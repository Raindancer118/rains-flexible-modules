package de.raindancer.modules.hungergames.rules;

import de.raindancer.core.data.settings.SettingsAudit;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.BorderConflict;
import de.raindancer.modules.hungergames.model.BorderMath;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.model.GamePhase;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What about this configuration would not work, worked out before anybody plays a round on it.
 *
 * <h2>Why this exists at all</h2>
 * Almost nothing here would throw. A round with a border that cannot finish shrinking, monster waves
 * scheduled past the end of the game, or a deathmatch target below the border's own floor comes up
 * perfectly healthy, runs, and is simply not the tournament that was configured. The failure is silent by
 * construction: every one of these numbers is individually valid and only the combination is wrong.
 *
 * <p>So the combination is checked once, at startup, and said out loud. A warning on the console the
 * evening before is worth an unbounded amount compared with noticing at minute 149 in front of forty
 * people, and it costs a few hundred microseconds during {@code onEnable}.
 *
 * <h2>Warnings, never refusals</h2>
 * Nothing here stops the module starting, and that is deliberate. Every finding below is a judgement
 * about how a round will play rather than a fact about whether the code can run, and a server may have
 * chosen any of them on purpose — a tournament that deliberately wants a border nobody can dig away from,
 * or monster waves that stop early. Refusing to start over a judgement would be this class deciding what
 * somebody's tournament is, which it has no business doing.
 *
 * <p>A rule, so: no side effects, no server, no mutable state, safe from any thread. It takes the
 * settings and the phase list and returns sentences; the logging is somebody else's job.
 */
public final class ConfigurationRules implements IHungerGamesRule {

    /**
     * How fast somebody walled in by stone digs out with an iron pickaxe, in blocks per second.
     *
     * <p>{@code 1.5 hardness × 1.5 ÷ 6.0 tool speed} is 0.375 s a block, and a tunnel somebody can stand
     * up in costs two blocks for every step it advances — so 0.75 s per block of progress. No Efficiency
     * and no Haste: a tribute has what a chest gave them. See {@code BorderOutrunTest}, which owns this
     * arithmetic and checks it against the game's own numbers.
     */
    public static final double DIGGING_OUT_BLOCKS_PER_SECOND = 1.0D / 0.75D;

    /**
     * How long the arena must stand at its final size before the round is scheduled to end.
     *
     * <p>Fifteen minutes. The border finishing is not the finish — it is the moment the tournament becomes
     * a fight in a small box, which is the part everybody turned up for. A border that arrives at its final
     * size with two minutes left has technically done what it was configured to do and has skipped the
     * ending: whoever is alive at the clock wins on a count rather than on a fight.
     *
     * <p>Deliberately <em>not</em> called "the endgame", which is what the {@code deathmatch} is — an
     * actual feature that announces itself, pulls the border to its own target and teleports everybody in.
     * This is a number a configuration is checked against and nothing that happens in a round; naming it
     * after the feature made a validation constant read as though it did something.
     *
     * <p>Fifteen is long enough for a last stand and short enough that nobody is standing in a small box
     * getting bored.
     */
    public static final Duration MINIMUM_TIME_AT_FINAL_SIZE = Duration.ofMinutes(15);

    /**
     * Everything wrong with this configuration, worst first.
     *
     * <p>Returns Core's {@link SettingsAudit} rather than a type of this module's own. "Each of these
     * values is valid and the combination is wrong" is not a Hunger Games problem — it is what every module
     * with more than three settings has — so the shape it is said in, the two severities, the ordering and
     * the console block all belong in one place. This module's share is the arithmetic below.
     *
     * @param settings the settings as they are now
     * @param phases   the border phases as loaded — empty is a normal state, not a fault, since a fresh
     *                 install ships none
     */
    public SettingsAudit check(HungerGamesSettings settings, List<BorderPhaseConfig> phases) {
        SettingsAudit findings = new SettingsAudit();
        checkTheClock(settings, findings);
        checkTheBorder(settings, phases, findings);
        checkTheDeathmatch(settings, phases, findings);
        checkTheSupplyDrops(settings, findings);
        checkTheMonsterWaves(settings, findings);
        checkTheApi(settings, findings);
        // Ordering is SettingsAudit's — worst first, stable within a severity.
        return findings;
    }

