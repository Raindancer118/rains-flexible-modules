package de.raindancer.modules.twitch.command;

import de.raindancer.modules.twitch.TwitchServices;
import de.raindancer.modules.twitch.model.Observation;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.service.StreamParser;
import de.raindancer.modules.twitch.service.TwitchPoller;
import de.raindancer.modules.twitch.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /twitch status}, {@code /twitch test live|ended}, {@code /twitch reload}. */
public final class TwitchCommand implements ITwitchCommand {

    private final Supplier<TwitchServices> services;

    public TwitchCommand(Supplier<TwitchServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        TwitchServices live = services.get();
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "twitch.not-allowed");
            return;
        }
        String first = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "status" -> status(live, sender);
            case "test" -> {
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                boolean everybody = args.length > 2 && args[2].equalsIgnoreCase("all");
                StreamEvent event = what.equals("live") ? new StreamEvent.WentLive(live.announcer().sample())
                        : what.equals("ended") || what.equals("end")
                        ? new StreamEvent.Ended(live.announcer().sample(), live.announcer().sampleLength()) : null;
                if (event == null || (!everybody && !(sender instanceof Player))) {
                    live.messages().send(sender, "twitch.usage");
                } else if (everybody) {
                    live.announcer().announce(event);
                } else {
                    live.announcer().preview(event, (Player) sender);
                }
            }
            case "reload" -> {
                live.reloadLogin().run();
                live.messages().send(sender, "twitch.reloaded");
            }
            default -> live.messages().send(sender, "twitch.usage");
        }
    }

    private void status(TwitchServices live, CommandSender sender) {
        TwitchPoller poller = live.poller().get();
        String channel = live.settings().get().channelClean();
        if (poller == null) {
            String holder = live.role().holder().orElse("");
            if (holder.isEmpty()) {
                live.messages().send(sender, "twitch.status.listening", "channel", channel);
            } else {
                live.messages().send(sender, "twitch.status.elsewhere", "channel", channel, "server", holder);
            }
            return;
        }
        Observation last = poller.last();
        String seen = last instanceof Observation.Live running
                ? "live: " + running.stream().title() + " (" + StreamParser.spoken(
                        Duration.between(running.stream().startedAt(), java.time.Instant.now())) + ")"
                : last == Observation.OFFLINE ? "offline" : "not known yet";
        live.messages().send(sender, "twitch.status.watching", "channel", channel, "seen", seen);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (!source.getSender().hasPermission(PermissionNodes.ADMIN)) {
            return List.of();
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            return List.of("status", "test", "reload").stream().filter(option -> option.startsWith(last)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return List.of("live", "ended").stream().filter(option -> option.startsWith(last)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test")) {
            return List.of("all").stream().filter(option -> option.startsWith(last)).toList();
        }
        return List.of();
    }

    @Override
    public String describe() {
        return "the Twitch watch: its status, test announcements, reading the login again";
    }
}
