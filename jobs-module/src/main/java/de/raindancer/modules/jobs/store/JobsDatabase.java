package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Schema;

/**
 * The network's one jobs database. Steps are only ever appended: a written step is a migration somebody ran.
 *
 * <p>Counts are columns that only ever get {@code + delta}; whatever must happen once — a goal ending, a quest
 * being paid — is a row that one server changes and the others find already changed.
 */
public final class JobsDatabase {

    public static final String NAME = "jobs";

    public static final Schema SCHEMA = Schema.of(
            """
            CREATE TABLE goal (
                number INTEGER NOT NULL PRIMARY KEY,
                template TEXT NOT NULL,
                title TEXT NOT NULL,
                icon TEXT NOT NULL,
                kind TEXT NOT NULL,
                items TEXT NOT NULL,
                amount INTEGER NOT NULL,
                started_at INTEGER NOT NULL,
                ends_at INTEGER NOT NULL
            )
            """,
            "CREATE UNIQUE INDEX goal_template ON goal (template)",
            """
            CREATE TABLE goal_given (
                goal INTEGER NOT NULL,
                player TEXT NOT NULL,
                given INTEGER NOT NULL,
                PRIMARY KEY (goal, player)
            )
            """,
            "CREATE TABLE goal_learned (template TEXT NOT NULL PRIMARY KEY, amount INTEGER NOT NULL)",
            "CREATE TABLE goal_owed (player TEXT NOT NULL PRIMARY KEY, minor INTEGER NOT NULL)",
            // One row, locked by whoever changes the board: goal numbers, the board's size and what a goal still
            // takes are decided one server at a time.
            "CREATE TABLE jobs_counter (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1), next INTEGER NOT NULL)",
            "INSERT INTO jobs_counter (id, next) VALUES (1, 1)",
            """
            CREATE TABLE quest_day (
                player TEXT NOT NULL PRIMARY KEY,
                day TEXT NOT NULL,
                tier INTEGER NOT NULL,
                yesterday TEXT NOT NULL,
                rerolls INTEGER NOT NULL,
                rev INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE quest (
                player TEXT NOT NULL,
                slot INTEGER NOT NULL,
                template TEXT NOT NULL,
                amount INTEGER NOT NULL,
                pay INTEGER NOT NULL,
                progress INTEGER NOT NULL,
                state TEXT NOT NULL,
                PRIMARY KEY (player, slot)
            )
            """,
            """
            CREATE TABLE order_ledger (
                player TEXT NOT NULL PRIMARY KEY,
                day TEXT NOT NULL,
                taken INTEGER NOT NULL,
                rerolls INTEGER NOT NULL,
                offered INTEGER NOT NULL,
                rev INTEGER NOT NULL,
                work TEXT,
                says TEXT,
                units INTEGER,
                pay INTEGER,
                started_at INTEGER,
                ends_at INTEGER,
                progress INTEGER,
                state TEXT
            )
            """,
            "CREATE TABLE jobs_import (source TEXT NOT NULL, server TEXT NOT NULL, PRIMARY KEY (source, server))");

    private JobsDatabase() {
    }
}
