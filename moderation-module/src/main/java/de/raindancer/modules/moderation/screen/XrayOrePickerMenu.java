package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which ores count as valuable for x-ray detection — a grid to click through instead of a material
 * list typed into chat one comma at a time.
 *
 * <h2>Why a fixed, curated list rather than every material on the server</h2>
 * {@code ItemChooser} exists for exactly the opposite job — picking one thing out of everything a
 * server can hold — and using it here would put "which ores matter for x-ray" behind the same three
 * clicks through drawers and families that finding a single dye colour needs, for a decision that is
 * always going to be one of a couple of dozen actual ores. This is deliberately the short list, laid
 * out flat, because that is the whole reason it is faster to use than typing.
 *
 * <h2>Where a click actually goes</h2>
 * Straight through {@code SettingsRegistry}, the same path the generic settings menu uses for every
 * other setting — not a shortcut around it. A toggle here and a value typed at {@code /mod config}
 * write the exact same key, so neither one can leave the other looking at a stale answer.
 */
public final class XrayOrePickerMenu extends ModerationList<Material> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The setting this page is a friendlier way of editing. See {@code ModerationSettings#xrayOres}. */
    private static final String SETTING_KEY = "xray.ores";

    /**
     * Every ore worth offering a toggle for.
     *
     * <p>Deliberately not "every material Bukkit calls an ore" — nether quartz and ancient debris are
     * here because a server watching for x-ray cares about them exactly as much as diamond, and coal
     * is here because some owners want it watched too despite how common it is. What is left out —
     * netherite scrap, raw ore drops that are not the block itself — is not a block anybody's pickaxe
     * hits, so a flag for it would toggle something that can never actually happen.
     */
    private static final List<Material> ORES = List.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS);

    public XrayOrePickerMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Watched ores");
    }

    @Override
    public String breadcrumb() {
        return "Watched ores";
    }

    @Override
    protected List<Material> entries() {
        return ORES;
    }

    @Override
    protected ItemStack icon(Material ore) {
        boolean watched = currentlyWatched().contains(ore.name());
        List<String> lore = new ArrayList<>();
        lore.add(watched ? "<green>Watched — counts towards the ratio and the review screen."
                : "<dark_gray>Not watched.");
        lore.add("");
        lore.add(may(ModerationPermission.CONFIG)
                ? "<dark_gray>Click to " + (watched ? "stop watching it." : "start watching it.")
                : "<dark_gray>An admin's decision.");
        return Icons.of(ore, (watched ? "<green>✔ " : "<gray>") + prettyName(ore), lore);
    }

    @Override
    protected void onClick(Material ore, InventoryClickEvent event) {
        if (!may(ModerationPermission.CONFIG)) {
            tell("moderation.no-permission");
            return;
        }
        Set<String> watched = new LinkedHashSet<>(currentlyWatched());
        if (!watched.remove(ore.name())) {
            watched.add(ore.name());
        }
        services().settingsNavigation().registry().set(SETTING_KEY, String.join(",", watched));
        services().settingsNavigation().registry().saveAll();
        refresh();
    }

    private List<String> currentlyWatched() {
        return services().config().xrayOres();
    }

    private static String prettyName(Material material) {
        String words = material.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Click any ore to watch it, or stop watching one already lit up.",
                "<dark_gray>The same setting as \"Which blocks count as valuable\" in /mod config.");
    }

    @Override
    public String describe() {
        return "which ores count as valuable for x-ray detection, as a grid instead of typed text";
    }
}
