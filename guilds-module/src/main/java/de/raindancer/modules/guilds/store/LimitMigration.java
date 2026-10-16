package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.guilds.model.Rank;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Raises the officers' and members' daily bank limits of guilds founded before 0.15.0 to the new defaults — once,
 * and only where a rank still holds exactly the old default, so a limit a guild chose for itself stays. Never lowers.
 * Runs before the book loads, so the book reads the raised limits.
 */
public final class LimitMigration {

    static final String SOURCE = "bank-limits-0.15";

    /** In minor units. */
    public record Limits(long oldOfficer, long newOfficer, long oldMember, long newMember) {
    }

    private LimitMigration() {
    }

    /** @return ranks raised; 0 when it ran before, nothing was on the old defaults, or the database is not writable */
    public static int run(Database database, Limits limits, LongSupplier clock) {
        AtomicInteger raised = new AtomicInteger();
        boolean written = database.write(connection -> {
            try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM guild_migration WHERE source = ?")) {
                query.setString(1, SOURCE);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next()) {
                        return;
                    }
                }
            }
            raised.addAndGet(raise(connection, Rank.OFFICER, limits.oldOfficer(), limits.newOfficer()));
            raised.addAndGet(raise(connection, Rank.MEMBER, limits.oldMember(), limits.newMember()));
            try (PreparedStatement mark = connection.prepareStatement(
                    "INSERT INTO guild_migration (source, at) VALUES (?, ?) ON CONFLICT (source) DO NOTHING")) {
                mark.setString(1, SOURCE);
                mark.setLong(2, clock.getAsLong());
                mark.executeUpdate();
            }
        });
        return written ? raised.get() : 0;
    }

    private static int raise(java.sql.Connection connection, String rank, long from, long to) throws java.sql.SQLException {
        if (to <= from || from < 0) {
            return 0;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rank SET daily_limit = ? WHERE id = ? AND daily_limit = ?")) {
            update.setLong(1, to);
            update.setString(2, rank);
            update.setLong(3, from);
            return update.executeUpdate();
        }
    }
}
