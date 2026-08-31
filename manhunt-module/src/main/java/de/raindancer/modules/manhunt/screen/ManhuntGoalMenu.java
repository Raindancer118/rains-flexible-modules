package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.ManhuntSettings;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Seven curated vanilla advancements, one per column, as the Runners' win goal — the ADVANCEMENT half
 * of {@link ManhuntSettings#runnerWin()} pointed at whichever key {@link ManhuntSettings#runnerAdvancementKey()}
 * holds. See {@code ManhuntAchievementsMenu}'s own class javadoc for why a curated seven rather than
 * every advancement the game has: a full vanilla tree in one band is a wall of icons, not a choice.
 * {@code /manhunt goal <key>}, with full tab-completion over every advancement a server knows about
 * (curated or not), is the "everything else" answer for this field — not the generic {@code /settings}
 * chat-typing flow, which still works but is no longer the intended path here.
 *
 * <h2>Real icons, not guessed ones</h2>
 * Each button resolves its {@link Advancement} live via {@link Bukkit#getAdvancement(NamespacedKey)}
 * and reads {@link Advancement#getDisplay()} for the vanilla {@link ItemStack} icon, title and
 * description a client already renders for that advancement — the same shape
 * {@code RunnerAdvancementEndCondition} already uses to resolve the configured key at runtime. A
 * missing advancement or a missing display (recipe unlocks have none) simply drops that column rather
 * than showing a wrong or placeholder icon — a server on a stripped-down datapack still gets a working,
 * if shorter, menu instead of a crash.
 *
 * <p>Picking a goal also switches {@link ManhuntSettings#runnerWin()} to {@code ADVANCEMENT} — the
 * obviously-intended behaviour of choosing one, even though nobody has to ask for it in words.
 */
public final class ManhuntGoalMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** The seven curated Runner-win advancements, in the order they appear in the menu. */
    public static final List<String> CURATED_KEYS = List.of(
            "minecraft:end/kill_dragon",
            "minecraft:end/elytra",
            "minecraft:end/root",
            "minecraft:nether/root",
            "minecraft:husbandry/balanced_diet",
            "minecraft:adventure/kill_all_mobs",
            "minecraft:nether/all_effects");

    private final ManhuntServices services;

    public ManhuntGoalMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_aqua>The Runners' Goal");
    }

    @Override
    public String breadcrumb() {
        return "Goal";
    }

    @Override
    protected void render() {
        String configuredKey = services.config().runnerAdvancementKey();

        int column = 1;
        for (String key : CURATED_KEYS) {
            Advancement advancement = resolveAdvancement(key);
            AdvancementDisplay display = advancement == null ? null : advancement.getDisplay();
            if (display == null) {
                continue;   // datapack does not have it, or it has no display (e.g. a recipe unlock)
            }
            band(MenuLayout.WHO, column++, iconFor(key, display, configuredKey), click -> select(key));
        }
    }

    private void select(String key) {
        services.store().set("runner-advancement-key", key);
        services.store().set("runner-win", "ADVANCEMENT");
        services.store().save();
        refresh();
    }

    private ItemStack iconFor(String key, AdvancementDisplay display, String configuredKey) {
        List<String> lore = new ArrayList<>();
        lore.add(PLAIN.serialize(display.description()));
        if (isConfigured(key, configuredKey)) {
            lore.add("<green>Current goal");
        }
        return styledIcon(display.icon(), PLAIN.serialize(display.title()), lore);
    }

    private static boolean isConfigured(String key, String configuredKey) {
        return configuredKey != null && key.equalsIgnoreCase(configuredKey.trim());
    }

    /** {@code Bukkit.getAdvancement}, guarded by a key that may not even parse as one. */
    public static Advancement resolveAdvancement(String key) {
        if (key == null) {
            return null;
        }
        NamespacedKey namespacedKey = NamespacedKey.fromString(key.trim());
        return namespacedKey == null ? null : Bukkit.getAdvancement(namespacedKey);
    }

    /**
     * A vanilla {@link ItemStack} — icon, material, model data and all — restyled with this codebase's
     * own item-name/lore convention ({@link Icons#name(String)} / {@link Icons#loreLine(String)})
     * rather than the game's own (uncoloured) advancement text, so a button here reads like every other
     * button in these menus.
     */
    public static ItemStack styledIcon(ItemStack base, String name, List<String> lore) {
        ItemStack icon = base.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        meta.displayName(Icons.name(name));
        meta.lore(lore.stream().map(Icons::loreLine).toList());
        icon.setItemMeta(meta);
        return icon;
    }

    public String describe() {
        return "the seven curated advancements a server can pick as the Runners' win goal";
    }
}