    // ==================== the clock ====================

    private void checkTheClock(HungerGamesSettings settings, SettingsAudit findings) {
        Duration round = settings.roundDuration();
        Duration runUp = Duration.ofSeconds(settings.countdown()).plus(settings.gracePeriod());

        if (runUp.compareTo(round) >= 0) {
            findings.broken((("The countdown and the grace period together are %s, which is the whole "
                    + "%s round or more. Tributes would be released from their platforms and the round "
                    + "would already be over.")
                    .formatted(describe(runUp), describe(round))));
        } else if (runUp.multipliedBy(4).compareTo(round) > 0) {
            findings.questionable((("The countdown and the grace period take up %s of a %s round. "
                    + "Most of the tournament is people standing still.")
                    .formatted(describe(runUp), describe(round))));
        }

        if (settings.disconnectEliminationMinutes() > 0
                && Duration.ofMinutes(settings.disconnectEliminationMinutes()).compareTo(round) >= 0) {
            findings.questionable((("A tribute who disconnects is eliminated after %d minutes, which "
                    + "is longer than the %s round. Nobody will ever be eliminated for leaving — they "
                    + "will simply be counted as alive to the end.")
                    .formatted(settings.disconnectEliminationMinutes(), describe(round))));
        }
    }

    // ==================== the border ====================

    private void checkTheBorder(HungerGamesSettings settings, List<BorderPhaseConfig> phases,
                                SettingsAudit findings) {
        if (settings.borderInitialSize() < settings.borderFloor()) {
            findings.broken((("The border starts at %d blocks across, which is already below its own "
                    + "floor of %.0f. It has nowhere to shrink to.")
                    .formatted(settings.borderInitialSize(), settings.borderFloor())));
        }

        if (settings.borderEdgeSpeed() > DIGGING_OUT_BLOCKS_PER_SECOND) {
            findings.questionable((("The border may close at up to %.2f blocks per second. Somebody "
                    + "walled in by stone digs out at about %.2f with an iron pickaxe, so a tribute "
                    + "caught in a hillside cannot get away from it — the arena's terrain decides who "
                    + "dies rather than the round does. Deliberate for a tournament where hiding in a "
                    + "hole must not be a strategy; an accident otherwise.")
                    .formatted(settings.borderEdgeSpeed(), DIGGING_OUT_BLOCKS_PER_SECOND)));
        }

        if (phases.isEmpty()) {
            // Not a fault. A server that has not configured a shrink yet is a normal starting state, and
            // saying so every boot would train people to ignore this whole block.
            return;
        }

        BorderSettings border = new BorderSettings(settings.borderInitialSize(), settings.borderFloor(),
                settings.borderEdgeSpeed(), phases);
        Duration round = settings.roundDuration();

        for (BorderConflict conflict : BorderMath.validate(border, Optional.of(round))) {
            findings.broken(("Border phase %d: %s".formatted(conflict.phaseIndex() + 1,
                    describe(conflict, border, round))));
        }

        // How long the arena stands at its final size. See MINIMUM_TIME_AT_FINAL_SIZE.
        //
        // Deliberately measured from the *border phases* and not from the deathmatch, even though the
        // deathmatch pulls the border further in. The deathmatch has no time of its own to measure
        // against: it fires when two tributes are left, or when a gamemaster calls it, and neither is
        // something a clock can predict from a config file. A round whose phases finish two minutes
        // before the end is misconfigured whether or not a deathmatch is also coming — because the
        // deathmatch might not come at all, and if it does it will be even later.
        //
        // What the deathmatch does change is what "final size" means, which is checked separately in
        // checkTheDeathmatch: its target has to be inside the border's own floor, or the two features
        // disagree about how small the arena may get.
        lastPhaseEnds(border).ifPresent(ends -> {
            if (ends.compareTo(round) > 0) {
                return;   // already reported as EXCEEDS_GAME_TIME above; saying it twice helps nobody
            }
            Duration atFinalSize = round.minus(ends);
            if (atFinalSize.compareTo(MINIMUM_TIME_AT_FINAL_SIZE) < 0) {
                findings.broken((("The border reaches its final size at %s of a %s round, so the arena "
                        + "only stands at that size for %s. At least %s is wanted: the border finishing is "
                        + "not the finish, it is the start of the fight in a small box that everybody "
                        + "turned up for. With less than that, whoever is alive at the clock wins on a "
                        + "count rather than on a fight. Trigger the last phase earlier, or make the round "
                        + "longer.")
                        .formatted(describe(ends), describe(round), describe(atFinalSize),
                                describe(MINIMUM_TIME_AT_FINAL_SIZE))));
            }
        });
    }

