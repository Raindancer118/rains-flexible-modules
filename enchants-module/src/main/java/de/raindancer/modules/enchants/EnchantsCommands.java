package de.raindancer.modules.enchants;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.enchants.command.EnchantsCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any plugin
 * is enabled, so the command points at {@link #require} until then.
 */
public final class EnchantsCommands {

    private static volatile EnchantsServices services;

    private EnchantsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(ModuleCommand.of("enchants", "Lists Rain's enchantments and hands out books",
                new EnchantsCommand(EnchantsCommands::require))
                .taking("list", "give <player> <enchantment> [level] — staff"),
                ModuleCommand.of("give", "The game's /give, knowing Rain's shards and books too",
                        new de.raindancer.modules.enchants.command.GiveCommand(EnchantsCommands::require))
                        .taking("<player> <item> [count] — also telekinesis_shard, flight_book, flight_book_3")
                        .needing(de.raindancer.modules.enchants.command.GiveCommand.PERMISSION),
                ModuleCommand.of("autopilot", "Wearing an Autopilot elytra: pick a place or a player and watch yourself fly there",
                        new de.raindancer.modules.enchants.command.AutopilotCommand(EnchantsCommands::require)));
    }

    static void ready(EnchantsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static EnchantsServices require() {
        EnchantsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the enchants module is not running");
        }
        return live;
    }
}
