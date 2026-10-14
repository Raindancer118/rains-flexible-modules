package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.ReportKind;

import de.raindancer.modules.moderation.model.CheckType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether the flags of one kind — movement, combat, world — of one player add up to somebody using a hack, sure enough
 * to ask staff to look. One honest hiccup (a lag spike, a block the client was told about late, a
 * quirk nobody modelled) produces a burst of flags and then nothing; a hack produces flags for as long
 * as it is on. So the rule wants all of:
 *
 * <ul>
 *   <li>enough flags in the window, those made at a bad ping or server speed count for a quarter (ping can be faked);</li>
 *   <li>flags in several separate stretches of the window, not one burst;</li>
 *   <li>a violation level, after decay, that is still high — the hack is still going on;</li>
 *   <li>more flags if only one check saw it than if independent checks agree.</li>
 * </ul>
 */
public final class ConfidenceRule implements IModerationRule {

    /** Movement checks that mean a hack when they keep failing. Sprint (starving) is a food state, not a hack. */
    public static boolean counts(CheckType check) {
        return ReportKind.of(check).orElse(null) == ReportKind.MOVEMENT;
    }

    /** Three flags of something no vanilla client can send are proof enough, whatever else the window holds. */
    static final int IMPOSSIBLE_ENOUGH = 3;

    /** One counted flag. */
    public record Hit(long millis, CheckType check, double weight, int ping, double tps, String detail) {
        /**
         * Which check saw it, for telling independent checks apart: Grim's own check name, which the flag's detail
         * starts with ({@code "Simulation: …"}), else the family.
         */
        public String name() {
            String text = detail == null ? "" : detail;
            int colon = text.indexOf(':');
            String head = colon > 0 ? text.substring(0, colon).trim() : "";
            return head.isEmpty() || head.contains(" ") ? check.key() : head;
        }
    }

    /**
     * @param windowMillis how far back flags count
     * @param minFlags     flags needed when two or more checks agree; one check alone needs {@link #SOLO_FACTOR} times that
     * @param minLevel     the violation level of the movement checks that must still stand, after decay
     * @param maxPing      flags made above this ping count for a quarter
     * @param minTps       so do flags made while the server ran slower than this
     * @param minStretches how many separate stretches of {@code stretchMillis} must hold a flag
     */
    public record Policy(long windowMillis, int minFlags, double minLevel, int maxPing, double minTps, int minStretches,
                         long stretchMillis) {

        public static final Policy DEFAULT = new Policy(120_000, 20, 15, 300, 17, 3, 5_000);

        public Policy {
            windowMillis = Math.max(1000, windowMillis);
            minFlags = Math.max(1, minFlags);
            minStretches = Math.max(1, minStretches);
            stretchMillis = Math.max(100, stretchMillis);
        }
    }

    public record Verdict(boolean confident, String summary) {

        public static final Verdict NOT_SURE = new Verdict(false, "");
    }

    /** One check alone must show this many times the flags that independent checks would need together. */
    public static final int SOLO_FACTOR = 2;

    /** A check that saw it too, to count as independent: this many flags of its own. */
    public static final int INDEPENDENT_AT = 3;

    /** What a flag made at a bad ping or on a lagging server is worth, against 1 for a fair one. */
    public static final double LAGGED_WORTH = 0.25;

    public Verdict judge(List<Hit> hits, double level, long now, Policy policy) {
        return judge(ReportKind.MOVEMENT, hits, level, now, policy);
    }

    /** The same for one kind of hack: only that kind's flags count, and only that kind's level. */
    public Verdict judge(ReportKind kind, List<Hit> hits, double level, long now, Policy policy) {
        long from = now - policy.windowMillis();
        List<Hit> impossible = hits.stream().filter(hit -> hit.millis() >= from && hit.check().impossible()
                && ReportKind.of(hit.check()).orElse(null) == kind).toList();
        if (impossible.size() >= IMPOSSIBLE_ENOUGH) {
            Hit last = impossible.getLast();
            return new Verdict(true, String.format(Locale.ROOT, "%s x%d — no vanilla client can do that · last: %s",
                    last.check().key(), impossible.size(), last.detail()));
        }
        if (level < policy.minLevel()) {
            return Verdict.NOT_SURE;
        }
        Map<String, Double> perCheck = new java.util.HashMap<>();
        Map<String, Integer> raw = new java.util.LinkedHashMap<>();
        Set<Long> stretches = new HashSet<>();
        List<Hit> counted = new ArrayList<>();
        double weighed = 0;
        for (Hit hit : hits) {
            if (hit.millis() < from || ReportKind.of(hit.check()).orElse(null) != kind) {
                continue;
            }
            // A client can fake any ping by holding back its answers, so a bad ping never exempts: it only makes
            // each flag count for less. Honest lag is a burst; a faker has to keep it up and is reported later.
            double worth = hit.ping() > policy.maxPing() || hit.tps() < policy.minTps() ? LAGGED_WORTH : 1;
            counted.add(hit);
            weighed += worth;
            perCheck.merge(hit.name(), worth, Double::sum);
            raw.merge(hit.name(), 1, Integer::sum);
            stretches.add(hit.millis() / policy.stretchMillis());
        }
        if (counted.isEmpty() || stretches.size() < policy.minStretches()) {
            return Verdict.NOT_SURE;
        }
        long independent = perCheck.values().stream().filter(count -> count >= INDEPENDENT_AT).count();
        int needed = independent >= 2 ? policy.minFlags() : policy.minFlags() * SOLO_FACTOR;
        if (weighed < needed) {
            return Verdict.NOT_SURE;
        }
        long first = counted.get(0).millis();
        long lastAt = first;
        for (Hit hit : counted) {
            first = Math.min(first, hit.millis());
            lastAt = Math.max(lastAt, hit.millis());
        }
        // Stretches counted by the clock can touch three of them within eight seconds: it has to last as well.
        if (lastAt - first < (policy.minStretches() - 1) * policy.stretchMillis()) {
            return Verdict.NOT_SURE;
        }
        Hit last = counted.get(counted.size() - 1);
        StringBuilder checks = new StringBuilder();
        raw.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> checks.append(checks.isEmpty() ? "" : ", ").append(entry.getKey()).append(" x").append(entry.getValue()));
        return new Verdict(true, String.format(Locale.ROOT, "%s · %d flags in %d s · level %.0f · last: %s",
                checks, counted.size(), Math.max(1, Math.round((now - first) / 1000.0)), level, last.detail()));
    }

    @Override
    public String describe() {
        return "whether Grim's flags are sustained and agreed on enough to be a hack";
    }
}
