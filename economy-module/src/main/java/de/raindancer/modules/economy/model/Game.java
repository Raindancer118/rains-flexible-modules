package de.raindancer.modules.economy.model;

import org.bukkit.Material;

/**
 * Every game of chance, with what an owner can set for each.
 *
 * @param bets whether a player chooses a stake (scratch cards and lottery tickets have a fixed price)
 * @param edge whether the payouts are worked out from a house edge (blackjack and baccarat play by casino rules)
 */
public enum Game {
    COINFLIP("coinflip", "Coin flip", Material.SUNFLOWER, true, true),
    DICE("dice", "Dice", Material.WHITE_WOOL, true, true),
    SLOTS("slots", "Slot machine", Material.DIAMOND, true, true),
    ROULETTE("roulette", "Roulette", Material.ENDER_PEARL, true, true),
    BLACKJACK("blackjack", "Blackjack", Material.PAPER, true, false),
    BACCARAT("baccarat", "Baccarat", Material.RED_CONCRETE, true, false),
    HILO("hilo", "Hi-Lo", Material.LIME_CONCRETE, true, true),
    MINES("mines", "Mines", Material.TNT, true, true),
    CRASH("crash", "Crash", Material.FIREWORK_ROCKET, true, true),
    RACE("race", "Horse race", Material.SADDLE, true, true),
    SCRATCH("scratch", "Scratch card", Material.MAP, false, true),
    LOTTERY("lottery", "Lottery", Material.FILLED_MAP, false, false);

    private final String key;
    private final String title;
    private final Material icon;
    private final boolean bets;
    private final boolean edge;

    Game(String key, String title, Material icon, boolean bets, boolean edge) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        this.bets = bets;
        this.edge = edge;
    }

    /** The settings key's first word: {@code features.<key>}, {@code <key>.max-bet}. */
    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public boolean bets() {
        return bets;
    }

    public boolean edge() {
        return edge;
    }

    /** Where its page is in /settings. */
    public String settingsPath() {
        return "economy/gambling/" + key;
    }
}
