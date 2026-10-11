package de.raindancer.modules.anticheat.service;

import de.raindancer.core.moderation.reports.AutoReports;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.rules.ConfidenceRule;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Remembers each player's recent movement flags and, when {@link ConfidenceRule} is sure it is a hack
 * and not a hiccup, files one staff report through Core's {@link AutoReports}. That is all it does:
 * nothing here sets anybody back, kicks or bans — staff read the report and decide.
 */
public final class AutoReportService implements IAntiCheatService {

    /** Kind of report: its text starts with this, and the queue keeps one open per player and kind. */
    public static final String KIND = "movement";

    private static final int MOST_HITS = 600;
    private static final long CHECK_EVERY_MILLIS = 1000;
    /** After a report or a duplicate: the queue has it, nobody needs asking again for a while. */
    private static final long AFTER_FILED_MILLIS = 10 * 60_000L;
    /** Nobody listening, or refused: try again soon, the flags are still there. */
    private static final long AFTER_UNFILED_MILLIS = 60_000L;

    private final LongSupplier clock;
    private final ConfidenceRule rule = new ConfidenceRule();
    private final Map<UUID, Ledger> ledgers = new ConcurrentHashMap<>();
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    public AutoReportService(LongSupplier clock) {
        this.clock = clock;
    }

    private static final class Ledger {
        final Deque<ConfidenceRule.Hit> hits = new ArrayDeque<>();
        long checkedAt;
        long quietUntil;
    }

    /**
     * A movement flag that was counted. Cheap for everything that is not movement.
     *
     * @param movementLevel the violation level of all movement checks together, after decay
     * @param where         "world x y z", for the report
     */
    public void flagged(UUID id, String name, CheckType check, double weight, String detail, double movementLevel,
                        int ping, double tps, String where) {
        AntiCheatSettings now = settings;
        if (!now.autoReport() || !ConfidenceRule.counts(check)) {
            return;
        }
        long at = clock.getAsLong();
        ConfidenceRule.Policy policy = now.reportPolicy();
        Ledger ledger = ledgers.computeIfAbsent(id, ignored -> new Ledger());
        String text;
        synchronized (ledger) {
            ledger.hits.addLast(new ConfidenceRule.Hit(at, check, weight, ping, tps, detail));
            while (!ledger.hits.isEmpty() && (ledger.hits.size() > MOST_HITS || ledger.hits.peekFirst().millis() < at - policy.windowMillis())) {
                ledger.hits.removeFirst();
            }
            if (at < ledger.quietUntil || at - ledger.checkedAt < CHECK_EVERY_MILLIS) {
                return;
            }
            ledger.checkedAt = at;
            ConfidenceRule.Verdict verdict = rule.judge(java.util.List.copyOf(ledger.hits), movementLevel, at, policy);
            if (!verdict.confident()) {
                return;
            }
            text = String.format(Locale.ROOT, "%s · ping %d ms, %.1f tps · at %s · /anticheat log %s",
                    verdict.summary(), ping, tps, where, name);
        }
        AutoReports.Result result = AutoReports.file(id, name, KIND, text);
        synchronized (ledger) {
            ledger.quietUntil = at + (result == AutoReports.Result.FILED || result == AutoReports.Result.DUPLICATE
                    ? AFTER_FILED_MILLIS : AFTER_UNFILED_MILLIS);
        }
    }

    public void forget(UUID player) {
        ledgers.remove(player);
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "asking staff to look at somebody whose movement checks keep failing";
    }
}
