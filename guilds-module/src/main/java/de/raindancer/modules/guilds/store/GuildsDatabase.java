package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Schema;

/** guilds.db, through Core's Databases. Steps are only ever appended: a written step is a migration somebody ran. */
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
            """);

    private GuildsDatabase() {
    }
}
