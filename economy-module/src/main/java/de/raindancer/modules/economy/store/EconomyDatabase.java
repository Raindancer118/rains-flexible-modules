package de.raindancer.modules.economy.store;

import de.raindancer.core.data.sql.Schema;

/** {@code economy.db}: one step per statement, because the driver runs only the first of several. */
public final class EconomyDatabase {

    public static final String NAME = "economy";

    public static final Schema SCHEMA = Schema.of(
            "CREATE TABLE account (id TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL, "
                    + "frozen INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, "
                    + "daily_day INTEGER NOT NULL DEFAULT -1, daily_streak INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX account_name ON account (name COLLATE NOCASE)",
            "CREATE TABLE ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, account TEXT NOT NULL, "
                    + "other TEXT, delta INTEGER NOT NULL, balance INTEGER NOT NULL, kind TEXT NOT NULL, "
                    + "reason TEXT NOT NULL)",
            "CREATE INDEX ledger_account ON ledger (account, id)",
            "CREATE TABLE note (serial TEXT PRIMARY KEY, value INTEGER NOT NULL, issued_to TEXT, "
                    + "issued_at INTEGER NOT NULL, redeemed_by TEXT, redeemed_at INTEGER)",
            "CREATE TABLE market (material TEXT PRIMARY KEY, pressure REAL NOT NULL, updated INTEGER NOT NULL)",
            "CREATE TABLE lottery (id INTEGER PRIMARY KEY CHECK (id = 1), draw INTEGER NOT NULL, next_at INTEGER NOT NULL)",
            "CREATE TABLE lottery_ticket (draw INTEGER NOT NULL, player TEXT NOT NULL, tickets INTEGER NOT NULL, "
                    + "PRIMARY KEY (draw, player))",
            "CREATE TABLE coin_float (value INTEGER PRIMARY KEY, outstanding INTEGER NOT NULL)",
            "CREATE TABLE contract (id TEXT PRIMARY KEY, employer TEXT NOT NULL, employer_name TEXT NOT NULL, "
                    + "employee TEXT NOT NULL, employee_name TEXT NOT NULL, wage INTEGER NOT NULL, every INTEGER NOT NULL, "
                    + "next_at INTEGER NOT NULL, missed INTEGER NOT NULL, title TEXT NOT NULL, since INTEGER NOT NULL)",
            "CREATE TABLE lottery_pick (id INTEGER PRIMARY KEY AUTOINCREMENT, draw INTEGER NOT NULL, "
                    + "player TEXT NOT NULL, numbers TEXT NOT NULL)");

    private EconomyDatabase() {
    }
}
