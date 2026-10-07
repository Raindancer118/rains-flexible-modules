package de.raindancer.modules.anticheat.command;

import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Evidence;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.rules.ClickRule;
import de.raindancer.modules.anticheat.screen.SuspectsMenu;
import de.raindancer.modules.anticheat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** {@code /anticheat} — the suspects screen, alerts, a player's record and evidence, exemptions, checks. */
public final class AntiCheatCommand implements BasicCommand {

    private static final int LOG_PAGE = 10;
    private static final List<String> SUBCOMMANDS = List.of("alerts", "verbose", "info", "log", "exempt", "unexempt",
            "reset", "checks", "status");

    private final Supplier<AntiCheatServices> services;

    public AntiCheatCommand(Supplier<AntiCheatServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        AntiCheatServices live = services.get();
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (sender instanceof Player viewer) {
                    new SuspectsMenu(live, viewer, null).open();
                } else {
                    status(live, sender);
                }
            }
            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    live.messages().send(sender, "anticheat.only-a-player");
                    return;
                }
                live.messages().send(sender, live.alerts().toggle(player) ? "anticheat.alerts-on" : "anticheat.alerts-off");
            }
            case "verbose" -> {
                if (!(sender instanceof Player player)) {
                    live.messages().send(sender, "anticheat.only-a-player");
                    return;
                }
                live.messages().send(sender, live.alerts().toggleVerbose(player) ? "anticheat.verbose-on" : "anticheat.verbose-off");
            }
            case "info" -> withPlayer(live, sender, args, target -> info(live, sender, target));
            case "log" -> log(live, sender, args);
            case "exempt" -> {
                if (!manage(live, sender)) {
                    return;
                }
                withPlayer(live, sender, args, target -> {
                    int seconds = args.length >= 3 ? parse(args[2], 60) : 60;
                    live.tracks().of(target).exempt(PlayerTrack.Exemption.MANUAL, seconds * 1000L);
                    live.messages().send(sender, "anticheat.exempted", "player", target.getName(), "seconds", seconds);
                });
            }
            case "unexempt" -> {
                if (!manage(live, sender)) {
                    return;
                }
                withPlayer(live, sender, args, target -> {
                    live.tracks().of(target).unexempt(PlayerTrack.Exemption.MANUAL);
                    live.messages().send(sender, "anticheat.unexempted", "player", target.getName());
                });
            }
            case "reset" -> {
                if (!manage(live, sender)) {
                    return;
                }
                withPlayer(live, sender, args, target -> {
                    live.tracks().of(target).violations().resetAll();
                    live.messages().send(sender, "anticheat.reset", "player", target.getName());
                });
            }
            case "checks" -> checks(live, sender);
            case "status" -> status(live, sender);
            default -> live.messages().send(sender, "anticheat.usage");
        }
    }

    private static void info(AntiCheatServices live, CommandSender sender, Player target) {
        PlayerTrack track = live.tracks().of(target);
        live.messages().send(sender, "anticheat.info-header", "player", target.getName(),
                "level", String.format(Locale.ROOT, "%.1f", track.violations().total()));
        Map<CheckType, Double> levels = track.violations().snapshot();
        if (levels.isEmpty()) {
            live.messages().send(sender, "anticheat.info-clean");
        }
        levels.forEach((check, level) -> live.messages().send(sender, "anticheat.info-line", "check", check.title(),
                "level", String.format(Locale.ROOT, "%.1f", level), "category", check.category().title()));
        String brand = target.getClientBrandName();
        PlayerTrack.Exemption exemption = track.exemption();
        live.messages().send(sender, "anticheat.info-meta", "ping", target.getPing(),
                "brand", brand == null ? "unknown" : brand,
                "tap", track.packets.tapped ? (track.packets.sendsTickEnd ? "packets with tick clock" : "packets") : "events only",
                "timer", String.format(Locale.ROOT, "%.0f", track.timer.balance()),
                "exempt", exemption == null ? "no" : exemption.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        ClickRule.Stats clicks = live.clicks().stats(track);
        live.messages().send(sender, "anticheat.info-clicks", "cps", String.format(Locale.ROOT, "%.1f", clicks.cps()),
                "deviation", String.format(Locale.ROOT, "%.1f", clicks.deviation()),
                "kurtosis", String.format(Locale.ROOT, "%.2f", clicks.kurtosis()),
                "skewness", String.format(Locale.ROOT, "%.2f", clicks.skewness()));
    }

    private void log(AntiCheatServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "anticheat.usage");
            return;
        }
        UUID who = resolve(live, args[1]);
        if (who == null) {
            live.messages().send(sender, "anticheat.no-record", "player", args[1]);
            return;
        }
        List<Evidence> all = live.evidence().of(who);
        if (all.isEmpty()) {
            live.messages().send(sender, "anticheat.log-empty", "player", args[1]);
            return;
        }
        int pages = (all.size() + LOG_PAGE - 1) / LOG_PAGE;
        int page = Math.max(1, Math.min(pages, args.length >= 3 ? parse(args[2], 1) : 1));
        live.messages().send(sender, "anticheat.log-header", "player", args[1], "page", page, "pages", pages, "count", all.size());
        SimpleDateFormat time = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT);
        for (Evidence entry : all.subList((page - 1) * LOG_PAGE, Math.min(all.size(), page * LOG_PAGE))) {
            String title = CheckType.find(entry.check()).map(CheckType::title).orElse(entry.check());
            live.messages().send(sender, "anticheat.log-line", "time", time.format(new Date(entry.atMillis())),
                    "check", title, "level", String.format(Locale.ROOT, "%.1f", entry.level()), "detail", entry.detail(),
                    "where", entry.world() + " " + entry.x() + " " + entry.y() + " " + entry.z(), "ping", entry.ping(),
                    "tps", String.format(Locale.ROOT, "%.1f", entry.tps()));
        }
    }

    private static void checks(AntiCheatServices live, CommandSender sender) {
        AntiCheatSettings settings = live.config();
        live.messages().send(sender, "anticheat.checks-header", "count", CheckType.values().length);
        for (CheckType check : CheckType.values()) {
            String key = settings.disabled(check.key()) ? "anticheat.check-off"
                    : check.experimental() && !settings.experimentalChecks() ? "anticheat.check-off"
                    : check.experimental() ? "anticheat.check-experimental"
                    : settings.silent(check.key()) ? "anticheat.check-silent" : "anticheat.check-on";
            live.messages().send(sender, key, "check", check.title(), "key", check.key(),
                    "category", check.category().title(), "description", check.description());
        }
    }

    private static void status(AntiCheatServices live, CommandSender sender) {
        double tps = Bukkit.getTPS()[0];
        long tapped = live.tracks().all().stream().filter(track -> track.packets.tapped).count();
        live.messages().send(sender, "anticheat.status", "players", live.tracks().size(), "tapped", tapped,
                "tps", String.format(Locale.ROOT, "%.1f", tps), "checks", CheckType.values().length,
                "tap", live.tap().broken() ? "does not fit this server" : live.config().packetTap() ? "on" : "off",
                "paused", tps < live.config().minTps() ? "yes" : "no");
    }

    private static boolean manage(AntiCheatServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.MANAGE)) {
            live.messages().send(sender, "anticheat.no-permission");
            return false;
        }
        return true;
    }

    private static void withPlayer(AntiCheatServices live, CommandSender sender, String[] args, java.util.function.Consumer<Player> then) {
        if (args.length < 2) {
            live.messages().send(sender, "anticheat.usage");
            return;
        }
        Player target = live.server().getPlayerExact(args[1]);
        if (target == null) {
            live.messages().send(sender, "anticheat.not-online", "player", args[1]);
            return;
        }
        then.accept(target);
    }

    private static UUID resolve(AntiCheatServices live, String name) {
        Player online = live.server().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        for (UUID id : live.evidence().everybody()) {
            if (name.equalsIgnoreCase(live.evidence().nameOf(id))) {
                return id;
            }
        }
        OfflinePlayer known = live.server().getOfflinePlayerIfCached(name);
        return known == null ? null : known.getUniqueId();
    }

    private static int parse(String text, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(text));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(SUBCOMMANDS);
        } else if (args.length == 2 && !List.of("alerts", "verbose", "checks", "status").contains(args[0].toLowerCase(Locale.ROOT))) {
            services.get().server().getOnlinePlayers().forEach(player -> options.add(player.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("exempt")) {
            options.addAll(List.of("30", "60", "300", "3600"));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.COMMAND;
    }
}
