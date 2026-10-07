package de.raindancer.modules.essentials.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.rules.EnchantRule;
import de.raindancer.modules.essentials.screen.EnchantMenu;
import de.raindancer.modules.essentials.util.Enchantments;
import de.raindancer.modules.essentials.util.PermissionNodes;
import de.raindancer.modules.essentials.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /enchant <enchantment> [level] [player]} — level 1 to 255, past the vanilla maximum and onto
 * items it does not fit with the matching permissions; level 0 or {@code remove} takes it off;
 * {@code /enchant clear [player]} takes everything off. Bare {@code /enchant} opens {@link EnchantMenu}.
 *
 * <p>The level defaults to 1, as in vanilla's own command. A second word that is not a level is read as
 * the player, so {@code /enchant sharpness Steve} works.
 */
public final class EnchantCommand implements IEssentialsCommand {

    private static final List<Integer> JUMPS = List.of(25, 50, 100, 150, 200, 255);

    private final Supplier<EssentialsServices> services;

    public EnchantCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "enchants the item in your hand, beyond vanilla's limits if you may";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            if (sender instanceof Player player) {
                new EnchantMenu(live, player, null).open();
            } else {
                live.messages().send(sender, "essentials.usage", "usage",
                        "/enchant <enchantment> [level] [player]");
            }
            return;
        }
        if (args[0].equalsIgnoreCase("clear")) {
            if (args.length > 2) {
                live.messages().send(sender, "essentials.usage", "usage", "/enchant clear [player]");
                return;
            }
            targets(live, sender, args.length > 1 ? args[1] : null)
                    .forEach(target -> live.enchanting().clear(sender, target));
            return;
        }
        Optional<Enchantment> found = Enchantments.find(args[0]);
        if (found.isEmpty()) {
            live.messages().send(sender, "essentials.enchant.unknown", "enchantment", args[0]);
            return;
        }
        Optional<Integer> typedLevel = args.length > 1 ? EnchantRule.parseLevel(args[1]) : Optional.empty();
        int level = typedLevel.orElse(1);
        int playerAt = typedLevel.isPresent() ? 2 : 1;
        if (args.length > playerAt + 1) {
            live.messages().send(sender, "essentials.usage", "usage", "/enchant <enchantment> [level] [player]");
            return;
        }
        String player = args.length > playerAt ? args[playerAt] : null;
        targets(live, sender, player).forEach(target -> live.enchanting().apply(sender, target, found.get(), level));
    }

    /** Who it is aimed at: the named players, or the sender. Says why when that is nobody. */
    private static List<Player> targets(EssentialsServices live, CommandSender sender, String text) {
        if (text == null) {
            if (sender instanceof Player self) {
                return List.of(self);
            }
            live.messages().send(sender, "essentials.only-a-player");
            return List.of();
        }
        List<Player> found = PlayerTargets.resolve(live.server(), sender, text);
        if (found.isEmpty()) {
            live.messages().send(sender, "essentials.enchant.nobody-there", "player", text);
        }
        return found;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        CommandSender sender = source.getSender();
        boolean others = sender.hasPermission(PermissionNodes.ENCHANT_OTHERS);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.add("clear");
            options.addAll(Enchantments.keys());
            String typed = args.length == 0 ? "" : Enchantments.normalise(args[0]);
            return options.stream().filter(option -> option.startsWith(typed)).toList();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args[0].equalsIgnoreCase("clear")) {
            if (args.length == 2 && others) {
                options.addAll(players(live, sender, args[1]));
            }
        } else if (args.length == 2) {
            options.addAll(levels(sender, args[0]));
            if (others) {
                options.addAll(players(live, sender, args[1]));
            }
        } else if (args.length == 3 && others && EnchantRule.parseLevel(args[1]).isPresent()) {
            options.addAll(players(live, sender, args[2]));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    private static List<String> levels(CommandSender sender, String enchantmentTyped) {
        int vanilla = Enchantments.find(enchantmentTyped).map(Enchantment::getMaxLevel).orElse(5);
        int ceiling = EnchantRule.ceiling(vanilla, sender.hasPermission(PermissionNodes.ENCHANT_BEYOND_MAX));
        List<String> levels = new ArrayList<>();
        for (int level = 1; level <= Math.min(ceiling, 10); level++) {
            levels.add(Integer.toString(level));
        }
        JUMPS.stream().filter(jump -> jump <= ceiling).map(String::valueOf).forEach(levels::add);
        levels.add("remove");
        return levels;
    }

    private static List<String> players(EssentialsServices live, CommandSender sender, String typed) {
        return PlayerTargets.suggest(live.server(), typed, Players.visibleTo(live.core().vanish(),
                sender instanceof Player viewer ? viewer.getUniqueId() : null));
    }

    @Override
    public String permission() {
        return PermissionNodes.ENCHANT;
    }
}
