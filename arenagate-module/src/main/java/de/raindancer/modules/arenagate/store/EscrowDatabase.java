package de.raindancer.modules.arenagate.store;

import de.raindancer.core.data.sql.Schema;

/** The shape of {@code arenagate.db}. Steps are only ever appended to — see {@link Schema}. */
public final class EscrowDatabase {

    public static final String NAME = "arenagate";

    public static final Schema SCHEMA = Schema.of(
            "CREATE TABLE escrow (fight TEXT NOT NULL, player TEXT NOT NULL, amount INTEGER NOT NULL, "
                    + "state TEXT NOT NULL, created INTEGER NOT NULL, settled INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (fight, player))",
            "CREATE INDEX escrow_state ON escrow (state)");

    private EscrowDatabase() {
    }
}
