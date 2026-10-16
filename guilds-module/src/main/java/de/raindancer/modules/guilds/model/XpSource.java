package de.raindancer.modules.guilds.model;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Every way a guild earns experience. Also what a weekly contract asks for, so "mine 400 iron" and "mining earns
 * experience" are counted by the same event, once.
 */
public enum XpSource {

    PLAY("Playing", Material.CLOCK, "minute(s) played", true),
    MONSTER("Monsters", Material.ZOMBIE_HEAD, "monster(s) killed", true),
    BOSS("Bosses", Material.DRAGON_HEAD, "boss(es) killed", true),
    ADVANCEMENT("Advancements", Material.KNOWLEDGE_BOOK, "advancement(s)", true),
    MINE("Mining", Material.IRON_PICKAXE, "ore(s) mined", true),
    FISH("Fishing", Material.FISHING_ROD, "fish caught", true),
    BREED("Breeding", Material.WHEAT, "animal(s) bred", true),
    TRADE("Trading", Material.EMERALD, "trade(s)", true),
    HARVEST("Harvesting", Material.GOLDEN_HOE, "crop(s) harvested", true),
    ENCHANT("Enchanting", Material.ENCHANTING_TABLE, "enchanting level(s) spent", true),
    RAID("Raids", Material.OMINOUS_BOTTLE, "raid(s) won", true),
    RIVAL_KILL("Rivals defeated", Material.IRON_SWORD, "rival(s) defeated", true),
    DEPOSIT("Bank deposits", Material.GOLD_NUGGET, "paid in", true),
    CONTRACT("Weekly contracts", Material.FILLED_MAP, "contract(s)", false),
    STAFF("Staff", Material.COMMAND_BLOCK, "", false);

    private final String title;
    private final Material icon;
    private final String unit;
    private final boolean capped;

    XpSource(String title, Material icon, String unit, boolean capped) {
        this.title = title;
        this.icon = icon;
        this.unit = unit;
        this.capped = capped;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public String unit() {
        return unit;
    }

    /**
     * Whether size damping and multipliers apply. A contract's reward is a lump the guild
     * earned together, and staff mean exactly the number they typed.
     */
    public boolean isCapped() {
        return capped;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Optional<XpSource> named(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        for (XpSource source : values()) {
            if (source.key().equalsIgnoreCase(typed.strip()) || source.name().equalsIgnoreCase(typed.strip())) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }
}
