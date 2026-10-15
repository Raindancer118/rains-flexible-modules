package de.raindancer.modules.oneblock.model;

import java.util.UUID;

/** The numbers invites and the sidebar compare: kept from BentoBox's scale, so the rank names did not have to move. */
public final class Ranks {

    public static final int OWNER = 1000;
    public static final int MEMBER = 500;
    public static final int VISITOR = 0;

    private Ranks() {
    }

    public static int of(Island island, UUID player) {
        if (island.owner().equals(player)) {
            return OWNER;
        }
        return island.isMember(player) ? MEMBER : VISITOR;
    }
}
