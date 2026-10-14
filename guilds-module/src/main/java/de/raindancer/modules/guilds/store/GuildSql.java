package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.ui.text.NameStyle;
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
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The guild tables, for the network. A guild is never written whole: a change is the difference between the
 * guild as it was read and as it should be, written as the smallest statements that make it — a field set, a
 * member added or removed, {@code bank = bank + delta}, {@code xp = xp + delta}. What must hold across servers is
 * decided by the database: one row per player in {@code member}, one owner per word in {@code guild_word}, a bank
 * that cannot go below zero.
 */
public final class GuildSql {

    /** What a write found. {@link #CONFLICT} is a guard that did not hold: read the guild again and decide again. */
    public enum Outcome { DONE, CONFLICT, NAME_TAKEN, TAG_TAKEN, ALREADY_IN_GUILD, NO_SUCH_GUILD, NOT_EMPTY, FAILED }

    /** One guild as it was read and as it should be. */
    public record Change(Guild before, Guild after) {
    }

    /** A guild taken away, and the guilds that had a relation to it, whose rows changed with it. */
    public record Disbanded(Outcome outcome, List<String> touched) {
    }

    private final Database database;
    private final Consumer<String> problems;

    public GuildSql(Database database, Consumer<String> problems) {
        this.database = database;
        this.problems = problems;
    }

    public boolean isUsable() {
        return database.isUsable();
    }

    /** What the namespace calls a name, a tag or an id: stripped and lower-cased. */
    static String word(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }

    /** How a typed word reads as an id. */
    static String idWord(String text) {
        return word(text).replaceAll("\\s+", "-");
    }

    // ------------------------------------------------------------------ reading

