package de.raindancer.modules.arena.store;

import de.raindancer.core.data.sql.Schema;

/** The shape of {@code arena.db}. Steps are only ever appended to — see {@link Schema}. */
public final class StatsDatabase {

    public static final String NAME = "arena";

    public static final Schema SCHEMA = Schema.of(
            "CREATE TABLE stats (player TEXT PRIMARY KEY, name TEXT NOT NULL, wins INTEGER NOT NULL DEFAULT 0, "
                    + "losses INTEGER NOT NULL DEFAULT 0, draws INTEGER NOT NULL DEFAULT 0, "
                    + "kills INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0, "
                    + "best_wave INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX stats_wins ON stats (wins DESC)",
            "CREATE INDEX stats_wave ON stats (best_wave DESC)");

    private StatsDatabase() {
    }
}
