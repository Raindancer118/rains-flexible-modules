package de.raindancer.modules.enchants.rules;

import java.util.List;

/**
 * The fighting enchantments' numbers. Against a player every bonus counts half, so a fight between two enchanted
 * players stays a fight.
 */
public final class Offence {

    public static final double PVP_SHARE = 0.5;
    /** Lifesteal never heals more than three hearts from one hit. */
    public static final double MOST_HEALED = 6;
    public static final double EXECUTION_BELOW = 0.3;
    public static final double CLEAVE_RADIUS = 2.5;
    public static final int BLEED_TICKS = 80;

    /**
     * One melee hit and what bears on it.
     *
     * @param damage   what the hit does before Rain's enchantments
     * @param overpower  … executioner, berserk: the weapon's levels
     */
    public record Hit(double damage, int overpower, int executioner, int berserk, double targetHealth,
                      double targetMost, double ownHealth, double ownMost, double armour, double toughness,
                      boolean againstPlayer) {
    }

    /** A potion effect to give: its key, amplifier and ticks. */
    public record Dose(String effect, int amplifier, int ticks) {
    }

    private Offence() {
    }

    private static double share(boolean againstPlayer) {
        return againstPlayer ? PVP_SHARE : 1;
    }

    /** What the hit does with Overpower, Executioner and Berserk (Armor Pierce comes on top: {@link #pierce}). */
    public static double damage(Hit hit) {
        double share = share(hit.againstPlayer());
        double flat = 1.5 * hit.overpower() * share;
        double percent = 0;
        if (hit.executioner() > 0 && hit.targetMost() > 0 && hit.targetHealth() / hit.targetMost() < EXECUTION_BELOW) {
            percent += 0.15 * hit.executioner();
        }
        if (hit.berserk() > 0 && hit.ownMost() > 0) {
            double missing = Math.max(0, 1 - hit.ownHealth() / hit.ownMost());
            percent += missing * (0.1 + 0.2 * hit.berserk());
        }
        return (hit.damage() + flat) * (1 + percent * share);
    }

    /** What share of a hit armour takes off, as the game reckons it. */
    public static double armourShare(double damage, double armour, double toughness) {
        double counted = Math.max(armour / 5, armour - damage / (2 + toughness / 4));
        return Math.clamp(counted, 0, 20) / 25;
    }

    /** Extra damage that undoes 15 % a level of what armour would take off the hit. */
    public static double pierce(double damage, double armour, double toughness, int level, boolean againstPlayer) {
        if (level < 1 || armour <= 0 || damage <= 0) {
            return 0;
        }
        double taken = armourShare(damage, armour, toughness);
        double wanted = damage * (1 - taken) + 0.15 * level * share(againstPlayer) * damage * taken;
        // Armour takes a smaller share off a bigger hit, so the extra is searched for, not worked out in one go.
        double low = 0;
        double high = damage * 10;
        for (int i = 0; i < 40; i++) {
            double middle = (low + high) / 2;
            double through = (damage + middle) * (1 - armourShare(damage + middle, armour, toughness));
            if (through < wanted) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /** What Lifesteal heals from a hit that did {@code dealt}: 6, 10 or 14 percent of it. */
    public static double lifesteal(int level, double dealt, boolean againstPlayer) {
        if (level < 1 || dealt <= 0) {
            return 0;
        }
        return Math.min(MOST_HEALED, dealt * (0.02 + 0.04 * level) * share(againstPlayer));
    }

    /** The wound's stacks after one more hit: up to two a level. */
    public static int bleedStacks(int level, int before) {
        return Math.min(2 * level, before + 1);
    }

    public static double bleedPerSecond(int stacks, boolean againstPlayer) {
        return 0.5 * stacks * share(againstPlayer);
    }

    /** How many others around the target a Cleave axe strikes too. */
    public static int cleaveTargets(int level) {
        return level + 1;
    }

    /** What share of the hit they take: 30, 45 or 60 percent. */
    public static double cleaveShare(int level, boolean againstPlayer) {
        return (0.15 + 0.15 * level) * share(againstPlayer);
    }

    public static List<Dose> venom(int level, boolean againstPlayer) {
        double share = share(againstPlayer);
        return switch (Math.clamp(level, 0, 3)) {
            case 0 -> List.of();
            case 1 -> List.of(new Dose("poison", 0, (int) (80 * share)));
            case 2 -> List.of(new Dose("poison", 1, (int) (80 * share)));
            default -> List.of(new Dose("poison", 1, (int) (100 * share)), new Dose("wither", 0, (int) (60 * share)));
        };
    }
}
