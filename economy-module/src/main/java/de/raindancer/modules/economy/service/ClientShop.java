package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Markup;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The bank's shop on a server that is not the bank: the price list and quotes are the bank's, and so is every booking.
 * This server only moves the items — handed over once the bank has charged, taken before it pays and given back when
 * it does not. Nothing is ever booked here.
 */
public final class ClientShop {

    /** Values the bank sends as rendered money: put in as markup, the rest as plain words. */
    private static final Set<String> MONEY = Set.of("amount", "balance", "left", "price");

    private final Plugin plugin;
    private final Messages messages;
    private final Effects effects;
    private final ShopWire.Client bank;
    private final java.util.function.Supplier<String> bankName;

    public ClientShop(Plugin plugin, Messages messages, Effects effects, ShopWire.Client bank,
                      java.util.function.Supplier<String> bankName) {
        this.plugin = plugin;
        this.messages = messages;
        this.effects = effects;
        this.bank = bank;
        this.bankName = bankName;
    }

    /** The price list, then {@code shown} on the player's own thread; "the bank is away" when it does not answer. */
    public void catalogue(Player player, Consumer<List<ShopDesk.Line>> shown) {
        bank.catalogue(player.getUniqueId()).thenAccept(lines -> onPlayer(player, () ->
                lines.ifPresentOrElse(shown, () -> away(player))));
    }

    public void quote(Player player, Material material, List<Integer> buy, List<Integer> sell,
                      Consumer<ShopDesk.Quote> shown) {
        bank.quote(player.getUniqueId(), material.name(), buy, sell).thenAccept(quote -> onPlayer(player, () ->
                quote.ifPresentOrElse(shown, () -> away(player))));
    }

    /**
     * Buys {@code count} for no more than {@code most}: the room is checked here first, the bank charges, and only
     * then are the items handed over — a full inventory costs nothing.
     */
    public CompletableFuture<Boolean> buy(Player player, Material material, int count, long most) {
        List<ItemStack> stacks = ShopService.sold(ShopService.stacksOf(material, Math.max(1, count)));
        if (!CashService.fits(player.getInventory(), stacks)) {
            messages.send(player, "economy.shop.no-room");
            effects.play(player.getUniqueId(), Cues.NO);
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        bank.buy(player.getUniqueId(), material.name(), count, most).thenAccept(settled -> onPlayer(player, () -> {
            if (settled.isEmpty()) {
                away(player);
                done.complete(false);
                return;
            }
            if (settled.get().ok()) {
                // Charged already: whatever no longer fits falls at their feet rather than vanishing paid-for.
                player.getInventory().addItem(stacks.toArray(ItemStack[]::new)).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            say(player, settled.get());
            done.complete(settled.get().ok());
        }));
        return done;
    }

    /** Sells up to {@code count} plain ones: taken first, paid by the bank, given back when it does not pay. */
    public CompletableFuture<Boolean> sell(Player player, Material material, int count) {
        int taken = ShopService.take(player.getInventory(), material, Math.max(1, count));
        if (taken == 0) {
            messages.send(player, "economy.shop.none-carried", "item",
                    de.raindancer.core.ui.choose.Catalogue.readable(material.name()));
            effects.play(player.getUniqueId(), Cues.NO);
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        bank.sell(player.getUniqueId(), material.name(), taken).thenAccept(settled -> onPlayer(player, () -> {
            boolean paid = settled.map(ShopDesk.Settled::ok).orElse(false);
            if (!paid) {
                ShopService.giveBack(player, material, taken);
            }
            settled.ifPresentOrElse(answer -> say(player, answer), () -> away(player));
            done.complete(paid);
        }));
        return done;
    }

    /** {@code /sell hand}. */
    public void sellHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !ShopService.sellableStack(held, held.getType())) {
            messages.send(player, "economy.shop.nothing-in-hand");
            return;
        }
        sell(player, held.getType(), held.getAmount());
    }

    /** {@code /sell all}: everything plain the bank buys, one item at a time, each booked on its own. */
    public void sellAll(Player player) {
        catalogue(player, lines -> {
            List<Material> selling = new ArrayList<>();
            for (ShopDesk.Line line : lines) {
                Material material = Material.matchMaterial(line.material());
                if (line.sellable() && material != null && ShopService.carryingIn(player, material) > 0) {
                    selling.add(material);
                }
            }
            if (selling.isEmpty()) {
                messages.send(player, "economy.shop.nothing-to-sell");
                return;
            }
            sellEach(player, selling, 0);
        });
    }

    private void sellEach(Player player, List<Material> selling, int index) {
        if (index >= selling.size() || !player.isOnline()) {
            return;
        }
        Material material = selling.get(index);
        sell(player, material, ShopService.carryingIn(player, material))
                .thenRun(() -> sellEach(player, selling, index + 1));
    }

    /** A line the bank wrote, said here: money values as the bank rendered them. */
    public void say(Player player, ShopDesk.Settled settled) {
        messages.send(player, settled.key(), values(settled.values()).toArray());
        effects.play(player.getUniqueId(), settled.ok() ? Cues.REWARD : Cues.NO);
    }

    static List<Object> values(List<String> pairs) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            out.add(pairs.get(i));
            out.add(MONEY.contains(pairs.get(i)) ? Markup.of(pairs.get(i + 1)) : pairs.get(i + 1));
        }
        return out;
    }

    private void away(Player player) {
        messages.send(player, "economy.client.unavailable", "server", bankName.get());
        effects.play(player.getUniqueId(), Cues.NO);
    }

    private void onPlayer(Player player, Runnable task) {
        Scheduling.entity(plugin, player, task);
    }

    /** What players know the bank's server as, for "the rest is on …". */
    public String bankName() {
        return bankName.get();
    }
}
