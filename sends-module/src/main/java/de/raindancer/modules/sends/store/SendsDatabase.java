package de.raindancer.modules.sends.store;

import de.raindancer.core.data.sql.Schema;

/** The shape of {@code sends.db}. Steps are only ever appended to — see {@link Schema}. */
public final class SendsDatabase {

    public static final String NAME = "sends";

    public static final Schema SCHEMA = Schema.of(
            "CREATE TABLE send (id TEXT PRIMARY KEY, sender TEXT NOT NULL, sender_name TEXT NOT NULL, "
                    + "recipient TEXT NOT NULL, recipient_name TEXT NOT NULL, created INTEGER NOT NULL, "
                    + "expires INTEGER NOT NULL, state TEXT NOT NULL, settled INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX send_state ON send (state)",
            "CREATE TABLE send_item (send TEXT NOT NULL REFERENCES send (id), slot INTEGER NOT NULL, "
                    + "item BLOB NOT NULL, PRIMARY KEY (send, slot))");

    private SendsDatabase() {
    }
}
