package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.ReportKind;

import de.raindancer.core.moderation.reports.AutoReports;
import de.raindancer.modules.moderation.AntiCheatSettings;
import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.rules.ConfidenceRule;

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
public final class AutoReportService implements IModerationService {

    /** The movement kind, whose report text starts with this word; combat and world have their own. */
    public static final String KIND = ReportKind.MOVEMENT.key();

    private static final int MOST_HITS = 600;
    private static final long CHECK_EVERY_MILLIS = 1000;
    /** After a report or a duplicate: the queue has it, nobody needs asking again for a while. */
    private static final long AFTER_FILED_MILLIS = 10 * 60_000L;
    /** Nobody listening, or refused: try again soon, the flags are still there. */
    private static final long AFTER_UNFILED_MILLIS = 60_000L;

    private final LongSupplier clock;
    private final ConfidenceRule rule = new ConfidenceRule();
    /** Per player and kind: movement, combat and world are judged and reported apart. */
    private final Map<String, Ledger> ledgers = new ConcurrentHashMap<>();
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
     * A flag that was counted. Cheap for everything that is no kind of hack report.
     *
     * @param movementLevel the violation level of all checks of the same kind together, after decay
     * @param where         "world x y z", for the report
     */
    public void flagged(UUID id, String name, CheckType check, double weight, String detail, double movementLevel,
                        int ping, double tps, String where) {
        AntiCheatSettings now = settings;
        ReportKind kind = ReportKind.of(check).orElse(null);
        if (!now.autoReport() || kind == null) {
            return;
        }
        long at = clock.getAsLong();
        ConfidenceRule.Policy policy = kind.policy(now.reportPolicy());
        Ledger ledger = ledgers.computeIfAbsent(id + ":" + kind.key(), ignored -> new Ledger());
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
            ConfidenceRule.Verdict verdict = rule.judge(kind, java.util.List.copyOf(ledger.hits), movementLevel, at, policy);
            if (!verdict.confident()) {
                return;
            }
            text = String.format(Locale.ROOT, "%s · ping %d ms, %.1f tps · at %s · /anticheat log %s",
                    verdict.summary(), ping, tps, where, name);
        }
        AutoReports.Result result = AutoReports.file(id, name, kind.key(), text);
        synchronized (ledger) {
            ledger.quietUntil = at + (result == AutoReports.Result.FILED || result == AutoReports.Result.DUPLICATE
                    ? AFTER_FILED_MILLIS : AFTER_UNFILED_MILLIS);
        }
    }

    public void forget(UUID player) {
        ledgers.keySet().removeIf(key -> key.startsWith(player.toString()));
    }

    @Override
    public void settings(ModerationSettings fresh) {
        settings(fresh.antiCheat());
    }

    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "asking staff to look at somebody whose movement, combat or world checks keep failing";
    }
}
