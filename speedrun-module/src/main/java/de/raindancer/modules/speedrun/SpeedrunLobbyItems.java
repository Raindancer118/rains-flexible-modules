package de.raindancer.modules.speedrun;

import de.raindancer.core.content.items.TaggedItems;
import de.raindancer.core.ui.menu.Icons;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Predicate;

/**
 * The two items a player in a ready lobby is handed: a compass that opens
 * {@link SpeedrunLobbyMenu}, and a block that starts a run.
 *
 * <p>Buttons, not gear: recognised by a tag rather than by material or name, and bound, so Core
 * keeps them out of chests, item frames and death drops — see Core's {@link TaggedItems}.
 */
public final class SpeedrunLobbyItems {

    /**
     * The name of the key these items are tagged with, without its namespace — which is whichever
     * plugin speedrun-module is running inside. Public and a compile-time constant so a module built
     * on this engine can recognise lobby items without a runtime dependency on this exact version.
     */
    public static final String MARKER_KEY = "speedrun-lobby-item";

    private static final String MENU = "menu";
    private static final String START = "start";
    private static final Predicate<String> EITHER = value -> MENU.equals(value) || START.equals(value);

    private final TaggedItems items;

    public SpeedrunLobbyItems(Plugin plugin) {
        this.items = TaggedItems.of(plugin, MARKER_KEY).onlyOn(Material.COMPASS, Material.LIME_CONCRETE).bound();
    }

    /** The compass that opens the lobby's menu. */
    public ItemStack menuCompass() {
        return tagged(Material.COMPASS, "<white>Speedrun menu",
                "<gray>Pick the advancement and death rule,", "<gray>or see the run in progress.",
                MENU);
    }

    /** The block that starts a run. */
    public ItemStack startBlock() {
        return tagged(Material.LIME_CONCRETE, "<green>Start the run",
                "<gray>Everybody currently in the lobby world", "<gray>races.",
                START);
    }

    /**
     * Gives the player the menu compass — and the start block too, when {@code withStartBlock} —
     * after taking any old lobby items. Never clears anything else: after a restart the lobby is READY
     * while people still stand in the run's world with the run's gear, and wiping that on join
     * destroyed a run that could otherwise be resumed. A real start clears inventories itself (the
     * start block's click and SpeedrunPreparation).
     *
     * <p>Handed out rather than greyed, unlike almost every other gated thing in these modules: a
     * block in an ordinary player's hotbar that refuses on click is a thing to try again every round,
     * and the lobby is the one place where what somebody is carrying <em>is</em> the interface. The
     * refusal on {@code SpeedrunLobbyListener.onInteract} still exists behind this, for a block that
     * was dropped, traded or kept from before a host changed the setting.
     */
    public void give(Player player, boolean withStartBlock) {
        take(player);
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : withStartBlock ? List.of(menuCompass(), startBlock()) : List.of(menuCompass())) {
            inventory.addItem(item);   // a full inventory goes without — nothing is pushed out for it
        }
    }

    /** Every lobby item off {@code inventory}, and nothing else. */
    public void take(PlayerInventory inventory) {
        items.removeAll(inventory, EITHER);
    }

    /** Every lobby item off {@code player}, the one on the cursor of an open window included. */
    public void take(Player player) {
        items.removeAll(player, EITHER);
    }

    public boolean isMenu(ItemStack stack) {
        return items.is(stack, MENU);
    }

    public boolean isStart(ItemStack stack) {
        return items.is(stack, START);
    }

    private ItemStack tagged(Material material, String name, String loreOne, String loreTwo, String tag) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Icons.name(name));
        meta.lore(List.of(Icons.loreLine(loreOne), Icons.loreLine(loreTwo)));
        stack.setItemMeta(meta);
        return items.tag(stack, tag);
    }
}
