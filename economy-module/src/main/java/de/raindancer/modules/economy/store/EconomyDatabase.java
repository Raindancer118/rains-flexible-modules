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
                    + "player TEXT NOT NULL, numbers TEXT NOT NULL)",
            "CREATE TABLE auction (id TEXT PRIMARY KEY, seller TEXT NOT NULL, seller_name TEXT NOT NULL, "
                    + "item BLOB NOT NULL, item_name TEXT NOT NULL, start INTEGER NOT NULL, buyout INTEGER NOT NULL, "
                    + "bid INTEGER NOT NULL, bidder TEXT, bidder_name TEXT NOT NULL, bids INTEGER NOT NULL, "
                    + "seconds INTEGER NOT NULL, listed_at INTEGER NOT NULL, ends_at INTEGER NOT NULL)",
            "CREATE TABLE auction_claim (id TEXT PRIMARY KEY, player TEXT NOT NULL, item BLOB NOT NULL, "
                    + "item_name TEXT NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL)",
            "CREATE TABLE raffle (id TEXT PRIMARY KEY, number INTEGER NOT NULL, host TEXT, host_name TEXT NOT NULL, "
                    + "item BLOB, prize_name TEXT NOT NULL, prize INTEGER NOT NULL, ticket_price INTEGER NOT NULL, "
                    + "most_tickets INTEGER NOT NULL, per_player INTEGER NOT NULL, started_at INTEGER NOT NULL, "
                    + "ends_at INTEGER NOT NULL)",
            "CREATE TABLE raffle_ticket (raffle TEXT NOT NULL, player TEXT NOT NULL, tickets INTEGER NOT NULL, "
                    + "seq INTEGER NOT NULL, PRIMARY KEY (raffle, player))",
            "CREATE TABLE raffle_counter (id INTEGER PRIMARY KEY CHECK (id = 1), next INTEGER NOT NULL)",
            "CREATE TABLE wealth_tax (id INTEGER PRIMARY KEY CHECK (id = 1), last_at INTEGER NOT NULL)",
            "CREATE TABLE loan (player TEXT PRIMARY KEY, name TEXT NOT NULL, borrowed INTEGER NOT NULL, "
                    + "owed INTEGER NOT NULL, taken_at INTEGER NOT NULL, due_at INTEGER NOT NULL, late_at INTEGER NOT NULL)",
            // What every account ever took in and paid out, by kind — the statement is trimmed, this is not.
            "CREATE TABLE ledger_total (account TEXT NOT NULL, kind TEXT NOT NULL, gained INTEGER NOT NULL, "
                    + "lost INTEGER NOT NULL, PRIMARY KEY (account, kind))",
            "INSERT INTO ledger_total (account, kind, gained, lost) SELECT account, kind, "
                    + "SUM(CASE WHEN delta > 0 THEN delta ELSE 0 END), SUM(CASE WHEN delta < 0 THEN -delta ELSE 0 END) "
                    + "FROM ledger GROUP BY account, kind",
            "CREATE TABLE credit_record (account TEXT PRIMARY KEY, on_time INTEGER NOT NULL, late INTEGER NOT NULL)",
            // What happened in each hour of playtime (Core's count of minutes not away), for "lately".
            "CREATE TABLE credit_hour (account TEXT NOT NULL, hour INTEGER NOT NULL, earned INTEGER NOT NULL, "
                    + "staked INTEGER NOT NULL, won INTEGER NOT NULL, PRIMARY KEY (account, hour))",
            // The loan limit reads only the last hours of play, spending and money between players included.
            "ALTER TABLE credit_hour ADD COLUMN spent INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE credit_hour ADD COLUMN received INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE credit_hour ADD COLUMN paid INTEGER NOT NULL DEFAULT 0");

    /** The first step of the lifetime totals — the steps before it are what a server had until then. */
    public static final int FIRST_CREDIT_STEP = SCHEMA.size() - 7;

    private EconomyDatabase() {
    }
}
