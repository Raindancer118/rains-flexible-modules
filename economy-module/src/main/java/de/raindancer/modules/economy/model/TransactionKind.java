package de.raindancer.modules.economy.model;

import org.bukkit.Material;

/** Why money moved — the word a statement shows beside each line. */
public enum TransactionKind {
    OPENING("Opening balance", Material.ENDER_CHEST),
    PAY("Payment", Material.WRITABLE_BOOK),
    BILL("Bill", Material.PAPER),
    TAX("Tax", Material.IRON_BARS),
    WITHDRAW("Cash withdrawn", Material.GOLD_NUGGET),
    DEPOSIT("Cash paid in", Material.HOPPER),
    FEE("Fee", Material.IRON_BARS),
    BUY("Bought", Material.EMERALD),
    SELL("Sold", Material.CHEST),
    REWARD("Reward", Material.DIAMOND_PICKAXE),
    INCOME("Income", Material.CLOCK),
    WAGE("Wage", Material.WRITABLE_BOOK),
    DAILY("Daily reward", Material.SUNFLOWER),
    INTEREST("Interest", Material.EXPERIENCE_BOTTLE),
    GAMBLE("Game of chance", Material.GOLD_BLOCK),
    LOTTERY("Lottery", Material.FILLED_MAP),
    AUCTION("Auction", Material.BELL),
    ADMIN("Staff adjustment", Material.COMMAND_BLOCK),
    PLUGIN("Another plugin", Material.REPEATER);

    private final String label;
    private final Material icon;

    TransactionKind(String label, Material icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public Material icon() {
        return icon;
    }

    /** Lenient read of a stored name; an unknown one becomes {@link #PLUGIN} rather than a lost line. */
    public static TransactionKind read(String stored) {
        for (TransactionKind kind : values()) {
            if (kind.name().equalsIgnoreCase(stored)) {
                return kind;
            }
        }
        return PLUGIN;
    }
}
