package de.raindancer.modules.guilds.screen;

import de.raindancer.core.content.enchant.EnchantShards;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.service.ShardService;
import de.raindancer.modules.guilds.store.ShardBook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rain's enchantments and how far the guild is with each: click one to pay in the shards you carry, or shift-click a
 * stack of shards in your own inventory to pay in that enchantment's.
 */
public final class GuildEnchantsMenu extends PaginatedMenu<String> implements IGuildsScreen {

    private final GuildsServices services;

    public GuildEnchantsMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    private Optional<Guild> guild() {
        return services.guilds().guildOf(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return Component.text("Enchantments", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Enchantments";
    }

    @Override
    protected List<String> entries() {
        return guild().isPresent() ? services.shards().enchantments() : List.of();
    }

    @Override
    protected ItemStack icon(String enchantment) {
        String name = GuildMenu.escaped(ShardService.name(enchantment));
        ShardBook.Progress progress = guild().map(guild -> services.shards().progress(guild, enchantment))
                .orElse(ShardBook.Progress.NONE);
        if (progress.unlocked()) {
            return Icons.of(Material.ENCHANTED_BOOK, "<gold>" + name, "<green>Unlocked for your guild.",
                    "", "<gray>Buy its books at the shop,", "<gray>with its shards and a little money.");
        }
        int needed = services.shards().needed();
        int carried = EnchantShards.count(viewer.getInventory(), enchantment);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Paid in: <white>" + Math.min(progress.paid(), needed) + "/" + needed);
        lore.add("<gray>" + GuildMenu.bar((double) Math.min(progress.paid(), needed) / needed));
        lore.add("<gray>You carry <white>" + carried + "<gray> of its shards.");
        lore.add("");
        lore.add(carried > 0 ? "<gray>Click to pay them in." : "<dark_gray>Its shards drop in the shard world.");
        return Icons.of(Material.ECHO_SHARD, "<white>" + name, lore);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing to unlock", "<gray>Rain's enchantments are not on this server.");
    }

    @Override
    protected void onClick(String enchantment, InventoryClickEvent event) {
        if (EnchantShards.count(viewer.getInventory(), enchantment) == 0) {
            services.messages().send(viewer, "guilds.enchant-no-shards", "name", ShardService.name(enchantment));
            return;
        }
        // Says what it came to itself, once the guild's records are written.
        services.shards().payIn(viewer, enchantment, this::refresh);
    }

    /** Shards are read out of the player's own inventory: a shift-click on a stack pays in its enchantment. */
    @Override
    public boolean allowBottomInventoryInteraction() {
        return true;
    }

    @Override
    public void handleBottomClick(InventoryClickEvent event) {
        // Never the game's own move: nothing goes into this window, the shards go to the guild.
        event.setCancelled(true);
        if (!event.isShiftClick()) {
            return;
        }
        EnchantShards.enchantmentOf(event.getCurrentItem())
                .ifPresent(enchantment -> services.shards().payIn(viewer, enchantment, this::refresh));
    }

    @Override
    public String describe() {
        return "Rain's enchantments the guild unlocks with shards";
    }
}
