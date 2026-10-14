package de.raindancer.modules.enchants.rules;

/** Totems kept in a chestplate: 2, 4 or 8, by level. */
public final class Undying {

    private Undying() {
    }

    public static int capacity(int level) {
        return level < 1 ? 0 : 2 << (Math.min(level, 3) - 1);
    }

    /** How many of {@code offered} totems go in, with {@code held} in already. */
    public static int takes(int level, int held, int offered) {
        return Math.max(0, Math.min(offered, capacity(level) - held));
    }
}
