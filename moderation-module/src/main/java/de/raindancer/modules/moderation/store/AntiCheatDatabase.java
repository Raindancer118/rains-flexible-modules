package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.Schema;

/** The network's one anti-cheat database: evidence and violation levels, so a cheater hopping servers is the same cheater. */
public final class AntiCheatDatabase {

    public static final String NAME = "anticheat";

    public static final Schema SCHEMA = Schema.of("""
            CREATE TABLE ac_evidence (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                player    TEXT    NOT NULL,
                at        INTEGER NOT NULL,
                check_key TEXT    NOT NULL,
                level     REAL    NOT NULL,
                detail    TEXT    NOT NULL,
                world     TEXT    NOT NULL,
                x         INTEGER NOT NULL,
                y         INTEGER NOT NULL,
                z         INTEGER NOT NULL,
                ping      INTEGER NOT NULL,
                tps       REAL    NOT NULL
            )""",
            "CREATE INDEX ac_evidence_player ON ac_evidence (player, at)",
            """
            CREATE TABLE ac_player (
                player        TEXT    NOT NULL PRIMARY KEY,
                name          TEXT    NOT NULL,
                measured_from INTEGER NOT NULL
            )""",
            """
            CREATE TABLE ac_violation (
                player     TEXT    NOT NULL,
                check_key  TEXT    NOT NULL,
                level      REAL    NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (player, check_key)
            )""");

    private AntiCheatDatabase() {
    }
}
