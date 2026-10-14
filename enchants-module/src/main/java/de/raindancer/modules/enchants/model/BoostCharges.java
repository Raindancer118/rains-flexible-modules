package de.raindancer.modules.enchants.model;

/**
 * The Boost charges of one player, on the wall clock so they keep recharging while offline and across
 * servers. Charges come back one at a time; the next one is due {@code nextMillis}.
 */
public final class BoostCharges {

    private int charges;
    private long nextMillis;

    private BoostCharges(int charges, long nextMillis) {
        this.charges = Math.max(0, charges);
        this.nextMillis = nextMillis;
    }

    public static BoostCharges full(int capacity) {
        return new BoostCharges(capacity, 0);
    }

    public static BoostCharges of(int charges, long nextMillis) {
        return new BoostCharges(charges, nextMillis);
    }

    public void update(long now, int capacity, long rechargeMillis) {
        if (charges >= capacity) {
            charges = capacity;
            nextMillis = 0;
            return;
        }
        // Another server's clock may run ahead: never wait longer than one recharge from now.
        if (nextMillis == 0 || nextMillis > now + rechargeMillis) {
            nextMillis = now + rechargeMillis;
        }
        while (charges < capacity && now >= nextMillis) {
            charges++;
            nextMillis += rechargeMillis;
        }
        if (charges >= capacity) {
            nextMillis = 0;
        }
    }

    /** Spends one charge if there is one. */
    public boolean take(long now, int capacity, long rechargeMillis) {
        update(now, capacity, rechargeMillis);
        if (charges <= 0) {
            return false;
        }
        charges--;
        if (nextMillis == 0) {
            nextMillis = now + rechargeMillis;
        }
        return true;
    }

    public int charges() {
        return charges;
    }

    public long nextMillis() {
        return nextMillis;
    }

    public long millisToNext(long now) {
        return nextMillis == 0 ? 0 : Math.max(0, nextMillis - now);
    }
}
