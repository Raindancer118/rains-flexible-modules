package de.raindancer.modules.xpbottle.command;

import de.raindancer.modules.xpbottle.XpBottleServices;
import de.raindancer.modules.xpbottle.service.BottleForge;
import de.raindancer.modules.xpbottle.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /xpbottle} opens the page; {@code /xpbottle give <player> [tier]} conjures a siphon bottle
 * for somebody.
 *
 * <h2>Why {@code give} is the only subcommand</h2>
 * What earns one is taking an argument a menu cannot ask for. Giving a bottle to <em>another</em>
 * player is exactly that — the page can hand one to whoever is looking at it and no further. Filling
 * and pouring are gestures on an item and are not commands at all.
 */
public final class XpBottleCommand implements IXpBottleCommand {

    private final Supplier<XpBottleServices> services;

    public XpBottleCommand(Supplier<XpBottleServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        XpBottleServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            give(live, sender, args);
            return;
        }
        if (!(sender instanceof Player viewer)) {
            live.messages().send(sender, "xpbottle.only-a-player");
            return;
        }
        if (!viewer.hasPermission(PermissionNodes.MENU)) {
            live.messages().send(sender, "xpbottle.menu.not-allowed");
            return;
        }
        live.screens().root(viewer);
    }

    private void give(XpBottleServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.GIVE)) {
            live.messages().send(sender, "xpbottle.give.not-allowed");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "xpbottle.usage");
            return;
        }
        Player target = live.server().getPlayerExact(args[1]);
        if (target == null) {
            live.messages().send(sender, "xpbottle.give.not-online", "player", args[1]);
            return;
        }
        int highest = live.config().highestTierClamped();
        int tier = 1;
        if (args.length >= 3) {
            try {
                tier = Integer.parseInt(args[2]);
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "xpbottle.give.bad-tier", "tier", args[2],
                        "highest", String.valueOf(highest));
                return;
            }
            if (tier < 1 || tier > highest) {
                live.messages().send(sender, "xpbottle.give.bad-tier", "tier", args[2],
                        "highest", String.valueOf(highest));
                return;
            }
        }

        ItemStack bottle = live.forge().siphon(tier);
        target.getInventory().addItem(bottle).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        live.messages().send(sender, "xpbottle.give.given",
                "tier", BottleForge.numeral(tier), "player", target.getName());
        if (!target.equals(sender)) {
            live.messages().send(target, "xpbottle.give.received",
                    "tier", BottleForge.numeral(tier));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (!source.getSender().hasPermission(PermissionNodes.GIVE)) {
            return List.of();
        }
        if (args.length <= 1) {
            String started = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return "give".startsWith(started) ? List.of("give") : List.of();
        }
        if (!args[0].equalsIgnoreCase("give")) {
            return List.of();
        }
        if (args.length == 2) {
            String started = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player online : services.get().server().getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(started)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        if (args.length == 3) {
            List<String> tiers = new ArrayList<>();
            for (int tier = 1; tier <= services.get().config().highestTierClamped(); tier++) {
                tiers.add(String.valueOf(tier));
            }
            return tiers;
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "opening the XP bottle page, and giving somebody a siphon bottle";
    }
}
