package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.store.GameEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The round's own written record: kills, eliminations, revives and admin actions, one time-stamped line at
 * a time, in {@code logs/round-<time>.log} — a file a dispute the next day can be settled by reading rather
 * than by memory.
 *
 * <h2>Why this is not {@code context.log()}</h2>
 * {@code LogChannel} is the module's line in the server's shared console/file log — one stream, shared with
 * every other module, meant for an operator watching the console right now. A round log is the opposite: a
 * self-contained history of <em>one evening</em>, meant to be handed to somebody settling an argument about
 * who killed whom, days later, without wading through everything else the server logged in between. Pooling
 * the two would make neither job easy, which is why {@code LogChannel} is only reached for here when the
 * round log itself cannot be written — that failure genuinely does belong on the console.
 *
 * <h2>Why this implements {@link GameEvents} rather than adding a sixth listener interface</h2>
 * Every event this cares about — a phase change, a kill, an elimination, a revive, a winner — is already a
 * {@link GameEvents} method, and {@code store.GameSession} already calls all of them on every mutation. A
 * second, round-log-shaped event interface next to it would be one more thing a caller has to remember to
 * fire, and the two would drift the day {@code GameEvents} gains a case this class forgets to log. So this
 * class simply <em>is</em> a {@code GameEvents}, wired in beside — never instead of — the Bukkit
 * implementation that turns the same calls into this server's own events.
 *
 * <h2>Why this does not take a {@code GameSession}</h2>
 * {@code GameSession} is constructed <em>with</em> a {@code GameEvents} — this class, once wired — so a
 * constructor parameter of type {@code GameSession} here would be a cycle no caller could actually build.
 * {@link #participantName} and {@link #teamName} are the two lookups this class actually needs out of a
 * session, handed in as plain functions instead — typically {@code session.participants()::nameOf} composed
 * with {@code .orElse(uuid::toString)}, and {@code session.teams()::team} composed the same way.
 */
public final class RoundLogService implements GameEvents, IHungerGamesService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Where in the world something happened, spelled out just enough for a log line — no Bukkit needed. */
    public record Coordinates(String world, int x, int y, int z) {

        @Override
        public String toString() {
            return world + " " + x + "/" + y + "/" + z;
        }
    }

    private final Path logsDir;
    private final Function<UUID, String> participantName;
    private final Function<TeamId, String> teamName;
    private final LogChannel log;
    private final Supplier<LocalDateTime> clock;

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;
    private Path currentFile;

    /**
     * @param participantName a tribute's display name, never {@code null} — typically
     *                         {@code uuid -> session.participants().nameOf(uuid).orElse(uuid.toString())}
     * @param teamName         a team's display name, never {@code null} — typically
     *                         {@code id -> session.teams().team(id).map(Team::name).orElse(id.value())}
     * @param clock            the wall clock a file's name and every line's stamp are read from — injected
     *                         so a test can hand in a fixed instant rather than racing the real one
     */
    public RoundLogService(Path logsDir, Function<UUID, String> participantName, Function<TeamId, String> teamName,
                            LogChannel log, Supplier<LocalDateTime> clock) {
        this.logsDir = logsDir;
        this.participantName = participantName;
        this.teamName = teamName;
        this.log = log;
        this.clock = clock;
    }

    public RoundLogService(Path logsDir, Function<UUID, String> participantName, Function<TeamId, String> teamName,
                            LogChannel log) {
        this(logsDir, participantName, teamName, log, LocalDateTime::now);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    // ==================== writing ====================

    /** Writes one line, if the round log is switched on at all. */
    public synchronized void log(String category, String message) {
        if (!settings.roundLogEnabled()) {
            return;
        }
        Path file = targetFile();
        String line = clock.get().format(LINE_STAMP) + " [" + category + "] " + message
                + System.lineSeparator();
        try {
            Files.createDirectories(logsDir);
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            log.warn("The round log could not be written: {}", failure.getMessage());
        }
    }

    /** As {@link #log(String, String)}, with coordinates appended when {@code events.round-log.include-coordinates}
     * says so. */
    public void log(String category, String message, Coordinates where) {
        if (where != null && settings.roundLogIncludeCoordinates()) {
            message = message + " @ " + where;
        }
        log(category, message);
    }

    /** The file currently being written to, for a status screen — never throws even if it cannot be written. */
    public synchronized Path currentFile() {
        return targetFile();
    }

    private Path targetFile() {
        if (!settings.roundLogFilePerRound()) {
            return logsDir.resolve("rounds.log");
        }
        if (currentFile == null) {
            currentFile = logsDir.resolve("round-" + clock.get().format(FILE_STAMP) + ".log");
        }
        return currentFile;
    }

    // ==================== GameEvents ====================

    @Override
    public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        // A fresh round starts with PREFLIGHT — a new file, so a dispute about "last night" is not mixed
        // into whatever file happened to be open when the server was last restarted.
        if (newPhase == GamePhase.PREFLIGHT) {
            synchronized (this) {
                currentFile = null;
            }
        }
        log("PHASE", oldPhase + " -> " + newPhase);
    }

    @Override
    public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
        String by = killer == null ? "" : " by " + name(killer);
        log("ELIMINATION", name(participant) + by + " — " + remainingAlive + " tribute(s) remain");
    }

    @Override
    public void participantRevived(UUID participant) {
        log("REVIVE", name(participant) + " revived");
    }

    @Override
    public void whitelistChanged(UUID player, boolean added) {
        log("WHITELIST", name(player) + (added ? " added" : " removed"));
    }

    @Override
    public void teamCreated(de.raindancer.core.social.team.Team team) {
        log("TEAM", "team '" + team.name() + "' created");
    }

    @Override
    public void teamDeleted(de.raindancer.core.social.team.Team team) {
        log("TEAM", "team '" + team.name() + "' deleted");
    }

    @Override
    public void teamColourChanged(de.raindancer.core.social.team.Team team,
                                   de.raindancer.core.social.team.TeamColour oldColour,
                                   de.raindancer.core.social.team.TeamColour newColour) {
        log("TEAM", "team '" + team.name() + "' recoloured " + oldColour + " -> " + newColour);
    }

    @Override
    public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause) {
        log("TEAM", name(player) + " moved from " + describe(oldTeam) + " to " + describe(newTeam)
                + " (" + cause + ")");
    }

    @Override
    public void kill(UUID killer, UUID victim, int killerTotalKills) {
        log("KILL", name(killer) + " kills " + name(victim) + " (total kills: " + killerTotalKills + ")");
    }

    @Override
    public void winnerDeclared(Winner winner) {
        log("WINNER", describe(winner));
    }

    // ==================== plumbing ====================

    private String name(UUID uuid) {
        return participantName.apply(uuid);
    }

    private String describe(TeamId team) {
        return team == null ? "no team" : teamName.apply(team);
    }

    private String describe(Winner winner) {
        return switch (winner) {
            case Winner.Solo solo -> "solo winner: " + name(solo.uuid());
            case Winner.Team team -> "team winner: " + teamName.apply(team.teamId())
                    + " (" + describeMembers(team.members()) + ")";
            case Winner.None none -> "no winner";
        };
    }

    private String describeMembers(Set<UUID> members) {
        return members.stream().map(this::name).sorted().reduce((a, b) -> a + ", " + b).orElse("");
    }
}
