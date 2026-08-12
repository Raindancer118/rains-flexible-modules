package de.raindancer.modules.moderation.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.ApproachReading;
import de.raindancer.modules.moderation.model.MinedBlock;
import de.raindancer.modules.moderation.model.MiningTrail;
import de.raindancer.modules.moderation.model.MiningWindow;
import de.raindancer.modules.moderation.model.PlayerMiningProfile;
import de.raindancer.modules.moderation.model.ServerMiningBaseline;
import de.raindancer.modules.moderation.rules.XrayRule;
import de.raindancer.modules.moderation.store.PersistedFindings;
import de.raindancer.modules.moderation.store.PlayerMiningProfiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching what everybody mines, and filing a report when one player's own pattern looks like x-ray.
 *
 * <h2>Five things this learns from, and why they are kept apart</h2>
 * Each player has their own {@link MiningWindow} — the last few hundred blocks <em>they</em> broke, as
 * nothing but counts, which is what {@link XrayRule} judges as cheaply as possible on every single
 * block. Alongside it, the same player's {@link MiningTrail} keeps the same stretch of digging as
 * actual positions, and both of those are session-only and cost nothing to lose on a restart — the
 * trail's whole job is computing one {@link ApproachReading} the moment an ore block is found, and
 * once that is done, the raw stone-by-stone context behind it has done its job.
 *
 * <p>{@link #profiles} and {@link #findings}, by contrast, are both kept across restarts: the first a
 * longer-memory, much slower-moving {@link PlayerMiningProfile} per player, which
 * {@link #probabilityFor} is built from; the second every {@link ApproachReading} the trail has ever
 * worked out, capped per player, which {@link #approachesFor} reads. Restarting used to lose the
 * second half of that pair even though the first survived — a moderator could see a probability with
 * nothing behind it once the review screen opened. See {@link PersistedFindings} for why keeping the
 * readings rather than the raw trail is what makes that affordable to keep for good.
 *
 * <p>The server as a whole has one shared {@link ServerMiningBaseline}, fed by every player's every
 * block, which only ever raises {@link XrayRule}'s threshold above the owner's configured floor — see
 * that class for why it must never be allowed to lower it.
 */
public final class XrayDetectionService implements IModerationService {

    private final ReportService reports;
    private final XrayRule rule;
    private final PlayerMiningProfiles profiles;
    private final PersistedFindings findings;
    private final ServerMiningBaseline baseline = new ServerMiningBaseline();
    private final Map<UUID, MiningWindow> windows = new ConcurrentHashMap<>();
    private final Map<UUID, MiningTrail> trails = new ConcurrentHashMap<>();
    private final Cooldowns<UUID> between = new Cooldowns<>();

    private volatile ModerationSettings settings;

    public XrayDetectionService(ReportService reports, XrayRule rule, PlayerMiningProfiles profiles,
                                PersistedFindings findings, ModerationSettings settings) {
        this.reports = reports;
        this.rule = rule;
        this.profiles = profiles;
        this.findings = findings;
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
     * shape as the per-player window it is compared against. Whether it is watched ore is decided here,
     * once, from the material on {@code block} — the caller hands over what was broken and nothing
     * more, so there is exactly one place reading the configured list rather than two that have to be
     * kept agreeing with each other.
     */
    public void mined(UUID player, String playerName, MinedBlock block) {
        if (player == null || block == null) {
            return;
        }
        ModerationSettings now = settings;
        boolean isValuableOre = isWatched(block.material(), now.xrayOres());
        baseline.record(isValuableOre);
        if (!now.xrayDetectionEnabled()) {
            return;
        }
        MiningWindow window = windows.computeIfAbsent(player,
                ignored -> new MiningWindow(now.xrayWindowBlocks()));
        window.record(isValuableOre);
        MiningTrail trail = trails.computeIfAbsent(player,
                ignored -> new MiningTrail(now.xrayWindowBlocks()));
        trail.record(block);

        long observedAt = System.currentTimeMillis();
        PlayerMiningProfile profile = profiles.of(player);
        profile.recordBlock(isValuableOre, observedAt);
        if (!isValuableOre) {
            // The ratio can only newly cross the threshold on the block that raised it — checking on
            // every ordinary block mined as well would be a great deal of arithmetic for no chance of
            // a different answer, and there is no approach reading for a block that is not ore.
            return;
        }
        // Only this one block's own approach is worked out — see MiningTrail#approachToMostRecent's
        // own note on why recomputing every other ore block's reading in the trail, on every single
        // block mined, would be paying every time for work already done the moment each of those
        // earlier blocks was mined. Folded into both the long-running average and the persisted list
        // of findings the moment it exists, so a restart a second later loses neither.
        trail.approachToMostRecent(now.xrayOres()).ifPresent(reading -> {
            profile.recordApproach(reading.directnessPercent(), observedAt);
            findings.add(player, reading);
        });

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

    /**
     * Every ore this player has ever been found mining, most direct approach first — the thing worth
     * reading before a report like this one turns into a ban. See {@code XrayReviewMenu}, the one
     * place this is ever shown to anybody, and {@link PersistedFindings} for why this answers exactly
     * the same way before and after a restart.
     */
    public List<ApproachReading> approachesFor(UUID player) {
        List<ApproachReading> readings = new ArrayList<>(findings.of(player));
        readings.sort(Comparator.comparingInt(ApproachReading::directnessPercent).reversed());
        return readings;
    }

    /**
     * Lets go of a player's window, trail and wait. Called when they leave.
     *
     * <p>Their {@link PlayerMiningProfile} is deliberately not touched here — the whole reason it
     * exists is to answer for somebody who is not currently online, and wiping it on the one event
     * that makes that true would be exactly backwards.
     */
    public void forget(UUID who) {
        windows.remove(who);
        trails.remove(who);
        between.forget(who);
    }

    /**
     * A single number out of a hundred for how worth checking this player is — never a verdict, and
     * never shown without also showing {@link #approachesFor} so a human can see why. See
     * {@link PlayerMiningProfile#probabilityPercent} for how the number is actually built.
     */
    public int probabilityFor(UUID player) {
        if (player == null) {
            return 0;
        }
        ModerationSettings now = settings;
        int threshold = rule.effectiveThresholdPercent(now.xrayThresholdPercent(),
                now.xrayLearningEnabled(), baseline.ratio(), now.xrayLearnedMultiplier());
        return profiles.of(player).probabilityPercent(threshold);
    }

    /**
     * Everybody who has actually been found mining a watched ore — the list {@code XraySuspicionMenu}
     * is built from. Not everybody with a {@link PlayerMiningProfile}: that includes anybody who has
     * ever mined a single block of anything, ore or not, and a leaderboard built from it would list
     * the whole server for a question most of them have never given a reason to ask.
     */
    public Set<UUID> everybodyWorthReviewing() {
        return findings.everybody();
    }

    /** Reads what is on disk. Called once, when the module starts. */
    public void load() {
        profiles.load();
        findings.load();
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        boolean profilesOk = profiles.flush();
        boolean findingsOk = findings.flush();
        return profilesOk && findingsOk;
    }

    /** What the server has learnt is normal here, for a diagnostic. */
    public double learnedRatio() {
        return baseline.ratio();
    }

    private static boolean isWatched(String material, List<String> oreNames) {
        for (String name : oreNames) {
            if (name != null && name.equalsIgnoreCase(material)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return "watching everybody's mining for a pattern that looks like x-ray";
    }
}
