package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.service.ShopService;

import de.raindancer.core.ui.choose.Category;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /shop [category | item | search text]} and {@code /sell [hand | all | enchantments]}. */
public final class ShopCommand extends EconomyCommand {

    private final boolean selling;

    public ShopCommand(Supplier<EconomyServices> services, boolean selling) {
        super(services);
        this.selling = selling;
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            if (selling) {
                if (!allowed(live, sender, PermissionNodes.SELL)) {
                    return;
                }
                if (!live.config().sellingEnabled()) {
                    live.messages().send(player, "economy.shop.selling-off");
                    return;
                }
                String how = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                switch (how) {
                    case "hand" -> live.shop().sellHand(player);
                    case "enchantments", "enchants" -> live.shop().sellEnchantments(player);
                    case "all" -> live.shop().sellEverything(player);
                    default -> live.screens().sell(player);
                }
                return;
            }
            if (!allowed(live, sender, PermissionNodes.SHOP)) {
                return;
            }
            if (!live.config().shopEnabled()) {
                live.messages().send(player, "economy.shop.off");
                return;
            }
            if (args.length == 0) {
                live.screens().shop(player);
                return;
            }
            boolean searching = args[0].equalsIgnoreCase("search");
            String typed = rest(args, searching ? 1 : 0);
            // "search" asked for the search, so it gets the search — /shop search diamond lists every diamond thing
            // rather than jumping to the one item called that.
            if (searching) {
                if (typed.isBlank()) {
                    live.screens().shop(player);
                } else {
                    live.screens().shopSearch(player, typed);
                }
                return;
            }
            for (Category category : Category.values()) {
                if (key(category).equalsIgnoreCase(typed.replace(' ', '-'))) {
                    live.screens().shopCategory(player, category);
                    return;
                }
            }
            Material material = Material.matchMaterial(typed.replace(' ', '_'));
            if (material != null && material.isItem() && live.shop().tag(material).tradable()) {
                live.screens().trade(player, material);
                return;
            }
            live.screens().shopSearch(player, typed);
        });
    }

    /**
     * On a server that is not the bank: the bank's shop, where the owner switched it on here — items and eggs bought
     * and sold with the bank's prices; elsewhere the usual "that is on the bank's server".
     */
    @Override
    public void executeClient(de.raindancer.modules.economy.EconomyClient client, CommandSourceStack source,
                              String[] args) {
        if (client.shop().isEmpty() || !(source.getSender() instanceof org.bukkit.entity.Player player)) {
            client.refuse(source.getSender());
            return;
        }
        if (!player.hasPermission(selling ? PermissionNodes.SELL : PermissionNodes.SHOP)) {
            client.messages().send(player, "economy.not-allowed");
            return;
        }
        var shop = client.shop().get();
        if (selling) {
            switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
                case "hand" -> shop.sellHand(player);
                case "all" -> shop.sellAll(player);
                default -> shop.catalogue(player, lines -> new de.raindancer.modules.economy.screen.ClientItemsMenu(
                        shop, client.brand(), player, null, "Sell", lines.stream().filter(line -> line.sellable()
                        && Material.matchMaterial(line.material()) != null
                        && ShopService.carryingIn(player, Material.matchMaterial(line.material())) > 0).toList()).open());
            }
            return;
        }
        if (args.length > 0) {
            // A search, typed with "search" or without: the same words, typos and levels as the bank's own.
            String typed = rest(args, args[0].equalsIgnoreCase("search") ? 1 : 0);
            var rule = new de.raindancer.modules.economy.rules.ShopSearchRule();
            shop.catalogue(player, lines -> new de.raindancer.modules.economy.screen.ClientItemsMenu(shop, client.brand(),
                    player, null, "\"" + typed + "\"", lines.stream()
                    .filter(line -> rule.score(typed, de.raindancer.core.ui.choose.Catalogue.readable(line.material())).isPresent())
                    .sorted(java.util.Comparator.comparingInt(line -> rule.score(typed,
                            de.raindancer.core.ui.choose.Catalogue.readable(line.material())).orElse(9)))
                    .toList()).open());
            return;
        }
        shop.catalogue(player, lines -> new de.raindancer.modules.economy.screen.ClientShopMenu(shop, client.brand(),
                player, null, lines).open());
    }

    @Override
    public @NotNull Collection<String> suggestClient(de.raindancer.modules.economy.EconomyClient client,
                                                     @NotNull CommandSourceStack source, String @NotNull [] args) {
        return client.shop().isPresent() && selling && args.length <= 1
                ? starting(args.length == 0 ? "" : args[0], List.of("hand", "all")) : List.of();
    }

    private static String key(Category category) {
        return category.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0];
        if (selling) {
            return starting(typed, List.of("hand", "all", "enchantments"));
        }
        List<String> options = new ArrayList<>(List.of("search"));
        for (Category category : Category.values()) {
            options.add(key(category));
        }
        return starting(typed, options);
    }

    @Override
    public String describe() {
        return selling ? "selling to the shop" : "opening the shop, a drawer of it, an item, or a search";
    }
}