    /** Every guild, or empty when the database cannot be read — which is not the same as "no guilds". */
    public Optional<List<Guild>> loadAll() {
        if (!database.isUsable()) {
            return Optional.empty();
        }
        return database.read(connection -> {
            Map<String, List<Rank>> ranks = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM rank")) {
                while (rows.next()) {
                    ranks.computeIfAbsent(rows.getString("guild"), key -> new ArrayList<>()).add(rank(rows));
                }
            }
            Map<String, Map<UUID, Member>> members = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM member")) {
                while (rows.next()) {
                    String of = rows.getString("guild");
                    Optional<Member> member = member(rows);
                    if (member.isPresent()) {
                        members.computeIfAbsent(of, key -> new LinkedHashMap<>()).put(member.get().player(), member.get());
                    }
                }
            }
            Map<String, Map<String, Relation>> relations = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM relation")) {
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
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM guild")) {
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

    /** One guild: empty when the database could not be asked, an empty one inside when there is no such guild. */
    public Optional<Optional<Guild>> load(String id) {
        if (!database.isUsable()) {
            return Optional.empty();
        }
        return database.read(connection -> load(connection, id));
    }

    private Optional<Guild> load(Connection connection, String id) throws SQLException {
        List<Rank> ranks = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM rank WHERE guild = ?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ranks.add(rank(rows));
                }
            }
        }
        Map<UUID, Member> members = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM member WHERE guild = ?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    member(rows).ifPresent(member -> members.put(member.player(), member));
                }
            }
        }
        Map<String, Relation> relations = new LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM relation WHERE guild = ?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    try {
                        relations.put(rows.getString("other"), Relation.valueOf(rows.getString("kind")));
                    } catch (IllegalArgumentException unreadable) {
                        problems.accept("a relation of " + id + " could not be read");
                    }
                }
            }
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM guild WHERE id = ?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(guild(rows, members, ranks, relations));
                } catch (RuntimeException unreadable) {
                    problems.accept("the guild " + id + " could not be read and was left out: " + unreadable.getMessage());
                    return Optional.empty();
                }
            }
        }
    }

    private static Rank rank(ResultSet rows) throws SQLException {
        return new Rank(rows.getString("id"), rows.getString("name"), rows.getInt("weight"),
                permissions(rows.getString("permissions")), rows.getLong("daily_limit"));
    }

    private Optional<Member> member(ResultSet rows) throws SQLException {
        try {
            return Optional.of(new Member(UUID.fromString(rows.getString("player")), rows.getString("rank"),
                    rows.getLong("joined"), rows.getLong("deposited"), rows.getLong("withdrawn"), rows.getLong("xp"),
                    rows.getLong("taken_today"), rows.getLong("taken_on"), rows.getLong("seen")));
        } catch (IllegalArgumentException unreadable) {
            problems.accept("a member row with an unreadable player id was left out: " + rows.getString("player"));
            return Optional.empty();
        }
    }

    private static Guild guild(ResultSet rows, Map<UUID, Member> members, List<Rank> ranks,
                               Map<String, Relation> relations) throws SQLException {
        String world = rows.getString("home_world");
        Optional<HomeSpot> home = world == null ? Optional.empty() : Optional.of(new HomeSpot(world,
                rows.getDouble("home_x"), rows.getDouble("home_y"), rows.getDouble("home_z"),
                rows.getFloat("home_yaw"), rows.getFloat("home_pitch"), rows.getString("home_server")));
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
                roster, ladder, relations,
                NameStyle.parse(rows.getString("tag_style")), NameStyle.parse(rows.getString("name_style")));
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

    // ------------------------------------------------------------------ founding

    /** Writes a new guild, its words and its members — or says which of them is somebody else's. */
    public Outcome create(Guild guild) {
        Outcome[] outcome = {Outcome.DONE};
        boolean ran = database.write(connection -> {
            Outcome refused = refusal(connection, guild.id().value(), guild.name(), guild.tag(), true);
            if (refused != null) {
                outcome[0] = refused;
                return;
            }
            for (UUID player : guild.members().keySet()) {
                if (guildOf(connection, player) != null) {
                    outcome[0] = Outcome.ALREADY_IN_GUILD;
                    return;
                }
            }
            insert(connection, guild);
            claim(connection, guild);
        });
        if (ran) {
            return outcome[0];
        }
        // Two servers founding at once: the database let one through and refused the other at its constraint,
        // which rolled the write back. What it ran into is told as what it is.
        return database.read(connection -> {
            Outcome refused = refusal(connection, guild.id().value(), guild.name(), guild.tag(), true);
            for (UUID player : guild.members().keySet()) {
                if (refused == null && guildOf(connection, player) != null) {
                    refused = Outcome.ALREADY_IN_GUILD;
                }
            }
            return refused == null ? Outcome.FAILED : refused;
        }).orElse(Outcome.FAILED);
    }

    /** Which of name, tag and id is already some other guild's; null when none is. */
    private static Outcome refusal(Connection connection, String id, String name, String tag, boolean founding)
            throws SQLException {
        if (founding && owner(connection, id) != null) {
            return Outcome.NAME_TAKEN;
        }
        for (String word : new LinkedHashSet<>(List.of(word(name), idWord(name)))) {
            String owner = owner(connection, word);
            if (owner != null && !owner.equals(id)) {
                return Outcome.NAME_TAKEN;
            }
        }
        for (String word : new LinkedHashSet<>(List.of(word(tag), idWord(tag)))) {
            String owner = owner(connection, word);
            if (owner != null && !owner.equals(id)) {
                return Outcome.TAG_TAKEN;
            }
        }
        return null;
    }

    private static String owner(Connection connection, String word) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT guild FROM guild_word WHERE word = ?")) {
            query.setString(1, word);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static String guildOf(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT guild FROM member WHERE player = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** The words a guild holds: replaced as they are now. */
    private static void claim(Connection connection, Guild guild) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild_word WHERE guild = ?")) {
            delete.setString(1, guild.id().value());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO guild_word (word, guild) VALUES (?, ?)")) {
            for (String word : new LinkedHashSet<>(List.of(word(guild.name()), word(guild.tag()), guild.id().value()))) {
                insert.setString(1, word);
                insert.setString(2, guild.id().value());
                insert.executeUpdate();
            }
        }
    }

    /**
     * Makes sure every guild's words are held — for guilds from before the namespace was a table. A word somebody
     * else holds is left alone.
     */
    public boolean claimWords(Collection<Guild> guilds) {
        return database.write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO guild_word (word, guild) VALUES (?, ?) ON CONFLICT (word) DO NOTHING")) {
                for (Guild guild : guilds) {
                    for (String word : new LinkedHashSet<>(List.of(word(guild.name()), word(guild.tag()), guild.id().value()))) {
                        insert.setString(1, word);
                        insert.setString(2, guild.id().value());
                        insert.executeUpdate();
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ changing

    /** Writes the changes as one transaction: all of them, or none. */
    public Outcome write(List<Change> changes) {
        Outcome[] outcome = {Outcome.DONE};
        boolean ran = database.write(connection -> {
            for (Change change : changes) {
                Outcome result = apply(connection, change);
                if (result != Outcome.DONE) {
                    outcome[0] = result;
                    // Nothing before this was written: every check is made before its writes, and a refused
                    // change leaves the earlier ones to be undone by throwing.
                    if (changes.indexOf(change) > 0) {
                        throw new SQLException("a change was refused after another was written: " + result);
                    }
                    return;
                }
            }
        });
        if (ran || outcome[0] != Outcome.DONE) {
            return outcome[0];
        }
        // A constraint another server's write had just satisfied refused this one: told as what it is.
        return database.read(connection -> {
            for (Change change : changes) {
                Outcome refused = precheck(connection, change);
                if (refused != null) {
                    return refused;
                }
            }
            return Outcome.FAILED;
        }).orElse(Outcome.FAILED);
    }

    /** What would refuse this change, from what the database holds now; null when nothing does. */
    private static Outcome precheck(Connection connection, Change change) throws SQLException {
        Guild before = change.before();
        Guild after = change.after();
        String id = before.id().value();
        if (!after.name().equalsIgnoreCase(before.name()) || !after.tag().equalsIgnoreCase(before.tag())) {
            Outcome refused = refusal(connection, id, after.name(), after.tag(), false);
            if (refused != null) {
                return refused;
            }
        }
        for (UUID player : after.members().keySet()) {
            if (!before.members().containsKey(player)) {
                String there = guildOf(connection, player);
                if (there != null && !there.equals(id)) {
                    return Outcome.ALREADY_IN_GUILD;
                }
            }
        }
        return null;
    }

    private Outcome apply(Connection connection, Change change) throws SQLException {
        Guild before = change.before();
        Guild after = change.after();
        String id = before.id().value();
        long bank;
        try (PreparedStatement query = connection.prepareStatement("SELECT bank FROM guild WHERE id = ?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Outcome.NO_SUCH_GUILD;
                }
                bank = rows.getLong(1);
            }
        }
        boolean renamed = !after.name().equalsIgnoreCase(before.name()) || !after.tag().equalsIgnoreCase(before.tag());
        Outcome refused = precheck(connection, change);
        if (refused != null) {
            return refused;
        }
        long bankChange = after.bank() - before.bank();
        if (bankChange < 0 && bank + bankChange < 0) {
            return Outcome.CONFLICT;
        }
        scalars(connection, before, after);
        if (renamed) {
            claim(connection, after);
        }
        if (after.xp() != before.xp()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE guild SET xp = CASE WHEN xp + ? < 0 THEN 0 ELSE xp + ? END WHERE id = ?")) {
                update.setLong(1, after.xp() - before.xp());
                update.setLong(2, after.xp() - before.xp());
                update.setString(3, id);
                update.executeUpdate();
            }
        }
        if (bankChange != 0) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE guild SET bank = bank + ? WHERE id = ? AND bank + ? >= 0")) {
                update.setLong(1, bankChange);
                update.setString(2, id);
                update.setLong(3, bankChange);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("the bank of " + id + " changed while it was being written");
                }
            }
        }
        members(connection, id, before.members(), after.members());
        ranks(connection, id, before.ranks(), after.ranks());
        relations(connection, id, before.relations(), after.relations());
        return Outcome.DONE;
    }

    private static void scalars(Connection connection, Guild before, Guild after) throws SQLException {
        Map<String, Object> set = new LinkedHashMap<>();
        if (!after.name().equals(before.name())) {
            set.put("name", after.name());
        }
        if (!after.tag().equals(before.tag())) {
            set.put("tag", after.tag());
        }
        if (after.colour() != before.colour()) {
            set.put("colour", after.colour().name());
        }
        if (after.emblem() != before.emblem()) {
            set.put("emblem", after.emblem().name());
        }
        if (!after.motd().equals(before.motd())) {
            set.put("motd", after.motd());
        }
        if (!after.leader().equals(before.leader())) {
            set.put("leader", after.leader().toString());
        }
        if (after.joinMode() != before.joinMode()) {
            set.put("join_mode", after.joinMode().name());
        }
        if (after.friendlyFire() != before.friendlyFire()) {
            set.put("friendly_fire", after.friendlyFire() ? 1 : 0);
        }
        if (after.allyFriendlyFire() != before.allyFriendlyFire()) {
            set.put("ally_friendly_fire", after.allyFriendlyFire() ? 1 : 0);
        }
        if (!after.tagStyle().encode().equals(before.tagStyle().encode())) {
            set.put("tag_style", after.tagStyle().encode());
        }
        if (!after.nameStyle().encode().equals(before.nameStyle().encode())) {
            set.put("name_style", after.nameStyle().encode());
        }
        boolean homeChanged = !after.home().equals(before.home());
        if (set.isEmpty() && !homeChanged) {
            return;
        }
        StringBuilder sql = new StringBuilder("UPDATE guild SET ");
        List<Object> values = new ArrayList<>();
        set.forEach((column, value) -> {
            sql.append(values.isEmpty() ? "" : ", ").append(column).append(" = ?");
            values.add(value);
        });
        if (homeChanged) {
            sql.append(values.isEmpty() ? "" : ", ")
                    .append("home_world = ?, home_x = ?, home_y = ?, home_z = ?, home_yaw = ?, home_pitch = ?, home_server = ?");
        }
        sql.append(" WHERE id = ?");
        try (PreparedStatement update = connection.prepareStatement(sql.toString())) {
            int at = 1;
            for (Object value : values) {
                if (value instanceof Integer number) {
                    update.setInt(at++, number);
                } else {
                    update.setString(at++, String.valueOf(value));
                }
            }
            if (homeChanged) {
                at = home(update, at, after.home());
            }
            update.setString(at, before.id().value());
            update.executeUpdate();
        }
    }

    /** Binds a home's seven columns from {@code at}; an absent home is all nulls, typed. */
    private static int home(PreparedStatement statement, int at, Optional<HomeSpot> home) throws SQLException {
        if (home.isPresent()) {
            HomeSpot spot = home.get();
            statement.setString(at++, spot.world());
            statement.setDouble(at++, spot.x());
            statement.setDouble(at++, spot.y());
            statement.setDouble(at++, spot.z());
            statement.setFloat(at++, spot.yaw());
            statement.setFloat(at++, spot.pitch());
            statement.setString(at++, spot.server());
        } else {
            statement.setNull(at++, Types.VARCHAR);
            for (int column = 0; column < 5; column++) {
                statement.setNull(at++, Types.DOUBLE);
            }
            statement.setString(at++, "");
        }
        return at;
    }

    private static void members(Connection connection, String id, Map<UUID, Member> before, Map<UUID, Member> after)
            throws SQLException {
        for (Member member : after.values()) {
            Member was = before.get(member.player());
            if (was == null) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO member (player, guild, rank, joined, deposited, withdrawn, xp, taken_today, taken_on, seen)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    insert.setString(1, member.player().toString());
                    insert.setString(2, id);
                    insert.setString(3, member.rank());
                    insert.setLong(4, member.joined());
                    insert.setLong(5, member.deposited());
                    insert.setLong(6, member.withdrawn());
                    insert.setLong(7, member.xp());
                    insert.setLong(8, member.takenToday());
                    insert.setLong(9, member.takenOn());
                    insert.setLong(10, member.seen());
                    insert.executeUpdate();
                }
            } else if (!was.withSeen(member.seen()).equals(member.withSeen(was.seen()))) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE member SET rank = ?, deposited = deposited + ?, withdrawn = withdrawn + ?, xp = xp + ?,
                            taken_today = ?, taken_on = ? WHERE player = ? AND guild = ?""")) {
                    update.setString(1, member.rank());
                    update.setLong(2, member.deposited() - was.deposited());
                    update.setLong(3, member.withdrawn() - was.withdrawn());
                    update.setLong(4, member.xp() - was.xp());
                    update.setLong(5, member.takenToday());
                    update.setLong(6, member.takenOn());
                    update.setString(7, member.player().toString());
                    update.setString(8, id);
                    update.executeUpdate();
                }
            }
        }
        for (UUID gone : before.keySet()) {
            if (!after.containsKey(gone)) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM member WHERE player = ? AND guild = ?")) {
                    delete.setString(1, gone.toString());
                    delete.setString(2, id);
                    delete.executeUpdate();
                }
            }
        }
    }

    private static void ranks(Connection connection, String id, List<Rank> before, List<Rank> after) throws SQLException {
        Map<String, Rank> was = before.stream().collect(Collectors.toMap(Rank::id, rank -> rank));
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO rank (guild, id, name, weight, permissions, daily_limit) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (guild, id) DO UPDATE SET name = excluded.name, weight = excluded.weight,
                    permissions = excluded.permissions, daily_limit = excluded.daily_limit""")) {
            for (Rank rank : after) {
                if (rank.equals(was.get(rank.id()))) {
                    continue;
                }
                upsert.setString(1, id);
                upsert.setString(2, rank.id());
                upsert.setString(3, rank.name());
                upsert.setInt(4, rank.weight());
                upsert.setString(5, rank.permissions().stream().map(GuildPermission::key).collect(Collectors.joining(",")));
                upsert.setLong(6, rank.dailyLimit());
                upsert.executeUpdate();
            }
        }
        Set<String> kept = after.stream().map(Rank::id).collect(Collectors.toSet());
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM rank WHERE guild = ? AND id = ?")) {
            for (String gone : was.keySet()) {
                if (!kept.contains(gone)) {
                    delete.setString(1, id);
                    delete.setString(2, gone);
                    delete.executeUpdate();
                }
            }
        }
    }

    private static void relations(Connection connection, String id, Map<String, Relation> before, Map<String, Relation> after)
            throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO relation (guild, other, kind) VALUES (?, ?, ?)
                ON CONFLICT (guild, other) DO UPDATE SET kind = excluded.kind""")) {
            for (Map.Entry<String, Relation> relation : after.entrySet()) {
                if (relation.getValue() == before.get(relation.getKey())) {
                    continue;
                }
                upsert.setString(1, id);
                upsert.setString(2, relation.getKey());
                upsert.setString(3, relation.getValue().name());
                upsert.executeUpdate();
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM relation WHERE guild = ? AND other = ?")) {
            for (String gone : before.keySet()) {
                if (!after.containsKey(gone)) {
                    delete.setString(1, id);
                    delete.setString(2, gone);
                    delete.executeUpdate();
                }
            }
        }
    }

    private static void insert(Connection connection, Guild guild) throws SQLException {
        String id = guild.id().value();
        try (PreparedStatement row = connection.prepareStatement("INSERT INTO guild (id, name, tag, colour, emblem, motd, "
                + "founded, leader, join_mode, xp, bank, home_world, home_x, home_y, home_z, home_yaw, home_pitch, "
                + "home_server, friendly_fire, ally_friendly_fire, tag_style, name_style) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
            home(row, 12, guild.home());
            row.setInt(19, guild.friendlyFire() ? 1 : 0);
            row.setInt(20, guild.allyFriendlyFire() ? 1 : 0);
            row.setString(21, guild.tagStyle().encode());
            row.setString(22, guild.nameStyle().encode());
            row.executeUpdate();
        }
        members(connection, id, Map.of(), guild.members());
        ranks(connection, id, List.of(), guild.ranks());
        relations(connection, id, Map.of(), guild.relations());
    }

    // ------------------------------------------------------------------ disbanding

    /**
     * Takes a guild and everything of its own away — if its bank is empty and its vault holds nothing, checked in
     * the same write: a deposit another server made since cannot be lost with it.
     *
     * @param vault the key of the guild's vault rows
     */
    public Disbanded disband(String id, String vault) {
        Outcome[] outcome = {Outcome.DONE};
        List<String> touched = new ArrayList<>();
        boolean ran = database.write(connection -> {
            // The guild's row first: a deposit into its vault takes the same row, so one waits for the other.
            try (PreparedStatement lock = connection.prepareStatement("UPDATE guild SET xp = xp WHERE id = ?")) {
                lock.setString(1, id);
                if (lock.executeUpdate() != 1) {
                    outcome[0] = Outcome.NO_SUCH_GUILD;
                    return;
                }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT bank FROM guild WHERE id = ?")) {
                query.setString(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        outcome[0] = Outcome.NO_SUCH_GUILD;
                        return;
                    }
                    if (rows.getLong(1) > 0) {
                        outcome[0] = Outcome.NOT_EMPTY;
                        return;
                    }
                }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM guild_vault WHERE vault = ?")) {
                query.setString(1, vault);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next() && rows.getLong(1) > 0) {
                        outcome[0] = Outcome.NOT_EMPTY;
                        return;
                    }
                }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT guild FROM relation WHERE other = ?")) {
                query.setString(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        touched.add(rows.getString(1));
                    }
                }
            }
            for (String sql : List.of("DELETE FROM relation WHERE guild = ? OR other = ?",
                    "DELETE FROM invitation WHERE guild = ? OR other = ?")) {
                try (PreparedStatement delete = connection.prepareStatement(sql)) {
                    delete.setString(1, id);
                    delete.setString(2, id);
                    delete.executeUpdate();
                }
            }
            for (String table : List.of("member", "rank", "guild_word", "guild_xp_source", "contract", "guild_standing",
                    "guild_perk", "week_xp")) {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE guild = ?")) {
                    delete.setString(1, id);
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE crown SET guild = '' WHERE guild = ?")) {
                update.setString(1, id);
                update.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM guild WHERE id = ?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
        });
        return new Disbanded(ran ? outcome[0] : Outcome.FAILED, touched);
    }

    // ------------------------------------------------------------------ experience

    /**
     * Adds experience: each guild's {@code xp = xp + delta}, each member's the same.
     *
     * @return each guild's experience afterwards, for the guilds that exist; empty when it could not be written
     */
    public Optional<Map<String, Long>> addXp(Map<String, Long> guilds, Map<UUID, Map.Entry<String, Long>> members) {
        Map<String, Long> after = new LinkedHashMap<>();
        boolean ran = database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE guild SET xp = xp + ? WHERE id = ? RETURNING xp")) {
                for (Map.Entry<String, Long> each : guilds.entrySet()) {
                    update.setLong(1, each.getValue());
                    update.setString(2, each.getKey());
                    try (ResultSet rows = update.executeQuery()) {
                        if (rows.next()) {
                            after.put(each.getKey(), rows.getLong(1));
                        }
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE member SET xp = xp + ? WHERE player = ? AND guild = ?")) {
                for (Map.Entry<UUID, Map.Entry<String, Long>> each : members.entrySet()) {
                    update.setLong(1, each.getValue().getValue());
                    update.setString(2, each.getKey().toString());
                    update.setString(3, each.getValue().getKey());
                    update.executeUpdate();
                }
            }
        });
        return ran ? Optional.of(after) : Optional.empty();
    }

    /** When members were last on: only ever moves forward. */
    public boolean markSeen(Map<UUID, Long> seen) {
        return database.write(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE member SET seen = CASE WHEN seen < ? THEN ? ELSE seen END WHERE player = ?")) {
                for (Map.Entry<UUID, Long> each : seen.entrySet()) {
                    update.setLong(1, each.getValue());
                    update.setLong(2, each.getValue());
                    update.setString(3, each.getKey().toString());
                    update.executeUpdate();
                }
            }
        });
    }

    // ------------------------------------------------------------------ questions

    public Optional<List<Invitation>> loadInvitations() {
        return database.read(connection -> {
            List<Invitation> found = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM invitation")) {
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
        });
    }

    /** Puts a question, replacing the same one asked before. @return whether it was written */
    public boolean ask(Invitation invitation) {
        return database.write(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO invitation (kind, guild, subject, asked_by, other, expires) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (kind, guild, subject, other) DO UPDATE SET asked_by = excluded.asked_by,
                        expires = excluded.expires""")) {
                upsert.setString(1, invitation.kind().name());
                upsert.setString(2, invitation.guild());
                upsert.setString(3, invitation.subject().toString());
                upsert.setString(4, invitation.by().toString());
                upsert.setString(5, invitation.other());
                upsert.setLong(6, invitation.expires());
                upsert.executeUpdate();
            }
        });
    }

    /** Takes a question away, answering it: of the servers answering it, one gets it. */
    public Optional<Optional<Invitation>> take(Invitation.Kind kind, String guild, UUID subject, String other) {
        Invitation[] taken = new Invitation[1];
        boolean ran = database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM invitation WHERE kind = ? AND guild = ? AND subject = ? AND other = ?
                    RETURNING asked_by, expires""")) {
                delete.setString(1, kind.name());
                delete.setString(2, guild);
                delete.setString(3, subject.toString());
                delete.setString(4, other);
                try (ResultSet rows = delete.executeQuery()) {
                    if (rows.next()) {
                        taken[0] = new Invitation(kind, guild, subject, UUID.fromString(rows.getString(1)), other,
                                rows.getLong(2));
                    }
                }
            }
        });
        return ran ? Optional.of(Optional.ofNullable(taken[0])) : Optional.empty();
    }

    /** Drops every question that has run out. */
    public boolean expire(long now) {
        return database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM invitation WHERE expires <= ?")) {
                delete.setLong(1, now);
                delete.executeUpdate();
            }
        });
    }

    /** Drops every question naming this player that is not an alliance's. */
    public boolean forgetQuestionsOf(UUID player) {
        return database.write(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM invitation WHERE subject = ? AND kind <> ?")) {
                delete.setString(1, player.toString());
                delete.setString(2, Invitation.Kind.ALLIANCE.name());
                delete.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ the log

    public boolean addEvents(List<GuildEvent> events) {
        return database.write(connection -> {
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

    /** A page of one guild's log, newest first. {@code kinds} empty means every kind. */
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

    private static void uuid(PreparedStatement statement, int index, Optional<UUID> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get().toString());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static Optional<UUID> uuid(String written) {
        try {
            return written == null ? Optional.empty() : Optional.of(UUID.fromString(written));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }
}
