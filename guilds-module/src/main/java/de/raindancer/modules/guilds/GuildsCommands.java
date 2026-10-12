package de.raindancer.modules.guilds;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.guilds.command.GuildCommand;

import java.util.List;

/**
 * Declared at bootstrap, filled in when the module starts — Paper registers commands before any plugin is
 * enabled, so the command points at {@link #require} until then.
 */
public final class GuildsCommands {

    private static volatile GuildsServices services;

    private GuildsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("guild", "Your guild: members, ranks, bank, home, allies and chat",
                                new GuildCommand(GuildsCommands::require))
                        .aliased("g", "guilds", "clan")
                        .taking("(nothing) — the guild page", "create <name> <tag> · info [guild] · list · level · contracts · perks · vault · crown · prestige confirm",
                                "invite · accept · decline · join · leave · kick · promote · demote · rank · leader",
                                "c · oc · ac <message> — guild, officer and ally chat",
                                "home · sethome · deposit · withdraw <amount>",
                                "ally · unally · rival · unrival <guild>",
                                "rename · tag · motd · joining · disband",
                                "admin disband|rename|xp|give|leader <guild> … · admin boost <percent> <time>|off — staff")
                        .needing("rainsguilds.use"));
    }

    static void ready(GuildsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static GuildsServices require() {
        GuildsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the guilds module is not running");
        }
        return live;
    }
}
