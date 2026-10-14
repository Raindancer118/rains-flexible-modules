package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Schema;

/** The guilds database — the network's one, or this server's guilds.db. Steps are only ever appended: a written step is a migration somebody ran. */
public final class GuildsDatabase {

    public static final String NAME = "guilds";

    public static final Schema SCHEMA = Schema.of(
            """
            CREATE TABLE guild (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            tag TEXT NOT NULL,
                            colour TEXT NOT NULL,
                            emblem TEXT NOT NULL,
                            motd TEXT NOT NULL,
                            founded INTEGER NOT NULL,
                            leader TEXT NOT NULL,
                            join_mode TEXT NOT NULL,
                            xp INTEGER NOT NULL,
                            bank INTEGER NOT NULL,
                            home_world TEXT,
                            home_x REAL, home_y REAL, home_z REAL, home_yaw REAL, home_pitch REAL,
                            friendly_fire INTEGER NOT NULL,
                            ally_friendly_fire INTEGER NOT NULL
                        )
            """,
            """
            CREATE TABLE member (
                            player TEXT PRIMARY KEY,
                            guild TEXT NOT NULL,
                            rank TEXT NOT NULL,
                            joined INTEGER NOT NULL,
                            deposited INTEGER NOT NULL,
                            withdrawn INTEGER NOT NULL,
                            xp INTEGER NOT NULL,
                            taken_today INTEGER NOT NULL,
                            taken_on INTEGER NOT NULL
                        )
            """,
            """
            CREATE INDEX member_guild ON member (guild)
            """,
            """
            CREATE TABLE rank (
                            guild TEXT NOT NULL,
                            id TEXT NOT NULL,
                            name TEXT NOT NULL,
                            weight INTEGER NOT NULL,
                            permissions TEXT NOT NULL,
                            daily_limit INTEGER NOT NULL,
                            PRIMARY KEY (guild, id)
                        )
            """,
            """
            CREATE TABLE relation (
                            guild TEXT NOT NULL,
                            other TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            PRIMARY KEY (guild, other)
                        )
            """,
            """
            CREATE TABLE event (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            guild TEXT NOT NULL,
                            at INTEGER NOT NULL,
                            kind TEXT NOT NULL,
                            actor TEXT,
                            target TEXT,
                            amount INTEGER NOT NULL,
                            detail TEXT NOT NULL
                        )
            """,
            """
            CREATE INDEX event_guild ON event (guild, at)
            """,
            """
            CREATE TABLE invitation (
                            kind TEXT NOT NULL,
                            guild TEXT NOT NULL,
                            subject TEXT NOT NULL,
                            asked_by TEXT NOT NULL,
                            other TEXT NOT NULL,
                            expires INTEGER NOT NULL,
                            PRIMARY KEY (kind, guild, subject, other)
                        )
            """,
            """
            ALTER TABLE guild ADD COLUMN tag_style TEXT NOT NULL DEFAULT ''
            """,
            """
            ALTER TABLE guild ADD COLUMN name_style TEXT NOT NULL DEFAULT ''
            """,
            """
            CREATE TABLE guild_xp_source (
                guild TEXT NOT NULL,
                source TEXT NOT NULL,
                xp INTEGER NOT NULL,
                PRIMARY KEY (guild, source)
            )
            """,
            """
            CREATE TABLE contract (
                guild TEXT NOT NULL,
                week INTEGER NOT NULL,
                slot INTEGER NOT NULL,
                source TEXT NOT NULL,
                what TEXT NOT NULL,
                title TEXT NOT NULL,
                goal INTEGER NOT NULL,
                progress INTEGER NOT NULL,
                reward INTEGER NOT NULL,
                done INTEGER NOT NULL,
                PRIMARY KEY (guild, week, slot)
            )
            """,
            """
            CREATE TABLE boost (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                percent INTEGER NOT NULL,
                until INTEGER NOT NULL,
                started_by TEXT
            )
            """,
            """
            CREATE TABLE guild_standing (
                guild TEXT PRIMARY KEY,
                level_points INTEGER NOT NULL,
                prestige INTEGER NOT NULL,
                crowns INTEGER NOT NULL,
                respec_at INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE guild_perk (
                guild TEXT NOT NULL,
                perk TEXT NOT NULL,
                rank INTEGER NOT NULL,
                PRIMARY KEY (guild, perk)
            )
            """,
            """
            CREATE TABLE week_xp (
                guild TEXT NOT NULL,
                week INTEGER NOT NULL,
                xp INTEGER NOT NULL,
                PRIMARY KEY (guild, week)
            )
            """,
            """
            CREATE TABLE crown (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                guild TEXT NOT NULL,
                week INTEGER NOT NULL,
                settled_week INTEGER NOT NULL
            )
            """,
            // The server whose world the home is in; '' is a server of its own, where it is wherever it is.
            """
            ALTER TABLE guild ADD COLUMN home_server TEXT NOT NULL DEFAULT ''
            """,
            // Every name, tag and id a guild holds, one word one guild: the network's one namespace, decided by the
            // primary key when two servers found a guild at once.
            """
            CREATE TABLE guild_word (
                word TEXT NOT NULL PRIMARY KEY,
                guild TEXT NOT NULL
            )
            """,
            """
            CREATE INDEX guild_word_guild ON guild_word (guild)
            """,
            // One row per stack: a stack leaves the vault by deleting its row, so it leaves once, on one server.
            """
            CREATE TABLE guild_vault (
                id TEXT NOT NULL PRIMARY KEY,
                vault TEXT NOT NULL,
                slot TEXT NOT NULL,
                pos INTEGER NOT NULL,
                item TEXT NOT NULL
            )
            """,
            """
            CREATE INDEX guild_vault_vault ON guild_vault (vault)
            """,
            """
            CREATE TABLE guild_migration (
                source TEXT NOT NULL PRIMARY KEY,
                at INTEGER NOT NULL
            )
            """,
            // When a member was last on any server: what a leader's absence is measured by.
            """
            ALTER TABLE member ADD COLUMN seen INTEGER NOT NULL DEFAULT 0
            """);

    private GuildsDatabase() {
    }
}
