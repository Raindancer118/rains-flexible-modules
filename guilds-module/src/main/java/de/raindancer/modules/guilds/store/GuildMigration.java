package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.HomeSpot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Brings this server's own guilds database — what the module kept per server before it was the network's — into
 * the shared one, once per server.
 *
 * <p>Guild by guild, through the same write a founding uses, so the network's rules hold for what comes in: a
 * guild whose name, tag or id another server's guild already holds, or whose members are already in another
 * guild, stays where it was (and is told to the log) rather than being merged into somebody else's. A guild's
 * experience, standing, contracts, log and open questions come with it. A home that named no server — one set
 * before the network — is this server's.
 */
public final class GuildMigration {

    public record Report(int brought, int left, boolean failed) {
    }

    private GuildMigration() {
    }

    /**
     * @param from   this server's own database
     * @param to     the network's
     * @param server this server's id; the source's name in the shared database
     * @return what happened; {@code failed} leaves everything to be tried again at the next start
     */
    public static Report run(Database from, Database to, String server, Consumer<String> problems, LongSupplier clock) {
        String source = "server:" + server;
        if (!from.isUsable() || !to.isUsable()) {
            return new Report(0, 0, true);
        }
        Optional<Boolean> done = to.read(connection -> marked(connection, source));
        if (done.isEmpty()) {
            return new Report(0, 0, true);
        }
        if (done.get()) {
            return new Report(0, 0, false);
        }
        Optional<List<Guild>> guilds = new GuildSql(from, problems).loadAll();
        if (guilds.isEmpty()) {
            return new Report(0, 0, true);
        }
        GuildSql target = new GuildSql(to, problems);
        int brought = 0;
        int left = 0;
        Set<String> imported = new HashSet<>();
        for (Guild guild : guilds.get()) {
            Guild here = guild.home().filter(home -> home.server().isBlank() && !server.isBlank()).map(home ->
                    guild.withHome(Optional.of(new HomeSpot(home.world(), home.x(), home.y(), home.z(), home.yaw(),
                            home.pitch(), server)))).orElse(guild);
            GuildSql.Outcome made = target.create(here);
            if (made == GuildSql.Outcome.FAILED) {
                return new Report(brought, left, true);
            }
            if (made != GuildSql.Outcome.DONE) {
                problems.accept("the guild " + guild.name() + " of this server was not brought into the network's database: "
                        + made + " (another guild holds its name, tag or id, or its members are in one). It stays in "
                        + "this server's own database.");
                left++;
                continue;
            }
            imported.add(guild.id().value());
            brought++;
            if (!copyOf(from, to, guild.id().value())) {
                return new Report(brought, left, true);
            }
        }
        boolean finished = to.write(connection -> {
            copy(from, connection, "boost", "", List.of());
            copy(from, connection, "crown", "", List.of());
            for (String id : imported) {
                copy(from, connection, "invitation", "WHERE guild = ?", List.of(id));
            }
            try (PreparedStatement mark = connection.prepareStatement(
                    "INSERT INTO guild_migration (source, at) VALUES (?, ?) ON CONFLICT (source) DO NOTHING")) {
                mark.setString(1, source);
                mark.setLong(2, clock.getAsLong());
                mark.executeUpdate();
            }
        });
        return new Report(brought, left, !finished);
    }

    private static boolean marked(Connection connection, String source) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM guild_migration WHERE source = ?")) {
            query.setString(1, source);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** One guild's experience by source, week, contracts, standing, perks and log. */
    private static boolean copyOf(Database from, Database to, String guild) {
        return to.write(connection -> {
            for (String table : List.of("guild_xp_source", "week_xp", "contract", "guild_standing", "guild_perk")) {
                copy(from, connection, table, "WHERE guild = ?", List.of(guild));
            }
            copy(from, connection, "event", "WHERE guild = ?", List.of(guild));
        });
    }

    /** Rows of {@code table} in {@code from} into the same table of {@code connection}'s database; theirs win. */
    private static void copy(Database from, Connection connection, String table, String where, List<String> args)
            throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        boolean read = from.read(source -> {
            try (PreparedStatement query = source.prepareStatement("SELECT * FROM " + table + " " + where)) {
                for (int index = 0; index < args.size(); index++) {
                    query.setString(index + 1, args.get(index));
                }
                try (ResultSet found = query.executeQuery()) {
                    ResultSetMetaData meta = found.getMetaData();
                    for (int column = 1; column <= meta.getColumnCount(); column++) {
                        // The log's own numbering is given anew here: two servers' entry 1 are two entries.
                        if (!(table.equals("event") && meta.getColumnName(column).equals("id"))) {
                            columns.add(meta.getColumnName(column));
                            types.add(meta.getColumnType(column));
                        }
                    }
                    while (found.next()) {
                        List<Object> row = new ArrayList<>();
                        for (String column : columns) {
                            row.add(found.getObject(column));
                        }
                        rows.add(row);
                    }
                }
            }
            return true;
        }).orElse(false);
        if (!read) {
            throw new SQLException("the table " + table + " of this server's database could not be read");
        }
        if (rows.isEmpty()) {
            return;
        }
        String marks = String.join(", ", columns.stream().map(column -> "?").toList());
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " ("
                + String.join(", ", columns) + ") VALUES (" + marks + ") ON CONFLICT DO NOTHING")) {
            for (List<Object> row : rows) {
                for (int index = 0; index < row.size(); index++) {
                    if (row.get(index) == null) {
                        insert.setNull(index + 1, types.get(index) == Types.NULL ? Types.VARCHAR : types.get(index));
                    } else {
                        insert.setObject(index + 1, row.get(index));
                    }
                }
                insert.executeUpdate();
            }
        }
    }
}
