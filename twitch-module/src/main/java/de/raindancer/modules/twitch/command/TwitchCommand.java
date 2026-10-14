package de.raindancer.modules.twitch.command;

import de.raindancer.modules.twitch.TwitchServices;
import de.raindancer.modules.twitch.model.Observation;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.service.StreamParser;
import de.raindancer.modules.twitch.service.TwitchLoginStore;
import de.raindancer.modules.twitch.service.TwitchPoller;
import de.raindancer.modules.twitch.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /twitch status}, {@code test live|ended}, {@code reload}, and, from the console only, {@code login} and {@code logout}. */
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
            case "status" -> {
                status(live, sender);
                live.messages().send(sender, "twitch.status.login." + live.logins().source().name().toLowerCase(Locale.ROOT));
            }
            case "test" -> {
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                boolean everybody = args.length > 2 && args[2].equalsIgnoreCase("all");
                StreamEvent event = what.equals("live") ? new StreamEvent.WentLive(live.announcer().sample())
                        : what.equals("ended") || what.equals("end")
                        ? new StreamEvent.Ended(live.announcer().sample(), live.announcer().sampleLength()) : null;
                if (event == null || (!everybody && !(sender instanceof Player))) {
                    live.messages().send(sender, "twitch.usage");
                } else if (everybody) {
                    // "counts" makes a test count like a real stream — for when Twitch cannot be asked.
                    boolean counts = args.length > 3 && args[3].equalsIgnoreCase("counts");
                    live.announcer().announce(event, !counts);
                } else {
                    live.announcer().preview(event, (Player) sender);
                }
            }
            case "reload" -> {
                live.reloadLogin().run();
                live.messages().send(sender, "twitch.reloaded");
            }
            case "login" -> login(live, sender, args);
            case "logout" -> logout(live, sender);
            default -> live.messages().send(sender, "twitch.usage");
        }
    }

    private static boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    /** Console only: what a player types is written to the server log and kept in their chat history. */
    private void login(TwitchServices live, CommandSender sender, String[] args) {
        if (!isConsole(sender)) {
            live.messages().send(sender, "twitch.login.console-only");
        } else if (args.length != 3) {
            live.messages().send(sender, "twitch.login.usage");
        } else if (!live.logins().set(args[1], args[2])) {
            live.messages().send(sender, "twitch.login.failed");
        } else {
            live.reloadLogin().run();
            live.messages().send(sender, live.logins().source() == TwitchLoginStore.Source.ENVIRONMENT
                    ? "twitch.login.stored-environment-wins" : "twitch.login.stored");
        }
    }

    private void logout(TwitchServices live, CommandSender sender) {
        if (!isConsole(sender)) {
            live.messages().send(sender, "twitch.login.console-only");
        } else if (!live.logins().clear()) {
            live.messages().send(sender, "twitch.login.failed");
        } else {
            live.reloadLogin().run();
            live.messages().send(sender, "twitch.login.cleared");
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
            return List.of("status", "test", "reload", "login", "logout").stream().filter(option -> option.startsWith(last)).toList();
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
