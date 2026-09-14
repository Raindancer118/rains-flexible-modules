package de.raindancer.modules.manhunt.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.sql.Schema;
import de.raindancer.modules.speedrun.SpeedrunOutcome;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every hunt that has ever finished — the record {@code manhunt-roadmap-to-1-0} named as the one
 * thing still missing beyond the four run-lifecycle areas: "no record of past hunts beyond
 * achievements". An achievement says a player once did a thing; this says what happened, to whom,
 * and how long it took, for every hunt rather than the first one.
 *
 * <h2>Its own database, not Core's</h2>
 * Same reasoning {@code farmworld-module}'s own {@code FarmWorldState} already gives for its table:
 * this is Manhunt's own concept, grows without bound over the
 * life of a server, and has nothing to do with what Core keeps for warps and homes. One row per hunt,
 * one row per participant per hunt — a Runner who plays a hundred hunts costs a hundred small rows,
 * not a column that grows sideways.
 *
 * <h2>Who won, worked out from the reason</h2>
 * {@link ManhuntService} already hands {@link SpeedrunOutcome#reason()} to every caller of
 * {@code onFinished} — {@code "portal-exit"} or an {@code "advancement:"} key for the Runners,
 * {@code "all-runners-dead"} or {@code "timeout"} for the Hunters, and {@code "manual"} or
 * {@code "plugin-disable"} for a hunt nobody won. {@link Winner#forReason} is that mapping, kept in
 * one place rather than re-derived at every call site that wants to know.
 */
public final class HuntHistory {

    public static final Schema SCHEMA = Schema.of(
            """
            CREATE TABLE hunt (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at       INTEGER NOT NULL,
                finished_at      INTEGER NOT NULL,
                elapsed_seconds  INTEGER NOT NULL,
                reason           TEXT NOT NULL,
                winner           TEXT NOT NULL
            )""",
            """
            CREATE TABLE hunt_participant (
                hunt_id    INTEGER NOT NULL REFERENCES hunt(id),
                player_id  TEXT NOT NULL,
                side       TEXT NOT NULL
            )""",
            "CREATE INDEX hunt_participant_hunt ON hunt_participant(hunt_id)");

    /** Which side a finished hunt actually went to, worked out from {@link SpeedrunOutcome#reason()}. */
    public enum Winner {
        RUNNERS, HUNTERS, NONE;

        public static Winner forReason(String reason) {
            if (reason == null) {
                return NONE;
            }
            if (reason.equals("portal-exit") || reason.startsWith("advancement:")) {
                return RUNNERS;
            }
            if (reason.equals("all-runners-dead") || reason.equals("timeout")) {
                return HUNTERS;
            }
            // "manual", "plugin-disable", or anything a future end condition names that this class
            // does not yet know — nobody's side won a hunt that was cut short rather than finished.
            return NONE;
        }
    }

    /** Which side a participant played, independent of who won. */
    public enum Side { RUNNER, HUNTER }

    /** One finished hunt, with everybody who was in it. */
    public record Entry(long id, Instant startedAt, Instant finishedAt, Duration elapsed,
                         String reason, Winner winner, Set<UUID> runners, Set<UUID> hunters) {
    }

    /** Every hunt ever recorded, boiled down to the numbers a leaderboard wants. */
    public record Summary(int total, int runnerWins, int hunterWins, int aborted,
                          Duration averageElapsed, Duration shortest, Duration longest) {

        public static final Summary EMPTY =
                new Summary(0, 0, 0, 0, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    /** One player's own record across every hunt they were in, on either side. */
    public record PlayerRecord(int played, int won) {

        public static final PlayerRecord EMPTY = new PlayerRecord(0, 0);
    }

    private final Database database;

    public HuntHistory(Database database) {
        this.database = database;
    }

    /**
     * Records one finished hunt. Must be called off the server's own thread — see
     * {@link Database#write}, which only reports the mistake rather than refusing it.
     *
     * @param startedAt the moment the hunt actually began (after any countdown), snapshotted by the
     *                  caller at {@code ManhuntService.onStart} — this class has no notion of "now"
     * @param runners   who was on the Runner side for the whole hunt — the roster is frozen for the
     *                  entire run, so the set captured at the start is the set that finished it
     * @param hunters   the same, for Hunters
     * @param outcome   what {@code ManhuntService.onFinished} was handed
     * @return whether the write was committed
     */
    public boolean record(Instant startedAt, Set<UUID> runners, Set<UUID> hunters, SpeedrunOutcome outcome) {
        if (startedAt == null || outcome == null || outcome.reason() == null || outcome.elapsed() == null) {
            return false;
        }
        Set<UUID> runnerSet = runners == null ? Set.of() : runners;
        Set<UUID> hunterSet = hunters == null ? Set.of() : hunters;
        Instant finishedAt = outcome.finishedAt() != null ? outcome.finishedAt() : startedAt.plus(outcome.elapsed());
        Winner winner = Winner.forReason(outcome.reason());

        return database.write(connection -> {
            long huntId = insertHunt(connection, startedAt, finishedAt, outcome, winner);
            if (huntId < 0) {
                return;
            }
            insertParticipants(connection, huntId, runnerSet, hunterSet);
        });
    }

    private static long insertHunt(Connection connection, Instant startedAt, Instant finishedAt,
                                    SpeedrunOutcome outcome, Winner winner) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO hunt (started_at, finished_at, elapsed_seconds, reason, winner) "
                        + "VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, startedAt.toEpochMilli());
            insert.setLong(2, finishedAt.toEpochMilli());
            insert.setLong(3, outcome.elapsed().getSeconds());
            insert.setString(4, outcome.reason());
            insert.setString(5, winner.name());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    private static void insertParticipants(Connection connection, long huntId, Set<UUID> runners,
                                            Set<UUID> hunters) throws SQLException {
        if (runners.isEmpty() && hunters.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO hunt_participant (hunt_id, player_id, side) VALUES (?, ?, ?)")) {
            for (UUID id : runners) {
                insert.setLong(1, huntId);
                insert.setString(2, id.toString());
                insert.setString(3, Side.RUNNER.name());
                insert.addBatch();
            }
            for (UUID id : hunters) {
                insert.setLong(1, huntId);
                insert.setString(2, id.toString());
                insert.setString(3, Side.HUNTER.name());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** The most recent hunts, newest first. */
    public List<Entry> recent(int limit) {
        int wanted = Math.max(0, limit);
        if (wanted == 0 || !database.isUsable()) {
            return List.of();
        }
        return database.read(connection -> {
            List<Entry> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, started_at, finished_at, elapsed_seconds, reason, winner FROM hunt "
                            + "ORDER BY id DESC LIMIT ?")) {
                select.setInt(1, wanted);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        found.add(readEntry(connection, rows));
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    /** Every hunt a player took part in, on either side, newest first. */
    public List<Entry> forPlayer(UUID player, int limit) {
        int wanted = Math.max(0, limit);
        if (player == null || wanted == 0 || !database.isUsable()) {
            return List.of();
        }
        return database.read(connection -> {
            List<Entry> found = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT h.id, h.started_at, h.finished_at, h.elapsed_seconds, h.reason, h.winner "
                            + "FROM hunt h JOIN hunt_participant p ON p.hunt_id = h.id "
                            + "WHERE p.player_id = ? ORDER BY h.id DESC LIMIT ?")) {
                select.setString(1, player.toString());
                select.setInt(2, wanted);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        found.add(readEntry(connection, rows));
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    private static Entry readEntry(Connection connection, ResultSet rows) throws SQLException {
        long id = rows.getLong("id");
        Set<UUID> runners = new LinkedHashSet<>();
        Set<UUID> hunters = new LinkedHashSet<>();
        try (PreparedStatement participants = connection.prepareStatement(
                "SELECT player_id, side FROM hunt_participant WHERE hunt_id = ?")) {
            participants.setLong(1, id);
            try (ResultSet participantRows = participants.executeQuery()) {
                while (participantRows.next()) {
                    UUID playerId = UUID.fromString(participantRows.getString("player_id"));
                    if (Side.RUNNER.name().equals(participantRows.getString("side"))) {
                        runners.add(playerId);
                    } else {
                        hunters.add(playerId);
                    }
                }
            }
        }
        return new Entry(id, Instant.ofEpochMilli(rows.getLong("started_at")),
                Instant.ofEpochMilli(rows.getLong("finished_at")),
                Duration.ofSeconds(rows.getLong("elapsed_seconds")),
                rows.getString("reason"), Winner.valueOf(rows.getString("winner")),
                Set.copyOf(runners), Set.copyOf(hunters));
    }

    /** Every hunt ever recorded, summarised. */
    public Summary summary() {
        if (!database.isUsable()) {
            return Summary.EMPTY;
        }
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT winner, elapsed_seconds FROM hunt");
                 ResultSet rows = select.executeQuery()) {
                int total = 0;
                int runnerWins = 0;
                int hunterWins = 0;
                int aborted = 0;
                long sum = 0;
                long shortest = Long.MAX_VALUE;
                long longest = Long.MIN_VALUE;
                while (rows.next()) {
                    total++;
                    long seconds = rows.getLong("elapsed_seconds");
                    sum += seconds;
                    shortest = Math.min(shortest, seconds);
                    longest = Math.max(longest, seconds);
                    switch (Winner.valueOf(rows.getString("winner"))) {
                        case RUNNERS -> runnerWins++;
                        case HUNTERS -> hunterWins++;
                        case NONE -> aborted++;
                    }
                }
                if (total == 0) {
                    return Summary.EMPTY;
                }
                return new Summary(total, runnerWins, hunterWins, aborted,
                        Duration.ofSeconds(sum / total), Duration.ofSeconds(shortest),
                        Duration.ofSeconds(longest));
            }
        }).orElse(Summary.EMPTY);
    }

    /** How many hunts a player has played, and how many of those their own side won. */
    public PlayerRecord forPlayerSummary(UUID player) {
        if (player == null || !database.isUsable()) {
            return PlayerRecord.EMPTY;
        }
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT h.winner, p.side FROM hunt h "
                            + "JOIN hunt_participant p ON p.hunt_id = h.id WHERE p.player_id = ?")) {
                select.setString(1, player.toString());
                try (ResultSet rows = select.executeQuery()) {
                    int played = 0;
                    int won = 0;
                    while (rows.next()) {
                        played++;
                        Winner winner = Winner.valueOf(rows.getString("winner"));
                        Side side = Side.valueOf(rows.getString("side"));
                        boolean sideWon = (winner == Winner.RUNNERS && side == Side.RUNNER)
                                || (winner == Winner.HUNTERS && side == Side.HUNTER);
                        if (sideWon) {
                            won++;
                        }
                    }
                    return new PlayerRecord(played, won);
                }
            }
        }).orElse(PlayerRecord.EMPTY);
    }
}
