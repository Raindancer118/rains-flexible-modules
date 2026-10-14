package de.raindancer.modules.enchants.command;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.EnchantDef;
import de.raindancer.modules.enchants.EnchantsDefinitions;
import de.raindancer.modules.enchants.EnchantsServices;
import de.raindancer.modules.enchants.util.PermissionNodes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code /enchants list} and {@code /enchants give <player> <enchantment> [level]}. */
public final class EnchantsCommand implements BasicCommand {

    private final Supplier<EnchantsServices> services;

    public EnchantsCommand(Supplier<EnchantsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EnchantsServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.equals("list")) {
            live.messages().send(sender, "enchants.list-header");
            for (EnchantDef def : EnchantsDefinitions.all()) {
                live.messages().send(sender, "enchants.list-line", "name", def.path(), "max", def.maxLevel(),
                        "gear", def.gear().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            return;
        }
        if (first.equals("shard")) {
            shard(live, sender, args);
            return;
        }
        if (!first.equals("give") || args.length < 3 || !sender.hasPermission(PermissionNodes.GIVE)) {
            live.messages().send(sender, "enchants.usage");
            return;
        }
        Player target = live.server().getPlayerExact(args[1]);
        if (target == null) {
            live.messages().send(sender, "enchants.not-online", "player", args[1]);
            return;
        }
        Optional<EnchantDef> def = EnchantsDefinitions.all().stream()
                .filter(each -> each.path().equals(args[2].toLowerCase(Locale.ROOT))).findFirst();
        if (def.isEmpty()) {
            live.messages().send(sender, "enchants.unknown", "name", args[2]);
            return;
        }
        int level = 1;
        if (args.length >= 4) {
            try {
                level = Integer.parseInt(args[3]);
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "enchants.bad-level", "max", def.get().maxLevel());
                return;
            }
        }
        if (level < 1 || level > def.get().maxLevel()) {
            live.messages().send(sender, "enchants.bad-level", "max", def.get().maxLevel());
            return;
        }
        ItemStack book = CustomEnchantments.book(def.get().key(), level);
        target.getInventory().addItem(book).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        live.messages().send(sender, "enchants.given", "player", target.getName(), "name", def.get().path(),
                "level", level);
    }

    private static boolean staffWord(String word) {
        return word.equalsIgnoreCase("give") || word.equalsIgnoreCase("shard");
    }

    /** {@code /enchants shard <player> <enchantment> [amount]} — for staff, events and tests. */
    private static void shard(EnchantsServices live, CommandSender sender, String[] args) {
        if (args.length < 3 || !sender.hasPermission(PermissionNodes.GIVE)) {
            live.messages().send(sender, "enchants.shard-usage");
            return;
        }
        Player target = live.server().getPlayerExact(args[1]);
        if (target == null) {
            live.messages().send(sender, "enchants.not-online", "player", args[1]);
            return;
        }
        Optional<EnchantDef> def = EnchantsDefinitions.all().stream()
                .filter(each -> each.path().equals(args[2].toLowerCase(Locale.ROOT))).findFirst();
        if (def.isEmpty()) {
            live.messages().send(sender, "enchants.unknown", "name", args[2]);
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(6400, Integer.parseInt(args[3])));
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "enchants.shard-usage");
                return;
            }
        }
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (int left = amount; left > 0; left -= 64) {
            stacks.add(de.raindancer.core.content.enchant.EnchantShards.of(def.get().key(), Math.min(64, left)));
        }
        de.raindancer.core.platform.util.Inventories.giveOrDrop(target, stacks);
        live.messages().send(sender, "enchants.shard-given", "player", target.getName(), "amount", amount,
                "name", def.get().path());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 0, 1 -> source.getSender().hasPermission(PermissionNodes.GIVE) ? List.of("list", "give", "shard")
                    : List.of("list");
            case 2 -> staffWord(args[0]) && source.getSender().hasPermission(PermissionNodes.GIVE)
                    ? services.get().server().getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
            case 3 -> staffWord(args[0])
                    ? EnchantsDefinitions.all().stream().map(EnchantDef::path).toList() : List.of();
            default -> List.of();
        };
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