    /** When the last phase stops moving, when every phase has a time trigger to work that out from. */
    private static Optional<Duration> lastPhaseEnds(BorderSettings border) {
        Duration latest = null;
        for (int phase = 0; phase < border.phases().size(); phase++) {
            Optional<Duration> triggers = border.phases().get(phase).trigger().time();
            if (triggers.isEmpty()) {
                // Triggered by how many are still alive, which no clock can predict. Nothing to say.
                continue;
            }
            Duration ends = triggers.get().plus(BorderMath.effectiveDuration(border, phase));
            if (latest == null || ends.compareTo(latest) > 0) {
                latest = ends;
            }
        }
        return Optional.ofNullable(latest);
    }

    private static String describe(BorderConflict conflict, BorderSettings border, Duration round) {
        return switch (conflict.type()) {
            case EXCEEDS_GAME_TIME -> ("it is still closing when the %s round ends, so the arena never "
                    + "reaches the size it is configured to finish at. Trigger it earlier, give it a "
                    + "nearer target, or raise the speed ceiling above %.2f blocks per second.")
                    .formatted(describe(round), border.maxEdgeSpeed());
            case SPEED_EXCEEDS_MAX -> ("it would have to move at %.2f blocks per second and the ceiling "
                    + "is %.2f, so it will take longer than the phase asks for.")
                    .formatted(conflict.impliedSpeed(), conflict.limit());
            case TARGET_BELOW_MINIMUM -> ("its target is below the border's floor of %.0f blocks, so it "
                    + "will stop short of where it was told to go.").formatted(conflict.limit());
            case TARGET_NOT_SHRINKING -> ("its target is not smaller than the size it starts from (%.0f "
                    + "blocks), so this phase does nothing.").formatted(conflict.limit());
            case PHASES_OUT_OF_ORDER -> ("it triggers before the phase in front of it, so the phases will "
                    + "not run in the order they are written.");
        };
    }

    // ==================== the deathmatch ====================

    private void checkTheDeathmatch(HungerGamesSettings settings, List<BorderPhaseConfig> phases,
                                    SettingsAudit findings) {
        if (!settings.deathmatchEnabled()) {
            return;
        }

        // Where the border phases already leave the arena. The deathmatch is meant to be a further,
        // dramatic tightening; if its target is not actually smaller than what the phases already
        // achieved, calling it announces itself, teleports everybody in, and closes nothing — which
        // reads to the people watching as the feature being broken.
        if (!phases.isEmpty()) {
            double afterThePhases = phases.get(phases.size() - 1).targetSize();
            if (settings.deathmatchTargetBorderSize() >= afterThePhases) {
                findings.broken((("The deathmatch shrinks the border to %d blocks, and the border "
                        + "phases have already brought it to %.0f. The deathmatch would close nothing: "
                        + "it announces itself, pulls everybody to the middle, and the arena stays "
                        + "exactly the size it already was. Give it a target below %.0f, or accept that "
                        + "the phases are the whole shrink and the deathmatch is only the teleport.")
                        .formatted(settings.deathmatchTargetBorderSize(), afterThePhases,
                                afterThePhases)));
            }
        }

        if (settings.deathmatchTargetBorderSize() < settings.borderFloor()) {
            findings.broken((("The deathmatch shrinks the border to %d blocks, which is below its own "
                    + "floor of %.0f. The border will stop short and the deathmatch arena will be bigger "
                    + "than it was configured to be.")
                    .formatted(settings.deathmatchTargetBorderSize(), settings.borderFloor())));
        }

        if (settings.deathmatchTargetBorderSize() > settings.borderInitialSize()) {
            findings.broken((("The deathmatch target of %d blocks is larger than the border ever "
                    + "starts at (%d). Calling it would open the arena up rather than close it.")
                    .formatted(settings.deathmatchTargetBorderSize(), settings.borderInitialSize())));
        }

        List<String> known = GamePhase.values() == null ? List.of()
                : java.util.Arrays.stream(GamePhase.values()).map(Enum::name).toList();
        for (String allowed : settings.deathmatchAllowedPhases()) {
            if (!known.contains(allowed.trim().toUpperCase(Locale.ROOT))) {
                findings.broken((("The deathmatch is allowed in phase '%s', which is not a phase this "
                        + "plugin has. Known phases: %s. A deathmatch called in a phase spelled this way "
                        + "is one that can never be called at all.")
                        .formatted(allowed, String.join(", ", known))));
            }
        }

        if (settings.deathmatchAllowedPhases().isEmpty()) {
            findings.broken(("The deathmatch is switched on but allowed in no phase at all, so it can "
                    + "never be called."));
        }
    }

