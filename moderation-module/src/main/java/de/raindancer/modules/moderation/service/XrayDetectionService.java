package de.raindancer.modules.moderation.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.MiningWindow;
import de.raindancer.modules.moderation.model.ServerMiningBaseline;
import de.raindancer.modules.moderation.rules.XrayRule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching what everybody mines, and filing a report when one player's own pattern looks like x-ray.
 *
 * <h2>Two things this learns from, and why they are kept apart</h2>
 * Each player has their own {@link MiningWindow} — the last few hundred blocks <em>they</em> broke,
 * which is what {@link XrayRule} judges. The server as a whole has one shared
 * {@link ServerMiningBaseline}, fed by every player's every block, which only ever raises the
 * threshold above the owner's configured floor — see that class for why it must never be allowed to
 * lower it.
 */
public final class XrayDetectionService implements IModerationService {

    private final ReportService reports;
    private final XrayRule rule;
    private final ServerMiningBaseline baseline = new ServerMiningBaseline();
    private final Map<UUID, MiningWindow> windows = new ConcurrentHashMap<>();
    private final Cooldowns<UUID> between = new Cooldowns<>();

    private volatile ModerationSettings settings;

    public XrayDetectionService(ReportService reports, XrayRule rule, ModerationSettings settings) {
        this.reports = reports;
        this.rule = rule;
        settings(settings);
    }

    @Override
    public void settings(ModerationSettings fresh) {
        this.settings = fresh == null ? ModerationSettings.DEFAULTS : fresh;
        between.every(this.settings.xrayCooldown());
    }

    /**
     * Records one block this player mined, and files a report if their window now looks like x-ray.
     *
     * <p>Every mined block feeds the server-wide baseline, whether or not it is one of the watched
     * ores — that is what makes the baseline a ratio of ore to <em>everything</em> mined, the same
     * shape as the per-player window it is compared against.
     *
     * @param isValuableOre whether the block just broken is on the watched list
     */
    public void mined(UUID player, String playerName, boolean isValuableOre) {
        if (player == null) {
            return;
        }
        ModerationSettings now = settings;
        baseline.record(isValuableOre);
        if (!now.xrayDetectionEnabled()) {
            return;
        }
        MiningWindow window = windows.computeIfAbsent(player,
                ignored -> new MiningWindow(now.xrayWindowBlocks()));
        window.record(isValuableOre);
        if (!isValuableOre) {
            // The ratio can only newly cross the threshold on the block that raised it — checking on
            // every ordinary block mined as well would be a great deal of arithmetic for no chance of
            // a different answer.
            return;
        }

        int threshold = rule.effectiveThresholdPercent(now.xrayThresholdPercent(),
                now.xrayLearningEnabled(), baseline.ratio(), now.xrayLearnedMultiplier());
        Verdict verdict = rule.mayBeFlagged(window.oreCount(), window.totalCount(),
                now.xrayMinimumOre(), threshold);
        if (verdict.isRefused() || !between.tryUse(player)) {
            return;
        }
        reports.file(null, null, player, playerName,
                "mining pattern looks like x-ray: " + window.oreCount() + "/" + window.totalCount()
                        + " of the last blocks mined were valuable ore (threshold " + threshold + "%)");
    }

    /** Lets go of a player's window and wait. Called when they leave. */
    public void forget(UUID who) {
        windows.remove(who);
        between.forget(who);
    }

    /** What the server has learnt is normal here, for a diagnostic. */
    public double learnedRatio() {
        return baseline.ratio();
    }

    @Override
    public String describe() {
        return "watching everybody's mining for a pattern that looks like x-ray";
    }
}
