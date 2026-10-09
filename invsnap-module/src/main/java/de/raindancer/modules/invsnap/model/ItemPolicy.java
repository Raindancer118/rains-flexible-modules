package de.raindancer.modules.invsnap.model;

import java.util.UUID;

/**
 * One policy on one item.
 *
 * @param value   what the item was worth when insured; renewals are priced from this, because the item
 *                itself may be in somebody else's hands when they fall due
 * @param premium the premium most recently taken, before any price index — for display
 * @param nextDue epoch milliseconds the next premium falls due
 * @param ended   empty while in force, otherwise why it is not: {@code unpaid}, {@code worn},
 *                {@code cancelled}
 * @param told    whether the owner has been told it ended
 */
public record ItemPolicy(String id, UUID owner, String description, String material, long value, long premium,
                         long nextDue, String ended, boolean told) {

    public static final String UNPAID = "unpaid";
    public static final String WORN = "worn";
    public static final String CANCELLED = "cancelled";

    public boolean inForce() {
        return ended == null || ended.isEmpty();
    }

    public ItemPolicy renewed(long premiumTaken, long due) {
        return new ItemPolicy(id, owner, description, material, value, premiumTaken, due, "", false);
    }

    public ItemPolicy endedBy(String reason) {
        return new ItemPolicy(id, owner, description, material, value, premium, nextDue, reason, false);
    }

    public ItemPolicy toldOwner() {
        return new ItemPolicy(id, owner, description, material, value, premium, nextDue, ended, true);
    }
}
