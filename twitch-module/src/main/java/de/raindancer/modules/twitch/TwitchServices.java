package de.raindancer.modules.twitch;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.twitch.service.Announcer;
import de.raindancer.modules.twitch.service.TwitchPoller;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Everything this module has built, carried to its command. The poller is null on a server with no Twitch login. */
public record TwitchServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        RainsCore core,
        Supplier<TwitchSettings> settings,
        Announcer announcer,
        java.util.function.Supplier<TwitchPoller> poller,
        Runnable reloadLogin) {
}
