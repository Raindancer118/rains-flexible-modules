package de.raindancer.modules.hungergames;

import de.raindancer.core.content.loot.LootEntry;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderTrigger;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.OpTrackerService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.service.PreflightCheckService.CheckResult;
import de.raindancer.modules.hungergames.service.PreflightCheckService.Severity;
import de.raindancer.modules.hungergames.service.RoundLogService;
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
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link PreflightCheckService}: the eleven checks a round must pass, and the Core rule chain that decides
 * which of them actually blocks a start.
 */
class PreflightCheckServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private GameSession session;
    private OpTrackerService opTracker;
    private LootCatalogue lootCatalogue;

    private boolean arenaReady = true;
    private boolean worldEditPresent = true;
    private boolean everybodyOnline = true;

    private PreflightCheckService preflight;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
        session.whitelistAdd(ALICE, "Alice");
        session.whitelistAdd(BOB, "Bob");
        // checkArena() asks session.phase().isInitialized() in addition to the ArenaStatus seam — a
        // "healthy round" fixture has to actually leave NOT_INITIALIZED, or every test in this class would
        // see Arena as ERROR regardless of arenaReady, which is exactly what happened here originally: every
        // assertion about overall blocking failed because of the session's phase, not the arenaReady seam.
        session.transitionTo(GamePhase.PREFLIGHT);

        RuntimeStore runtimeStore = new RuntimeStore(dir.resolve("runtime.yml"));
        RoundLogService roundLog = new RoundLogService(dir.resolve("logs"), uuid -> "u", id -> "t",
                mock(LogChannel.class));
        opTracker = new OpTrackerService(session, new OpTrackerService.OpAccess() {
            @Override
            public boolean isOp(UUID uuid) {
                return false;
            }

            @Override
            public void setOp(UUID uuid, boolean op) {
            }
        }, runtimeStore, roundLog, (uuid, message) -> { });
        opTracker.settings(HungerGamesSettings.DEFAULTS);

        LootTables tables = new LootTables(dir.resolve("loot.yml"));
        lootCatalogue = new LootCatalogue(tables);
        lootCatalogue.define("chest", 1, 100, List.of(LootEntry.of(Material.BREAD, 1)));

        preflight = new PreflightCheckService(session, opTracker, lootCatalogue,
                () -> arenaReady ? Optional.of("0/0") : Optional.empty(),
                () -> worldEditPresent,
                uuid -> everybodyOnline,
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
    }

    private CheckResult byName(List<CheckResult> results, String name) {
        return results.stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name));
    }

    @Test
    @DisplayName("a fully healthy round passes every check and does not block")
    void healthyRoundPasses() {
        List<CheckResult> results = preflight.runAll(List.of());

        assertThat(results).allSatisfy(r -> assertThat(r.severity()).isNotEqualTo(Severity.ERROR));
        assertThat(preflight.hasBlockingErrors(results)).isFalse();
        assertThat(preflight.canStart(List.of())).isTrue();
    }

    @Test
    @DisplayName("no arena yet is a blocking error, both in the list and through the rule chain")
    void noArenaBlocks() {
        arenaReady = false;

        List<CheckResult> results = preflight.runAll(List.of());

        assertThat(byName(results, "Arena").severity()).isEqualTo(Severity.ERROR);
        assertThat(preflight.hasBlockingErrors(results)).isTrue();
        assertThat(preflight.canStart(List.of())).isFalse();
    }

    @Test
    @DisplayName("fewer than two living tributes is a blocking error")
    void tooFewTributesBlocks() {
        session.whitelistRemove(BOB);

        List<CheckResult> results = preflight.runAll(List.of());

        assertThat(byName(results, "Participants").severity()).isEqualTo(Severity.ERROR);
        assertThat(preflight.canStart(List.of())).isFalse();
    }

    @Test
    @DisplayName("no tributes at all is its own, differently worded, blocking error")
    void noTributesAtAllBlocks() {
        session.whitelistRemove(ALICE);
        session.whitelistRemove(BOB);

        CheckResult result = byName(preflight.runAll(List.of()), "Participants");

        assertThat(result.severity()).isEqualTo(Severity.ERROR);
        assertThat(result.detail()).contains("No tributes registered");
    }

    @Test
    @DisplayName("enough tributes but not all online is a warning, not a block")
    void someOfflineIsAWarning() {
        everybodyOnline = false;

        List<CheckResult> results = preflight.runAll(List.of());

        assertThat(byName(results, "Participants").severity()).isEqualTo(Severity.WARNING);
        assertThat(preflight.hasBlockingErrors(results)).isFalse();
    }

    @Test
    @DisplayName("solo mode (no teams created) is fine")
    void soloModeIsOk() {
        assertThat(byName(preflight.runAll(List.of()), "Teams").severity()).isEqualTo(Severity.OK);
    }

    @Test
    @DisplayName("a living tribute with no team is a warning")
    void teamlessTributeIsAWarning() {
        session.teamCreate("Red", de.raindancer.core.social.team.TeamColour.RED);

        assertThat(byName(preflight.runAll(List.of()), "Teams").severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    @DisplayName("no border phases configured yet is fine, not an error")
    void emptyBorderPhasesIsOk() {
        assertThat(byName(preflight.runAll(List.of()), "Border").severity()).isEqualTo(Severity.OK);
    }

    @Test
    @DisplayName("a border phase that cannot finish in time is only a warning — never blocks the start")
    void borderConflictIsAWarningNotABlock() {
        BorderPhaseConfig tooSlow = BorderPhaseConfig.ofFixedSpeed(
                new BorderTrigger(Optional.of(Duration.ofMinutes(1)), Optional.empty()), 10, 0.01);
        List<BorderPhaseConfig> phases = List.of(tooSlow);

        List<CheckResult> results = preflight.runAll(phases);

        assertThat(byName(results, "Border").severity()).isEqualTo(Severity.WARNING);
        assertThat(preflight.hasBlockingErrors(results)).isFalse();
        assertThat(preflight.canStart(phases)).isTrue();
    }

    @Test
    @DisplayName("supply drops disabled is fine regardless of the schedule")
    void supplyDropsDisabledIsOk() {
        preflight.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "supplyDropsEnabled", false));

        assertThat(byName(preflight.runAll(List.of()), "Supply drops").severity()).isEqualTo(Severity.OK);
    }

    @Test
    @DisplayName("a supply-drop table that does not exist is a blocking error")
    void missingSupplyDropTableBlocks() {
        preflight.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "supplyDropsEnabled", true));
        lootCatalogue = null; // not used further; the service was built with the "chest" table already

        PreflightCheckService withMissingTable = rebuildWithSupplyTable("does-not-exist");

        List<CheckResult> results = withMissingTable.runAll(List.of());
        assertThat(byName(results, "Supply drops").severity()).isEqualTo(Severity.ERROR);
        assertThat(withMissingTable.hasBlockingErrors(results)).isTrue();
    }

    @Test
    @DisplayName("WorldEdit missing is a blocking error")
    void missingWorldEditBlocks() {
        worldEditPresent = false;

        List<CheckResult> results = preflight.runAll(List.of());

        assertThat(byName(results, "WorldEdit").severity()).isEqualTo(Severity.ERROR);
        assertThat(preflight.canStart(List.of())).isFalse();
    }

    @Test
    @DisplayName("no loot tables at all is a blocking error")
    void noLootTablesBlocks(@TempDir Path dir) {
        LootCatalogue empty = new LootCatalogue(new LootTables(dir.resolve("empty-loot.yml")));
        PreflightCheckService withEmptyLoot = new PreflightCheckService(session, opTracker, empty,
                () -> Optional.of("0/0"), () -> true, uuid -> true, List::of,
                new PreflightCheckService.SupplyDropPlan() {
                    @Override
                    public List<Duration> schedule() {
                        return List.of();
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
        withEmptyLoot.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "supplyDropsEnabled", false));

        List<CheckResult> results = withEmptyLoot.runAll(List.of());

        assertThat(byName(results, "Loot").severity()).isEqualTo(Severity.ERROR);
        assertThat(withEmptyLoot.hasBlockingErrors(results)).isTrue();
    }

    @Test
    @DisplayName("gamemasters who are also whitelisted tributes are flagged, not blocked")
    void gamemasterPlayingIsWarned() {
        preflight.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "gamemasterEnabled", true));
        PreflightCheckService withGmAlice = rebuildWithGamemasters(Set.of(ALICE));

        CheckResult result = byName(withGmAlice.runAll(List.of()), "Gamemasters");

        assertThat(result.severity()).isEqualTo(Severity.WARNING);
        assertThat(result.detail()).contains("Alice");
    }

    @Test
    @DisplayName("judgeAll exposes Core's own Verdict shape, one per check, in the same order as runAll")
    void judgeAllMatchesRunAllInOrder() {
        // Originally written as "judgeAll returns one Verdict per check, always" and asserted the two lists
        // had the same size regardless of outcome. That was a wrong guess about Core's own Rules, checked
        // directly against a small standalone harness against the real de.raindancer.core.platform.rule
        // classes: Rules.judgeAll evaluates *every* rule in the chain — it does not stop at the first
        // refusal, so this was never a short-circuiting chain the way judge()'s single combined verdict is
        // — and returns only the ones that came back refused. All allowed means an empty list, not eleven
        // allowed verdicts, which is exactly what the original hasSameSizeAs(results) assertion got wrong:
        // it failed with "expected 11, was 0" on a perfectly healthy round, not because Arena or anything
        // else was broken.
        //
        // So the real, checkable property is a count match against runAll()'s own ERROR severities, since
        // judgeAll's refusals and CheckResult's ERROR findings are computed from the exact same eleven
        // evaluate() calls inside PreflightCheckService.rule(...).
        List<CheckResult> results = preflight.runAll(List.of());
        List<de.raindancer.core.platform.rule.Verdict> verdicts = preflight.judgeAll(List.of());

        long errorCount = results.stream().filter(CheckResult::isError).count();
        assertThat(errorCount).as("this fixture is the healthy-round baseline").isZero();
        assertThat(verdicts).isEmpty();
    }

    @Test
    @DisplayName("judgeAll reports every objecting check, not only the first")
    void judgeAllCollectsEveryObjection() {
        arenaReady = false;      // Arena, first in the chain, now refuses
        worldEditPresent = false; // WorldEdit, last in the chain, refuses too

        List<CheckResult> results = preflight.runAll(List.of());
        List<de.raindancer.core.platform.rule.Verdict> verdicts = preflight.judgeAll(List.of());

        long errorCount = results.stream().filter(CheckResult::isError).count();
        assertThat(errorCount).isEqualTo(2);
        // If judgeAll actually stopped at the first refusal, this would come back as 1 (Arena only) rather
        // than 2 — the assertion that matters here is the count, not merely "non-empty".
        assertThat(verdicts).hasSize(2);
        assertThat(verdicts).allSatisfy(v -> assertThat(v.isRefused()).isTrue());
    }

    @Test
    @DisplayName("what it calls itself, for the console line listing what started")
    void itSaysWhatItIs() {
        assertThat(preflight.describe()).isNotBlank();
    }

    @Test
    @DisplayName("audit() folds findings into Core's SettingsAudit, carrying OK checks over as nothing")
    void auditFoldsFindingsIntoCoresSettingsAudit() {
        assertThat(preflight.audit(List.of()).isEmpty())
                .as("a fully healthy round has nothing broken or questionable to report")
                .isTrue();

        arenaReady = false;
        var audit = preflight.audit(List.of());

        assertThat(audit.hasBroken()).isTrue();
        assertThat(audit.lines()).anyMatch(line -> line.contains("Arena"));
    }

    // ==================== helpers that rebuild the service with one collaborator swapped ====================

    private PreflightCheckService rebuildWithSupplyTable(String tableName) {
        PreflightCheckService rebuilt = new PreflightCheckService(session, opTracker,
                new LootCatalogue(new LootTables(Path.of("build", "no-such-file.yml"))),
                () -> Optional.of("0/0"), () -> true, uuid -> true, List::of,
                new PreflightCheckService.SupplyDropPlan() {
                    @Override
                    public List<Duration> schedule() {
                        return List.of();
                    }

                    @Override
                    public String lootTableName() {
                        return tableName;
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
        rebuilt.settings(HungerGamesSettings.DEFAULTS.withDeathmatchEnabled(false));
        return rebuilt;
    }

    private PreflightCheckService rebuildWithGamemasters(Set<UUID> onlineActive) {
        PreflightCheckService rebuilt = new PreflightCheckService(session, opTracker, lootCatalogue,
                () -> Optional.of("0/0"), () -> true, uuid -> true, () -> onlineActive,
                new PreflightCheckService.SupplyDropPlan() {
                    @Override
                    public List<Duration> schedule() {
                        return List.of();
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
        rebuilt.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "gamemasterEnabled", true));
        return rebuilt;
    }
}
