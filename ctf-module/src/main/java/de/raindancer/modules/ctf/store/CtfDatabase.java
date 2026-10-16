package de.raindancer.modules.ctf.store;

import de.raindancer.core.data.sql.Schema;

/** The shape of {@code ctf.db}. Steps are only ever appended to — see {@link Schema}. */
public final class CtfDatabase {

    public static final String NAME = "ctf";

    public static final Schema SCHEMA = Schema.of(
            "CREATE TABLE stats (player TEXT PRIMARY KEY, name TEXT NOT NULL, rounds INTEGER NOT NULL DEFAULT 0, "
                    + "wins INTEGER NOT NULL DEFAULT 0, losses INTEGER NOT NULL DEFAULT 0, "
                    + "captures INTEGER NOT NULL DEFAULT 0, returns INTEGER NOT NULL DEFAULT 0, "
                    + "kills INTEGER NOT NULL DEFAULT 0, deaths INTEGER NOT NULL DEFAULT 0, "
                    + "carrier_kills INTEGER NOT NULL DEFAULT 0, mvps INTEGER NOT NULL DEFAULT 0, "
                    + "fastest_capture BIGINT NOT NULL DEFAULT 0, xp BIGINT NOT NULL DEFAULT 0)",
            "CREATE INDEX stats_wins ON stats (wins DESC)",
            "CREATE INDEX stats_captures ON stats (captures DESC)",
            "CREATE INDEX stats_xp ON stats (xp DESC)",
            // One row per player and week: the weekly board is a sum over the current week only.
            "CREATE TABLE weekly (player TEXT NOT NULL, week INTEGER NOT NULL, name TEXT NOT NULL, "
                    + "wins INTEGER NOT NULL DEFAULT 0, captures INTEGER NOT NULL DEFAULT 0, "
                    + "kills INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (player, week))",
            "CREATE INDEX weekly_week ON weekly (week, captures DESC)",
            "CREATE TABLE profile (player TEXT PRIMARY KEY, trail TEXT NOT NULL DEFAULT 'TEAM', "
                    + "show TEXT NOT NULL DEFAULT 'BURST', class TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE challenge (player TEXT NOT NULL, day INTEGER NOT NULL, challenge TEXT NOT NULL, "
                    + "done INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (player, day, challenge))");

    private CtfDatabase() {
    }
}