    // ==================== supply drops ====================

    private void checkTheSupplyDrops(HungerGamesSettings settings, SettingsAudit findings) {
        if (!settings.supplyDropsEnabled()) {
            return;
        }

        if (settings.supplyDropRadiusMin() > settings.supplyDropRadiusMax()) {
            findings.broken((("Supply drops are told to land between %d and %d blocks from the middle, "
                    + "which is no distance at all — the minimum is beyond the maximum.")
                    .formatted(settings.supplyDropRadiusMin(), settings.supplyDropRadiusMax())));
        }

        // The border is a diameter; half of it is how far the edge is from the middle.
        double reach = settings.borderInitialSize() / 2.0;
        if (settings.supplyDropRadiusMax() > reach) {
            findings.questionable((("Supply drops may land up to %d blocks from the middle, and the "
                    + "border's edge starts %.0f blocks out. Some drops will land outside the border, "
                    + "where nobody can reach them.")
                    .formatted(settings.supplyDropRadiusMax(), reach)));
        }

        if (settings.supplyDropCount() <= 0) {
            findings.questionable(("Supply drops are switched on and the number dropped each time is "
                    + "zero, so the announcement will fire and nothing will land."));
        }
    }

    // ==================== monster waves ====================

    private void checkTheMonsterWaves(HungerGamesSettings settings, SettingsAudit findings) {
        int waves = settings.monsterWaveWaveCount();
        int interval = settings.monsterWaveIntervalSeconds();
        if (waves <= 0 || interval <= 0) {
            return;
        }

        Duration allWaves = Duration.ofSeconds((long) (waves - 1) * interval);
        if (allWaves.compareTo(settings.roundDuration()) > 0) {
            findings.questionable((("%d monster waves %d seconds apart take %s, and the round is %s. "
                    + "The later waves will never arrive.")
                    .formatted(waves, interval, describe(allWaves), describe(settings.roundDuration()))));
        }

        if (settings.monsterWaveCountPerWave() <= 0) {
            findings.questionable(("Monster waves are configured with no monsters in them."));
        }
    }

    // ==================== the API ====================

    private void checkTheApi(HungerGamesSettings settings, SettingsAudit findings) {
        if (!settings.apiEnabled()) {
            return;
        }

        String address = settings.apiBindAddress() == null ? "" : settings.apiBindAddress().trim();
        if (address.equals("0.0.0.0") || address.equals("::")) {
            findings.questionable((("The HTTP API is bound to %s, which is every network interface "
                    + "this machine has. There is no TLS: anybody who can reach the machine can reach "
                    + "the API. Intended only behind a closed network or a reverse proxy.")
                    .formatted(address)));
        }
    }

    // ==================== plumbing ====================

    /** A duration as somebody would say it. */
    private static String describe(Duration duration) {
        long minutes = duration.toMinutes();
        long seconds = duration.toSecondsPart();
        if (minutes == 0) {
            return seconds + "s";
        }
        return seconds == 0 ? minutes + " min" : "%d min %ds".formatted(minutes, seconds);
    }

    @Override
    public String describe() {
        return "what about this configuration would not work";
    }
}
