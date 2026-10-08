package de.raindancer.modules.anticheat.model;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Block changes the server has sent a client but the client has not provably applied yet. Until the
 * ping sent right behind a change comes back, the client may still stand on a block the server already
 * broke, or bump into one it already removed — so nothing near such a block is held against them.
 *
 * <p>Our transaction ids count down and the client answers pings in order, so an answer to id
 * {@code c} confirms every change tagged with an id of {@code c} or above.
 */
public final class PendingBlocks {

    public static final int CAPACITY = 4096;
    /**
     * The longest an unanswered change counts. A ping that never comes back (a lost packet, a plugin
     * eating it, a client holding answers back to buy itself leeway) must not exempt anyone for long.
     */
    public static final long GIVE_UP_MILLIS = 3000;
    /** The client applies the update on its next tick, which may come a little after the answer. */
    static final long GRACE_MILLIS = 60;

    private record Change(int x, int y, int z, int transaction, long sentMillis, long[] confirmedAt) {
    }

    private final ArrayDeque<Change> changes = new ArrayDeque<>();

    public synchronized void sent(int x, int y, int z, int transaction, long millis) {
        changes.addLast(new Change(x, y, z, transaction, millis, new long[]{-1}));
        while (changes.size() > CAPACITY) {
            changes.removeFirst();
        }
    }

    public synchronized void confirmed(int transaction, long millis) {
        for (Change change : changes) {
            if (change.confirmedAt()[0] < 0 && change.transaction() >= transaction) {
                change.confirmedAt()[0] = millis;
            }
        }
    }

    /**
     * Whether any block in the inclusive box may still look different to the client.
     *
     * @param patienceMillis how long an unanswered change counts — what the client's ping can explain,
     *                       at most {@link #GIVE_UP_MILLIS}
     */
    public synchronized boolean uncertain(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long now,
                                          long patienceMillis) {
        long patience = Math.min(patienceMillis, GIVE_UP_MILLIS);
        boolean found = false;
        for (Iterator<Change> it = changes.iterator(); it.hasNext(); ) {
            Change change = it.next();
            long confirmed = change.confirmedAt()[0];
            boolean settled = confirmed >= 0 ? now - confirmed > GRACE_MILLIS : now - change.sentMillis() > patience;
            if (settled) {
                it.remove();
                continue;
            }
            found |= change.x() >= minX && change.x() <= maxX && change.y() >= minY && change.y() <= maxY
                    && change.z() >= minZ && change.z() <= maxZ;
        }
        return found;
    }

    public synchronized void clear() {
        changes.clear();
    }

    public synchronized int size() {
        return changes.size();
    }
}
