package de.raindancer.modules.twitch;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.twitch.command.TwitchCommand;

import java.util.List;

/** What this module declares at bootstrap, and how it is filled in once the module runs. */
public final class TwitchCommands {

    private static volatile TwitchServices services;

    private TwitchCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(ModuleCommand.of("twitch", "The Twitch live announcements: status and tests",
                        new TwitchCommand(TwitchCommands::require))
                .taking("status | test live|ended | reload"));
    }

    static void ready(TwitchServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    private static TwitchServices require() {
        TwitchServices live = services;
        if (live == null) {
            throw new IllegalStateException("the twitch module is not running");
        }
        return live;
    }
}
