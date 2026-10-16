package de.raindancer.modules.oneblock.store;

import de.raindancer.core.data.sql.Schema;

/**
 * The islands' tables, in the network's shared database: the island server is the only writer, every other server
 * reads who plays on which island and with which money. Steps are only ever appended to — see {@link Schema}.
 */
public final class OneBlockDatabase {

    public static final String NAME = "oneblock";

    public static final Schema SCHEMA = Schema.of(
            "CREATE TABLE island (id TEXT PRIMARY KEY, owner TEXT NOT NULL, mode TEXT NOT NULL, "
                    + "state TEXT NOT NULL, data TEXT NOT NULL, updated INTEGER NOT NULL)",
            "CREATE TABLE island_member (player TEXT PRIMARY KEY, island TEXT NOT NULL)",
            "CREATE INDEX island_member_island ON island_member (island)",
            "CREATE TABLE island_position (player TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL NOT NULL, "
                    + "y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, updated INTEGER NOT NULL)");

    private OneBlockDatabase() {
    }
}
