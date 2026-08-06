package de.raindancer.modules.hungergames;

import de.raindancer.core.content.loot.LootEntry;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.OpTrackerService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.service.PreflightCheckService.CheckResult;
import de.raindancer.modules.hungergames.service.PreflightCheckService.Severity;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.command.RoundCommand;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * One tribute is a round. It has to be, because that is the only round anybody ever tests.
 *
 * <h2>What was reported, and why "one tribute has nobody to win against" was the wrong answer</h2>
 * An admin registered themselves as the single tribute, built an arena and ran the sequence — and got a
 * greyed-out Start button and a red <i>"Only 1 living tribute — at least 2 are needed"</i>. Which is a
 * defensible sentence about a tournament and a useless one about the thing an admin does forty times an
 * evening: stand in the arena alone and find out whether the loot is placed, whether the border moves,
 * whether the smoke bomb works, whether the protection period protects.
 *
 * <p>The refusal was inherited from a constant named {@link GameControlService#MIN_PLAYERS} whose comment
 * argued from sport — and every path that mattered was gated on it: {@code /init 1} was refused outright, the
 * number chooser would not go below two, the dry-run branch of {@code /startup} demanded two names on the
 * books, and preflight called a solo round a blocking error.
 *
 * <p>Nothing about the round itself needs two. Victory is resolved when somebody is <em>eliminated</em>, so a
 * solo round simply runs until it is ended or its clock expires — exactly what a rehearsal wants. So the floor
 * is one, and "this is not a tournament" is said as a <b>warning</b>, which is what that sentence always
 * was: a remark about the evening, not a fact about whether the software can run.
 *
 * <h2>Why this is four assertions and not one</h2>
 * Because the number two was written into four independent gates, and fixing the one that produced the error
 * message somebody saw would leave the other three refusing for their own reasons — which is how "fixed" and
 * "still does not work" end up both being true.
 */
class ASoloRoundIsTestableTest {

    private static final UUID SOLO = UUID.randomUUID();

    @Test
    @DisplayName("the floor is one tribute, because a solo round is the round admins actually run")
    void theFloorIsOne() {
        assertThat(GameControlService.MIN_PLAYERS)
                .as("every gate below reads this constant; raising it re-breaks all of them at once")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("/init 1 builds an arena rather than quoting a range")
    void oneIsBuildable() {
        List<Integer> built = new ArrayList<>();
        GameSession session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(1));
        GameControlService control = new GameControlService(session, actor -> false,
                (actor, count) -> {
                    built.add(count);
                    return true;
                },
                actor -> true, actor -> true);

        assertThat(control.init(UUID.randomUUID(), 1)).isEmpty();
        assertThat(built).containsExactly(1);
    }

    @Test
    @DisplayName("the command and the chooser both accept one")
    void oneIsTypeable() {
        // countIn is deliberately empty for anything out of range rather than clamping — so a "1" that came
        // back empty would be a /init that silently asked the chooser again, which is what it used to do.
        assertThat(RoundCommand.countIn(new String[] {"1"})).contains(1);
        assertThat(RoundCommand.countIn(new String[] {"0"})).isEmpty();
    }

    @Test
    @DisplayName("a single living tribute is a warning, and the round may still start")
    void oneDoesNotBlockPreflight(@TempDir Path dir) {
        GameSession session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        session.whitelistAdd(SOLO, "Solo");
        session.transitionTo(GamePhase.PREFLIGHT);

        PreflightCheckService preflight = healthyPreflight(session, dir);
        CheckResult participants = byName(preflight.runAll(List.of()), "Participants");

        assertThat(participants.severity())
                .as("'not a tournament' is a remark about the evening, not a reason the software cannot run")
                .isEqualTo(Severity.WARNING);
        assertThat(preflight.canStart(List.of()))
                .as("the Start button was greyed out for an admin standing alone in their own arena")
                .isTrue();
    }

    @Test
    @DisplayName("no tributes at all is still a blocking error")
    void zeroStillBlocks(@TempDir Path dir) {
        GameSession session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        session.transitionTo(GamePhase.PREFLIGHT);

        PreflightCheckService preflight = healthyPreflight(session, dir);
        CheckResult participants = byName(preflight.runAll(List.of()), "Participants");

        assertThat(participants.severity()).isEqualTo(Severity.ERROR);
        assertThat(preflight.canStart(List.of())).isFalse();
    }

    /** A fixture in which nothing except the tribute count is wrong. */
    private static PreflightCheckService healthyPreflight(GameSession session, Path dir) {
        RuntimeStore runtimeStore = new RuntimeStore(dir.resolve("runtime.yml"));
        RoundLogService roundLog = new RoundLogService(dir.resolve("logs"), uuid -> "u", id -> "t",
                mock(LogChannel.class));
        OpTrackerService opTracker = new OpTrackerService(session, new OpTrackerService.OpAccess() {
            @Override
            public boolean isOp(UUID uuid) {
                return false;
            }

            @Override
            public void setOp(UUID uuid, boolean op) {
            }
        }, runtimeStore, roundLog, (uuid, message) -> { });
        opTracker.settings(HungerGamesSettings.DEFAULTS);

        LootCatalogue lootCatalogue = new LootCatalogue(new LootTables(dir.resolve("loot.yml")));
        lootCatalogue.define("chest", 1, 100, List.of(LootEntry.of(Material.BREAD, 1)));

        PreflightCheckService preflight = new PreflightCheckService(session, opTracker, lootCatalogue,
                () -> Optional.of("0/0"),
                () -> true,
                uuid -> true,
                List::of,
                new PreflightCheckService.SupplyDropPlan() {
                    @Override
                    public List<Duration> schedule() {
                        return List.of(Duration.ofMinutes(5));
                    }

                    @Override
                    public String lootTableName() {
                        return "chest";
                    }
                },
                new PreflightCheckService.SponsorShopStatus() {
                    @Override
                    public boolean enabled() {
                        return false;
                    }

                    @Override
                    public Optional<String> validationError() {
                        return Optional.empty();
                    }
                },
                List::of);
        preflight.settings(HungerGamesSettings.DEFAULTS);
        return preflight;
    }

    private static CheckResult byName(List<CheckResult> results, String name) {
        return results.stream()
                .filter(result -> result.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name));
    }
}
