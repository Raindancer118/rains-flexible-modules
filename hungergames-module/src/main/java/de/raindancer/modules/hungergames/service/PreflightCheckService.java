package de.raindancer.modules.hungergames.service;

import de.raindancer.core.data.settings.SettingsAudit;
import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Rules;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.BorderMath;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.LootCatalogue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Eleven questions asked of a round before {@code /startup} is allowed to touch it: is there an arena, are
 * there enough tributes, does the border actually finish, is the loot sane, is WorldEdit even here.
 *
 * <h2>Why the blocking decision goes through Core's {@code Rules}</h2>
 * Whether a red finding blocks the start is not this class inventing a second pass/fail mechanism next to
 * the sentence it prints — it is {@link Rules#judge}, the same chain-of-checks machinery
 * {@code claims-module}'s admission gates use. Each of the eleven checks below is wrapped once, in
 * {@link #chain}, as a small anonymous {@link AbstractRule}; its {@link de.raindancer.core.platform.rule.IRule#judge}
 * only ever answers {@link Verdict#refused} for the one severity that must stop a start — everything a
 * gamemaster may knowingly override answers {@link Verdict#allowed}, exactly like {@code ConfigurationRules}'
 * "warnings, never refusals" for the config-time version of the same idea. {@link #canStart} is nothing more
 * than reading that chain's verdict; {@link #runAll} produces the friendlier, per-check sentence list a
 * screen actually shows, computed by the very same eleven methods the chain wraps.
 *
 * <h2>Collaborators that do not exist in this module yet</h2>
 * Sponsor shops, supply drops, gamemaster mode and the arena's own geometry belong to services other agents
 * are porting alongside this one. Rather than block on all of that landing first — or invent throwaway
 * copies of it here, which is exactly what {@code ReuseTest} exists to catch once the real thing does land —
 * every one of those questions arrives as a small seam interface, the same pattern
 * {@code GameControlService.Stage} uses. A host wiring this module before its neighbours exist can hand in
 * the most conservative honest answer for each (empty, not installed, nobody online) without this class
 * ever needing to know that is what happened.
 */
public final class PreflightCheckService implements IHungerGamesService {

    /** How bad a single finding is. */
    public enum Severity {
        OK,
        WARNING,
        ERROR
    }

    /** One check's answer, in the exact shape a preflight screen prints a row from. */
    public record CheckResult(String name, Severity severity, String detail) {

        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }

    // ==================== collaborators this module does not own yet ====================

    /** Where the arena stands right now, described for a human — empty before {@code /init}. */
    @FunctionalInterface
    public interface ArenaStatus {
        Optional<String> centreDescription();
    }

    /** Whether WorldEdit, which every schematic placement needs, is on this server at all. */
    @FunctionalInterface
    public interface WorldEditPresence {
        boolean isInstalled();
    }

    /** Whether a tribute happens to be connected right now. */
    @FunctionalInterface
    public interface OnlinePlayers {
        boolean isOnline(UUID uuid);
    }

    /** Everybody currently in gamemaster mode and online — a small slice of whatever service owns that mode. */
    @FunctionalInterface
    public interface OnlineGamemasters {
        Collection<UUID> onlineActive();
    }

    /** The supply-drop timetable and the table it draws from — data another store owns. */
    public interface SupplyDropPlan {
        List<Duration> schedule();

        String lootTableName();
    }

    /** Whether the sponsor shop is switched on, and whether its configured lines actually parse. */
    public interface SponsorShopStatus {
        boolean enabled();

        Optional<String> validationError();
    }

    private final GameSession session;
    private final OpTrackerService opTracker;
    private final LootCatalogue lootTables;
    private final ArenaStatus arena;
    private final WorldEditPresence worldEdit;
    private final OnlinePlayers online;
    private final OnlineGamemasters gamemasters;
    private final SupplyDropPlan supplyDrops;
    private final SponsorShopStatus sponsorShop;
    private final Supplier<List<String>> soundProblems;

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    /** Every check, wrapped as a Core rule — see the class note on why the blocking decision lives here. */
    private final Rules<List<BorderPhaseConfig>> chain = Rules.of(
            rule("Arena", phases -> checkArena()),
            rule("Participants", phases -> checkParticipants()),
            rule("Teams", phases -> checkTeams()),
            rule("Border", this::checkBorder),
            rule("Supply drops", phases -> checkSupplyDrops()),
            rule("Sponsors", phases -> checkSponsorShop()),
            rule("Sounds", phases -> checkSounds()),
            rule("Gamemasters", phases -> checkGamemasters()),
            rule("Admin tributes", phases -> checkAdminParticipants()),
            rule("Loot", phases -> checkLoot()),
            rule("WorldEdit", phases -> checkWorldEdit()));

    public PreflightCheckService(GameSession session, OpTrackerService opTracker, LootCatalogue lootTables,
                                  ArenaStatus arena, WorldEditPresence worldEdit, OnlinePlayers online,
                                  OnlineGamemasters gamemasters, SupplyDropPlan supplyDrops,
                                  SponsorShopStatus sponsorShop, Supplier<List<String>> soundProblems) {
        this.session = session;
        this.opTracker = opTracker;
        this.lootTables = lootTables;
        this.arena = arena;
        this.worldEdit = worldEdit;
        this.online = online;
        this.gamemasters = gamemasters;
        this.supplyDrops = supplyDrops;
        this.sponsorShop = sponsorShop;
        this.soundProblems = soundProblems;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** Every check's sentence, in a fixed, sensible order — arena first, WorldEdit last. */
    public List<CheckResult> runAll(List<BorderPhaseConfig> borderPhases) {
        List<CheckResult> results = new ArrayList<>();
        results.add(checkArena());
        results.add(checkParticipants());
        results.add(checkTeams());
        results.add(checkBorder(borderPhases));
        results.add(checkSupplyDrops());
        results.add(checkSponsorShop());
        results.add(checkSounds());
        results.add(checkGamemasters());
        results.add(checkAdminParticipants());
        results.add(checkLoot());
        results.add(checkWorldEdit());
        return List.copyOf(results);
    }

    /** Whether at least one of a computed result list is an {@link Severity#ERROR} — the plain, no-Core way
     * to ask the same question {@link #canStart} answers through the rule chain. */
    public boolean hasBlockingErrors(List<CheckResult> results) {
        return results.stream().anyMatch(CheckResult::isError);
    }

    /** Whether the round may start right now, straight from Core's rule chain. */
    public boolean canStart(List<BorderPhaseConfig> borderPhases) {
        return chain.judge(borderPhases).isAllowed();
    }

    /**
     * Every check's own objection, in Core's shape — for a caller that wants {@code Rules}' own findings
     * rather than {@link CheckResult}.
     *
     * <p><b>Only the refusals, not one verdict per check.</b> {@code Rules.judgeAll} evaluates every rule in
     * the chain regardless of how many already objected — nothing here stops early — and returns the
     * {@link Verdict}s that came back refused, in the order their checks run. Eleven checks that all pass
     * means an empty list, not eleven {@code allowed()} verdicts; a caller that wants every check's own
     * sentence whether it objected or not wants {@link #runAll}, not this.
     */
    public List<Verdict> judgeAll(List<BorderPhaseConfig> borderPhases) {
        return chain.judgeAll(borderPhases);
    }

    /**
     * The same eleven findings, folded into Core's {@link SettingsAudit} — the shape {@code ConfigurationRules}
     * uses for "this configuration will not do what it says", now that Core owns it directly rather than
     * every module hand-rolling its own {@code Finding} type next to it. Reaches for it instead of a second,
     * preflight-shaped audit record: {@link CheckResult} already exists for the eleven-name, blocking-aware
     * shape a screen renders, so what this method actually adds is a one-line {@link SettingsAudit#report}
     * for whoever wants that instead of walking {@link #runAll} by hand.
     *
     * <p>Purely a reporting convenience — never a second gate. {@link #canStart} is still the one and only
     * answer to whether the round may actually begin; {@code OK} findings are not carried into the audit at
     * all, since {@code SettingsAudit} has nothing to say about a check that passed.
     */
    public SettingsAudit audit(List<BorderPhaseConfig> borderPhases) {
        SettingsAudit audit = new SettingsAudit();
        for (CheckResult result : runAll(borderPhases)) {
            String message = result.name() + ": " + result.detail();
            switch (result.severity()) {
                case ERROR -> audit.broken(message);
                case WARNING -> audit.questionable(message);
                case OK -> { }
            }
        }
        return audit;
    }

    // ==================== individual checks ====================

    private CheckResult checkArena() {
        Optional<String> centre = arena.centreDescription();
        if (session.phase().isInitialized() && centre.isPresent()) {
            return ok("Arena", centre.get() + " (" + session.phase() + ")");
        }
        return error("Arena", "Not initialised — run /init or use the arena admin screen");
    }

    private CheckResult checkParticipants() {
        int alive = session.participants().aliveCount();
        int total = session.participants().all().size();
        if (total == 0) {
            return error("Participants", "No tributes registered — add some to the whitelist");
        }
        if (alive < GameControlService.MIN_PLAYERS) {
            return error("Participants", "Only " + alive + " living tribute(s) — at least "
                    + GameControlService.MIN_PLAYERS + " are needed");
        }
        long onlineNow = session.participants().all().stream()
                .filter(p -> p.isAlive() && online.isOnline(p.uuid()))
                .count();
        if (onlineNow < alive) {
            return warning("Participants", alive + " alive, but only " + onlineNow + " online");
        }
        return ok("Participants", alive + " living tribute(s), all online");
    }

    private CheckResult checkTeams() {
        var teams = session.teams().all();
        if (teams.isEmpty()) {
            return ok("Teams", "Solo mode (no teams)");
        }
        long teamless = session.participants().all().stream()
                .filter(p -> p.isAlive() && p.teamId().isEmpty())
                .count();
        if (teamless > 0) {
            return warning("Teams", teamless + " living tribute(s) with no team — assign them randomly?");
        }
        return ok("Teams", teams.size() + " team(s), every tribute assigned");
    }

    private CheckResult checkBorder(List<BorderPhaseConfig> phases) {
        try {
            BorderSettings border = new BorderSettings(settings.borderInitialSize(), settings.borderFloor(),
                    settings.borderEdgeSpeed(), phases);
            var conflicts = BorderMath.validate(border, Optional.of(settings.roundDuration()));
            if (!conflicts.isEmpty()) {
                return warning("Border", conflicts.size() + " conflict(s) — resolve them from the border "
                        + "screen");
            }
            if (settings.deathmatchEnabled()
                    && settings.deathmatchTargetBorderSize() < settings.borderFloor()) {
                return warning("Border", "The deathmatch target (" + settings.deathmatchTargetBorderSize()
                        + ") is below border.minimum-size (" + settings.borderFloor() + ")");
            }
            return ok("Border", phases.size() + " phase(s), no conflicts");
        } catch (RuntimeException broken) {
            return error("Border", "The border phases could not be read: " + broken.getMessage());
        }
    }

    private CheckResult checkSupplyDrops() {
        if (!settings.supplyDropsEnabled()) {
            return ok("Supply drops", "Disabled");
        }
        List<Duration> schedule = supplyDrops.schedule();
        Duration round = settings.roundDuration();
        long late = schedule.stream().filter(time -> time.compareTo(round) >= 0).count();
        if (late > 0) {
            return warning("Supply drops", late + " drop(s) are scheduled after the round ends ("
                    + round.toMinutes() + "m)");
        }
        String table = supplyDrops.lootTableName();
        if (!lootTables.exists(table)) {
            return error("Supply drops", "Loot table \"" + table + "\" does not exist");
        }
        return ok("Supply drops", schedule.size() + " scheduled, table \"" + table + "\" present");
    }

    private CheckResult checkSponsorShop() {
        if (!sponsorShop.enabled()) {
            return ok("Sponsors", "Disabled");
        }
        Optional<String> problem = sponsorShop.validationError();
        if (problem.isPresent()) {
            return error("Sponsors", "Shop: " + problem.get());
        }
        return ok("Sponsors", "The shop is valid");
    }

    private CheckResult checkSounds() {
        List<String> problems = soundProblems.get();
        if (!problems.isEmpty()) {
            return warning("Sounds", problems.size() + " cue problem(s) — see the effects screen");
        }
        return ok("Sounds", "No cue problems");
    }

    private CheckResult checkGamemasters() {
        if (!settings.gamemasterEnabled()) {
            return ok("Gamemasters", "Disabled");
        }
        Collection<UUID> activeOnline = gamemasters.onlineActive();
        List<String> playingGms = new ArrayList<>();
        for (UUID uuid : activeOnline) {
            if (session.isWhitelisted(uuid)) {
                playingGms.add(session.participants().nameOf(uuid).orElse(uuid.toString()));
            }
        }
        if (!playingGms.isEmpty()) {
            return warning("Gamemasters", "Active gamemasters are also tributes: "
                    + String.join(", ", playingGms));
        }
        return ok("Gamemasters", activeOnline.size() + " active, none of them playing");
    }

    private CheckResult checkAdminParticipants() {
        var ops = opTracker.opParticipants();
        if (ops.isEmpty()) {
            return ok("Admin tributes", "No operator tributes");
        }
        List<String> names = ops.stream()
                .map(uuid -> session.participants().nameOf(uuid).orElse(uuid.toString()))
                .sorted()
                .toList();
        if (settings.adminDeopOnStart()) {
            return ok("Admin tributes", "Deop plan: " + String.join(", ", names)
                    + " (re-opped on elimination/finish)");
        }
        return warning("Admin tributes", String.join(", ", names)
                + " are playing with OP — deop-on-start is off");
    }

    private CheckResult checkLoot() {
        List<String> problems = lootTables.problems();
        if (!problems.isEmpty()) {
            return warning("Loot", problems.size() + " invalid entr(y/ies) — see the loot screen");
        }
        if (lootTables.all().isEmpty()) {
            return error("Loot", "No loot tables found");
        }
        return ok("Loot", lootTables.all().size() + " table(s), all valid");
    }

    private CheckResult checkWorldEdit() {
        if (worldEdit.isInstalled()) {
            return ok("WorldEdit", "Present");
        }
        return error("WorldEdit", "WorldEdit is missing — arena schematics cannot be placed");
    }

    // ==================== plumbing ====================

    private static CheckResult ok(String name, String detail) {
        return new CheckResult(name, Severity.OK, detail);
    }

    private static CheckResult warning(String name, String detail) {
        return new CheckResult(name, Severity.WARNING, detail);
    }

    private static CheckResult error(String name, String detail) {
        return new CheckResult(name, Severity.ERROR, detail);
    }

    private static de.raindancer.core.platform.rule.IRule<List<BorderPhaseConfig>> rule(
            String name, Function<List<BorderPhaseConfig>, CheckResult> evaluate) {
        return new AbstractRule<>(name) {
            @Override
            public Verdict judge(List<BorderPhaseConfig> phases) {
                CheckResult result = evaluate.apply(phases);
                return result.severity() == Severity.ERROR
                        ? Verdict.refused(result.detail())
                        : Verdict.allowed();
            }
        };
    }

    @Override
    public String describe() {
        return "the eleven checks a round must pass before it may start";
    }
}
