package de.raindancer.modules.warp.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.TaggedItems;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.warp.WarpSettings;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Warp tokens: a nether star that sets one warp, for somebody who could not otherwise set any.
 *
 * <p>Core's custom-item machinery carries it — the definition, the glow, the right click — and Core
 * keeps it out of every crafting grid, so a token cannot quietly become half a beacon. Right-clicking
 * asks for a name; the token is taken only once the warp exists, so a taken name or a full server
 * costs nothing. That is also why the ability always declines: Core would otherwise spend the item on
 * the click, before anybody had typed anything.
 */
public final class WarpTokens implements IWarpService {

    public static final String PLUGIN = "warps";
    public static final String ID = "token";
    public static final String KEY = PLUGIN + ":" + ID;

    private static final Duration TO_ANSWER = Duration.ofSeconds(60);

    private final WarpAdminService admin;
    private final ItemFactory factory;
    private final Messages messages;
    /** The server's definitions, once registered — an owner may have re-skinned the token there. */
    private volatile CustomItems items;

    public WarpTokens(WarpAdminService admin, ItemFactory factory, Messages messages) {
        this.admin = admin;
        this.factory = factory;
        this.messages = messages;
    }

    @Override
    public void settings(WarpSettings fresh) {
        // Nothing here reads a setting: the limits a token works around are the admin service's.
    }

    /** What a token is. An owner may re-skin it in Core's item menu; that edit is kept. */
    public static CustomItem definition() {
        return CustomItem.builder(PLUGIN, ID)
                .material(Material.NETHER_STAR)
                .name("<gradient:#FFE29A:#FF9BD5:#9AD8FF><bold>Warp Token</bold></gradient>")
                .lore(List.of(
                        "<gray>Sets one warp of your own,",
                        "<gray>right where you stand.",
                        "",
                        "<white>Right-click <gray>and type its name.",
                        "<dark_gray>Only used up once the warp exists."))
                .glowing(true)
                .ability(ID)
                .notAnIngredient()
                .build();
    }

    /**
     * Tells Core about the item and what clicking it does.
     *
     * @param prompts how to ask somebody for a name
     */
    public void register(CustomItems items, ItemAbilities abilities, Server server, ChatPrompts prompts) {
        this.items = items;
        items.defineIfAbsent(definition());
        abilities.register(ItemAbility.builder(PLUGIN, ID)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Set one warp of your own where you stand")
                .attempts(use -> {
                    Player player = server.getPlayer(use.player());
                    if (player != null) {
                        askForAName(player, prompts);
                    }
                    // Declined, always: the token is paid in redeem(), once the warp exists.
                    return false;
                })
                .build());
    }

    private void askForAName(Player player, ChatPrompts prompts) {
        boolean asking = prompts.ask(player.getUniqueId(), "warps", TO_ANSWER,
                answer -> redeem(player, answer),
                () -> messages.send(player, "warps.token.cancelled"));
        if (!asking) {
            messages.send(player, "warps.busy");
            return;
        }
        messages.send(player, "warps.token.ask-name");
    }

    /**
     * Sets the warp and takes one token for it — or, when either cannot happen, neither does.
     *
     * @return whether a warp was made
     */
    public boolean redeem(Player player, String name) {
        Optional<ItemStack> held = tokenIn(player);
        if (held.isEmpty()) {
            messages.send(player, "warps.token.gone");
            return false;
        }
        if (admin.create(player, name, true).isEmpty()) {
            // Already said why — a taken name, the server's ceiling. The token stays theirs.
            return false;
        }
        ItemStack stack = held.get();
        stack.setAmount(stack.getAmount() - 1);
        messages.send(player, "warps.token.used");
        return true;
    }

    private Optional<ItemStack> tokenIn(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && factory.keyOf(stack).map(KEY::equalsIgnoreCase).orElse(false)) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    /** Hands {@code amount} tokens to somebody; what does not fit is dropped at their feet. */
    public void give(CommandSender giver, Player to, int amount) {
        CustomItems registered = items;
        CustomItem token = registered == null ? definition() : registered.byKey(KEY).orElse(definition());
        Optional<ItemStack> made = factory.create(token, amount);
        if (made.isEmpty()) {
            messages.send(giver, "warps.token.cannot-make");
            return;
        }
        TaggedItems.handTo(to, made.get());
        messages.send(giver, "warps.token.given", "amount", amount, "player", to.getName());
        if (giver != to) {
            messages.send(to, "warps.token.received", "amount", amount);
        }
    }

    @Override
    public String describe() {
        return "warp tokens: one warp of your own, bought with a nether star";
    }
}
