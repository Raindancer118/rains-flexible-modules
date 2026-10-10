package de.raindancer.modules.economy.command;

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
            String typed = rest(args, args[0].equalsIgnoreCase("search") ? 1 : 0);
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
