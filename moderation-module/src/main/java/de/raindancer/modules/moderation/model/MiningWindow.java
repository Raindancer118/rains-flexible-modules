package de.raindancer.modules.moderation.model;

/**
 * A rolling count of how many of the last N blocks a player mined were valuable ore.
 *
 * <h2>Why a window rather than a lifetime total</h2>
 * A player who found three diamonds in their first ten minutes on the server and has mined stone ever
 * since has a lifetime ratio that never recovers, and a plugin that judged on it would flag them for
 * ever for one lucky vein. A window answers a different, better question: what does the last few
 * minutes of digging look like — which is the thing that actually changes when somebody switches on
 * x-ray.
 *
 * <h2>Why this holds no server, no player and no material</h2>
 * It knows only "ore" or "not ore", decided by the caller. That is what makes it testable without
 * Bukkit and reusable if the watched ore list ever needs to differ — a nether server's valuable block
 * is not a diamond.
 */
public final class MiningWindow {

    private final boolean[] recent;
    private int cursor;
    private int filled;
    private int oreCount;

    public MiningWindow(int size) {
        this.recent = new boolean[Math.max(1, size)];
    }

    /** Records one more block mined, dropping the oldest once the window is full. */
    public synchronized void record(boolean isOre) {
        if (filled == recent.length) {
            if (recent[cursor]) {
                oreCount--;
            }
        } else {
            filled++;
        }
        recent[cursor] = isOre;
        if (isOre) {
            oreCount++;
        }
        cursor = (cursor + 1) % recent.length;
    }

    /** How many blocks in the window were ore. */
    public synchronized int oreCount() {
        return oreCount;
    }

    /** How many blocks are in the window at all — below the configured size until it first fills. */
    public synchronized int totalCount() {
        return filled;
    }

    /** Ore as a fraction of the window, zero while the window is empty. */
    public synchronized double ratio() {
        return filled == 0 ? 0.0 : (double) oreCount / filled;
    }
}
