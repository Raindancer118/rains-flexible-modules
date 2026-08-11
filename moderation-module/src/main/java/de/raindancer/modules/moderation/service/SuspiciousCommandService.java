package de.raindancer.modules.moderation.service;

import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.rules.SuspiciousCommandRule;

import java.util.Optional;
import java.util.UUID;

/**
 * Watching for a typed command that only makes sense alongside an outside tool, and filing an
 * automatic report when one is seen.
 *
 * <h2>Why this goes into the same queue a player's own {@code /report} does</h2>
 * A second queue is a second thing for staff to remember to check, and {@code openReportsOnJoin}
 * already tells whoever comes on shift how many reports are waiting — so an automatic one is told the
 * same way, for free, rather than needing a feature of its own.
 *
 * <h2>Why the wait is on this service and not on {@code ReportRule}</h2>
 * {@code ReportRule} deliberately does not rate-limit a report filed with no reporter — see its own
 * note on why: the console, or an automated check, is not worth limiting the way a player is. That is
 * right for a single automated report, and wrong for this one specifically, because typing the same
 * command five times in a row would otherwise file five identical reports. The wait belongs here,
 * against the one thing actually repeating: this player typing this kind of command again.
 */
public final class SuspiciousCommandService implements IModerationService {

    private final ReportService reports;
    private final SuspiciousCommandRule rule;

    /**
     * Kept per player rather than per player-and-command: five different watched commands typed in a
     * row are still one person doing one suspicious thing, and five reports about it are no more
     * useful to a moderator than one.
     */
    private final Cooldowns<UUID> between = new Cooldowns<>();

    private volatile ModerationSettings settings;

    public SuspiciousCommandService(ReportService reports, SuspiciousCommandRule rule,
                                    ModerationSettings settings) {
        this.reports = reports;
        this.rule = rule;
        settings(settings);
    }

    @Override
    public void settings(ModerationSettings fresh) {
        this.settings = fresh == null ? ModerationSettings.DEFAULTS : fresh;
        between.every(this.settings.suspiciousCooldown());
    }

    /**
     * Looks at what somebody just typed, and files a report when it matches.
     *
     * <p>Silent either way: the whole point is that the player does not learn they have been flagged,
     * which is also why nothing here calls {@code event.setCancelled} — the command still runs exactly
     * as it would have.
     */
    public void check(UUID player, String playerName, String typedLabel) {
        if (player == null || !settings.suspiciousCommandsEnabled()) {
            return;
        }
        Optional<String> matched = rule.matched(typedLabel, settings.suspiciousCommands());
        if (matched.isEmpty()) {
            return;
        }
        if (!between.tryUse(player)) {
            return;
        }
        // reporter = null: filed by the check itself rather than by another player. ReportRule reads
        // that as the console and skips the limits meant for a player flooding the queue by hand.
        reports.file(null, null, player, playerName,
                "typed /" + matched.get() + " — flagged automatically as a suspicious command");
    }

    /** Lets go of a player's wait. Called when they leave. */
    public void forget(UUID who) {
        between.forget(who);
    }

    @Override
    public String describe() {
        return "watching for a typed command that suggests an outside tool, and auto-filing a report";
    }
}
