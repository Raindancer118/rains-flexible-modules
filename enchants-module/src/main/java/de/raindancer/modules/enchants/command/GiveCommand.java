package de.raindancer.modules.enchants.command;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.EnchantDef;
import de.raindancer.core.content.enchant.EnchantShards;
import de.raindancer.core.platform.util.Inventories;
import de.raindancer.modules.enchants.EnchantsDefinitions;
import de.raindancer.modules.enchants.EnchantsServices;
import de.raindancer.modules.enchants.rules.GiveRule;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The game's {@code /give}, knowing Rain's enchantments too: {@code /give <player> telekinesis_shard 64},
 * {@code /give <player> flight_book} (highest level), {@code /give <player> flight_book_3 2}. Any other item goes
 * on to {@code minecraft:give} exactly as typed, components and all.
 */
public final class GiveCommand implements BasicCommand {

    public static final String PERMISSION = "minecraft.command.give";
    private static final int MOST = 6400;
    private static final int MOST_SUGGESTED = 60;

    private final Supplier<EnchantsServices> services;
    private final GiveRule rule;

    public GiveCommand(Supplier<EnchantsServices> services) {
        this.services = services;
        Map<String, Integer> levels = new LinkedHashMap<>();
        for (EnchantDef def : EnchantsDefinitions.all()) {
            levels.put(def.key(), def.maxLevel());
        }
        this.rule = new GiveRule(levels);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Optional<GiveRule.Request> ours = args.length >= 2 ? rule.parse(args[1]) : Optional.empty();
        if (ours.isEmpty() || CustomEnchantments.find(ours.get().enchantment()).isEmpty()) {
            Bukkit.dispatchCommand(sender, "minecraft:give " + String.join(" ", args));
            return;
        }
        EnchantsServices live = services.get();
        int count = 1;
        if (args.length >= 3) {
            try {
                count = Integer.parseInt(args[2]);
            } catch (NumberFormatException notANumber) {
                count = 0;
            }
        }
        if (count < 1 || count > MOST || args.length > 3) {
            live.messages().send(sender, "enchants.give-usage");
            return;
        }
        List<Player> targets = targets(sender, args[0]);
        if (targets.isEmpty()) {
            live.messages().send(sender, "enchants.not-online", "player", args[0]);
            return;
        }
        GiveRule.Request request = ours.get();
        for (Player target : targets) {
            Inventories.giveOrDrop(target, stacks(request, count));
        }
        live.messages().send(sender, "enchants.give-done", "count", count, "item", args[1].toLowerCase(Locale.ROOT),
                "who", targets.size() == 1 ? targets.get(0).getName() : targets.size() + " players");
    }

    private static List<ItemStack> stacks(GiveRule.Request request, int count) {
        List<ItemStack> stacks = new ArrayList<>();
        if (request.shard()) {
            for (int left = count; left > 0; left -= 64) {
                stacks.add(EnchantShards.of(request.enchantment(), Math.min(64, left)));
            }
        } else {
            for (int i = 0; i < count; i++) {
                stacks.add(CustomEnchantments.book(request.enchantment(), request.level()));
            }
        }
        return stacks;
    }

    private static List<Player> targets(CommandSender sender, String typed) {
        if (typed.startsWith("@")) {
            try {
                List<Player> players = new ArrayList<>();
                for (Entity entity : Bukkit.selectEntities(sender, typed)) {
                    if (entity instanceof Player player) {
                        players.add(player);
                    }
                }
                return players;
            } catch (IllegalArgumentException badSelector) {
                return List.of();
            }
        }
        Player player = Bukkit.getPlayerExact(typed);
        return player == null ? List.of() : List.of(player);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("@a", "@p", "@r", "@s"));
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
        } else if (args.length == 2) {
            options.addAll(rule.names());
            Registry.ITEM.forEach(type -> options.add(type.getKey().asString()));
        } else if (args.length == 3) {
            options.addAll(List.of("1", "16", "64"));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)
                || option.toLowerCase(Locale.ROOT).startsWith("minecraft:" + typed)
                || option.toLowerCase(Locale.ROOT).startsWith("rainsenchants:" + typed)).limit(MOST_SUGGESTED).toList();
    }

    @Override
    public String permission() {
        return PERMISSION;
    }
}
