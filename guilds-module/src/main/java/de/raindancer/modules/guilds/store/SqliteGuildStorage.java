package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.HomeSpot;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.JoinMode;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.model.Relation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * guilds.db. A guild is written whole — its row, its members, ranks and relations deleted and inserted again —
 * because a guild is small and a partial write is the one thing that must never happen to it.
 */
public final class SqliteGuildStorage implements GuildStorage {

    private final Database database;
    private final Consumer<String> problems;

    /** @param problems told about a row that could not be read and was left out */
    public SqliteGuildStorage(Database database, Consumer<String> problems) {
        this.database = database;
        this.problems = problems;
    }

    @Override
    public boolean isUsable() {
        return database.isUsable();
    }

    @Override
    public Optional<List<Guild>> loadAll() {
        if (!database.isUsable()) {
            return Optional.empty();
        }
        return database.read(connection -> {
            Map<String, List<Rank>> ranks = new HashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM rank")) {
                while (rows.next()) {
                    ranks.computeIfAbsent(rows.getString("guild"), key -> new ArrayList<>()).add(new Rank(
                            rows.getString("id"), rows.getString("name"), rows.getInt("weight"),
                            permissions(rows.getString("permissions")), rows.getLong("daily_limit")));
                }
            }
            Map<String, Map<UUID, Member>> members = new HashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM member")) {
                while (rows.next()) {
                    try {
                        UUID player = UUID.fromString(rows.getString("player"));
                        members.computeIfAbsent(rows.getString("guild"), key -> new LinkedHashMap<>()).put(player,
                                new Member(player, rows.getString("rank"), rows.getLong("joined"),
                                        rows.getLong("deposited"), rows.getLong("withdrawn"), rows.getLong("xp"),
                                        rows.getLong("taken_today"), rows.getLong("taken_on")));
                    } catch (IllegalArgumentException unreadable) {
                        problems.accept("a member row with an unreadable player id was left out: " + rows.getString("player"));
                    }
                }
            }
            Map<String, Map<String, Relation>> relations = new HashMap<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM relation")) {
                while (rows.next()) {
                    try {
                        relations.computeIfAbsent(rows.getString("guild"), key -> new LinkedHashMap<>())
                                .put(rows.getString("other"), Relation.valueOf(rows.getString("kind")));
                    } catch (IllegalArgumentException unreadable) {
                        problems.accept("a relation of " + rows.getString("guild") + " could not be read");
                    }
                }
            }
            List<Guild> guilds = new ArrayList<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM guild")) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    try {
                        guilds.add(guild(rows, members.getOrDefault(id, Map.of()),
                                ranks.getOrDefault(id, List.of()), relations.getOrDefault(id, Map.of())));
                    } catch (RuntimeException unreadable) {
                        problems.accept("the guild " + id + " could not be read and was left out: " + unreadable.getMessage());
                    }
                }
            }
            return guilds;
        });
    }

    private static Guild guild(ResultSet rows, Map<UUID, Member> members, List<Rank> ranks,
                               Map<String, Relation> relations) throws SQLException {
        String world = rows.getString("home_world");
        Optional<HomeSpot> home = world == null ? Optional.empty() : Optional.of(new HomeSpot(world,
                rows.getDouble("home_x"), rows.getDouble("home_y"), rows.getDouble("home_z"),
                rows.getFloat("home_yaw"), rows.getFloat("home_pitch")));
        List<Rank> ladder = ranks.isEmpty() ? Rank.defaults(0, 0) : ranks;
        if (ladder.stream().noneMatch(Rank::isLeader)) {
            ladder = new ArrayList<>(ladder);
            ladder.add(new Rank(Rank.LEADER, "Leader", Rank.LEADER_WEIGHT, null, -1));
        }
        UUID leader = UUID.fromString(rows.getString("leader"));
        Map<UUID, Member> roster = new LinkedHashMap<>(members);
        // The leader's own row is what makes them the leader's rank; one lost in a crash is put back rather
        // than the guild refusing to load.
        roster.computeIfAbsent(leader, who -> Member.joining(who, Rank.LEADER, 0));
        roster.put(leader, roster.get(leader).withRank(Rank.LEADER));
        return new Guild(new TeamId(rows.getString("id")), rows.getString("name"), rows.getString("tag"),
                TeamColour.named(rows.getString("colour")).orElse(TeamColour.values()[0]),
                TeamEmblem.named(rows.getString("emblem")).orElse(TeamEmblem.NONE),
                rows.getString("motd"), rows.getLong("founded"), leader,
                JoinMode.named(rows.getString("join_mode")).orElse(JoinMode.INVITE),
                rows.getLong("xp"), rows.getLong("bank"), home,
                rows.getInt("friendly_fire") != 0, rows.getInt("ally_friendly_fire") != 0,
                roster, ladder, relations);
    }

    private static Set<GuildPermission> permissions(String written) {
        Set<GuildPermission> found = EnumSet.noneOf(GuildPermission.class);
        if (written != null) {
            for (String each : written.split(",")) {
                GuildPermission.named(each).ifPresent(found::add);
            }
        }
        return found;
    }

    @Override
    public List<Invitation> loadInvitations() {
        return database.read(connection -> {
            List<Invitation> found = new ArrayList<>();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM invitation")) {
                while (rows.next()) {
                    try {
                        found.add(new Invitation(Invitation.Kind.valueOf(rows.getString("kind")), rows.getString("guild"),
                                UUID.fromString(rows.getString("subject")), UUID.fromString(rows.getString("asked_by")),
                                rows.getString("other"), rows.getLong("expires")));
                    } catch (IllegalArgumentException unreadable) {
                        problems.accept("an invitation could not be read and was dropped");
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    @Override
    public boolean write(Collection<Guild> changed, Set<String> deleted, Collection<Invitation> invitations,
                         List<GuildEvent> events) {
        return database.write(connection -> {
            for (String id : deleted) {
                forget(connection, id);
            }
            for (Guild guild : changed) {
                forget(connection, guild.id().value());
                insert(connection, guild);
            }
            if (invitations != null) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM invitation");
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT OR REPLACE INTO invitation (kind, guild, subject, asked_by, other, expires) VALUES (?,?,?,?,?,?)")) {
                    for (Invitation each : invitations) {
                        insert.setString(1, each.kind().name());
                        insert.setString(2, each.guild());
                        insert.setString(3, each.subject().toString());
                        insert.setString(4, each.by().toString());
                        insert.setString(5, each.other());
                        insert.setLong(6, each.expires());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO event (guild, at, kind, actor, target, amount, detail) VALUES (?,?,?,?,?,?,?)")) {
                for (GuildEvent event : events) {
                    insert.setString(1, event.guild());
                    insert.setLong(2, event.at());
                    insert.setString(3, event.kind().name());
                    uuid(insert, 4, event.actor());
                    uuid(insert, 5, event.target());
                    insert.setLong(6, event.amount());
                    insert.setString(7, event.detail());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    private static void uuid(PreparedStatement statement, int index, Optional<UUID> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get().toString());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static void forget(Connection connection, String id) throws SQLException {
        for (String table : List.of("guild WHERE id", "member WHERE guild", "rank WHERE guild", "relation WHERE guild")) {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " = ?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
        }
    }

    private static void insert(Connection connection, Guild guild) throws SQLException {
        String id = guild.id().value();
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO guild (id, name, tag, colour, emblem, motd, "
                + "founded, leader, join_mode, xp, bank, home_world, home_x, home_y, home_z, home_yaw, home_pitch, "
                + "friendly_fire, ally_friendly_fire) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            row.setString(1, id);
            row.setString(2, guild.name());
            row.setString(3, guild.tag());
            row.setString(4, guild.colour().name());
            row.setString(5, guild.emblem().name());
            row.setString(6, guild.motd());
            row.setLong(7, guild.founded());
            row.setString(8, guild.leader().toString());
            row.setString(9, guild.joinMode().name());
            row.setLong(10, guild.xp());
            row.setLong(11, guild.bank());
            if (guild.home().isPresent()) {
                HomeSpot home = guild.home().get();
                row.setString(12, home.world());
                row.setDouble(13, home.x());
                row.setDouble(14, home.y());
                row.setDouble(15, home.z());
                row.setFloat(16, home.yaw());
                row.setFloat(17, home.pitch());
            } else {
                for (int column = 12; column <= 17; column++) {
                    row.setNull(column, column == 12 ? Types.VARCHAR : Types.REAL);
                }
            }
            row.setInt(18, guild.friendlyFire() ? 1 : 0);
            row.setInt(19, guild.allyFriendlyFire() ? 1 : 0);
            row.executeUpdate();
        }
        try (PreparedStatement row = connection.prepareStatement("INSERT OR REPLACE INTO member (player, guild, rank, joined, "
                + "deposited, withdrawn, xp, taken_today, taken_on) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (Member member : guild.members().values()) {
                row.setString(1, member.player().toString());
                row.setString(2, id);
                row.setString(3, member.rank());
                row.setLong(4, member.joined());
                row.setLong(5, member.deposited());
                row.setLong(6, member.withdrawn());
                row.setLong(7, member.xp());
                row.setLong(8, member.takenToday());
                row.setLong(9, member.takenOn());
                row.addBatch();
            }
            row.executeBatch();
        }
        try (PreparedStatement row = connection.prepareStatement(
                "INSERT INTO rank (guild, id, name, weight, permissions, daily_limit) VALUES (?,?,?,?,?,?)")) {
            for (Rank rank : guild.ranks()) {
                row.setString(1, id);
                row.setString(2, rank.id());
                row.setString(3, rank.name());
                row.setInt(4, rank.weight());
                row.setString(5, rank.permissions().stream().map(GuildPermission::key).collect(Collectors.joining(",")));
                row.setLong(6, rank.dailyLimit());
                row.addBatch();
            }
            row.executeBatch();
        }
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO relation (guild, other, kind) VALUES (?,?,?)")) {
            for (Map.Entry<String, Relation> relation : guild.relations().entrySet()) {
                row.setString(1, id);
                row.setString(2, relation.getKey());
                row.setString(3, relation.getValue().name());
                row.addBatch();
            }
            row.executeBatch();
        }
    }

    @Override
    public List<GuildEvent> events(String guild, Set<GuildEvent.Kind> kinds, int limit, int offset) {
        String filter = kinds == null || kinds.isEmpty() ? ""
                : " AND kind IN (" + kinds.stream().map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(",")) + ")";
        return database.read(connection -> {
            List<GuildEvent> found = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM event WHERE guild = ?" + filter + " ORDER BY at DESC, id DESC LIMIT ? OFFSET ?")) {
                query.setString(1, guild);
                query.setInt(2, Math.max(1, limit));
                query.setInt(3, Math.max(0, offset));
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        try {
                            found.add(new GuildEvent(rows.getString("guild"), rows.getLong("at"),
                                    GuildEvent.Kind.valueOf(rows.getString("kind")), uuid(rows.getString("actor")),
                                    uuid(rows.getString("target")), rows.getLong("amount"), rows.getString("detail")));
                        } catch (IllegalArgumentException unknownKind) {
                            // A kind from a newer version, read by an older one: left out of the page.
                        }
                    }
                }
            }
            return found;
        }).orElse(List.of());
    }

    private static Optional<UUID> uuid(String written) {
        try {
            return written == null ? Optional.empty() : Optional.of(UUID.fromString(written));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }
}
