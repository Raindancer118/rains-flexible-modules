package de.raindancer.modules.moderation.command;

import de.raindancer.core.platform.command.MistypedCommand;
import de.raindancer.modules.moderation.AntiCheatServices;
import de.raindancer.modules.moderation.AntiCheatSettings;
import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.model.Evidence;
import de.raindancer.modules.moderation.model.PlayerTrack;
import de.raindancer.modules.moderation.screen.SuspectsMenu;
import de.raindancer.modules.moderation.util.CheatPermissions;
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
    private static final List<String> SUBCOMMANDS = List.of("alerts", "alert", "verbose", "info", "log", "go", "back",
            "fresh", "reset", "checks", "status");

    /** Where and how a staff member was before {@code /ac go}. */
    private record Watcher(org.bukkit.Location where, org.bukkit.GameMode mode) {
    }

    private final Map<UUID, Watcher> watchers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Where each staff member stood before {@code /anticheat go}, for {@code /anticheat back}. */
    private final Map<UUID, org.bukkit.Location> before = new java.util.concurrent.ConcurrentHashMap<>();

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
            case "alert" -> alert(live, sender, args);
            case "verbose" -> {
                if (!(sender instanceof Player player)) {
                    live.messages().send(sender, "anticheat.only-a-player");
                    return;
                }
                live.messages().send(sender, live.alerts().toggleVerbose(player) ? "anticheat.verbose-on" : "anticheat.verbose-off");
            }
            case "info" -> withPlayer(live, sender, args, target -> info(live, sender, target));
            case "log" -> log(live, sender, args);
            case "fresh" -> {
                if (manage(live, sender)) {
                    fresh(live, sender, args);
                }
            }
            case "reset" -> {
                if (manage(live, sender)) {
                    reset(live, sender, args);
                }
            }
            case "go" -> go(live, sender, args);
            case "back" -> back(live, sender);
            case "checks" -> checks(live, sender);
            case "status" -> status(live, sender);
            default -> {
                if (!player(live, sender, args[0]) && !MistypedCommand.subcommand(sender, "anticheat", args, 0, SUBCOMMANDS)) {
                    live.messages().send(sender, "anticheat.usage");
                }
            }
        }
    }

    /**
     * {@code /anticheat <player>}: straight to their checks — what an alert's name opens, and what a Bedrock
     * staff member, who cannot click chat, types instead. Somebody offline with a record gets the log.
     *
     * @return whether the word was a player at all
     */
    private boolean player(AntiCheatServices live, CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            if (sender instanceof Player viewer) {
                new de.raindancer.modules.moderation.screen.PlayerChecksMenu(live, viewer, null, live.tracks().of(online)).open();
            } else {
                info(live, sender, online);
            }
            return true;
        }
        if (resolve(live, name) != null) {
            log(live, sender, new String[]{"log", name});
            return true;
        }
        return false;
    }

    /** Vanilla's own nodes: going somewhere and becoming a spectator are those commands' powers, not this one's. */
    static final String TELEPORT = "minecraft.command.teleport";
    static final String GAME_MODE = "minecraft.command.gamemode";

    /** {@code /anticheat go <player>}: to where they are, remembering where the staff member was. */
    private void go(AntiCheatServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            live.messages().send(sender, "anticheat.only-a-player");
            return;
        }
        if (!viewer.hasPermission(TELEPORT)) {
            live.messages().send(sender, "anticheat.no-permission");
            return;
        }
        withPlayer(live, sender, args, target -> {
            before.put(viewer.getUniqueId(), viewer.getLocation());
            // Where they stand is read on their own thread, then gone to on ours: on Folia they may be elsewhere.
            de.raindancer.core.platform.util.Scheduling.entity(live.plugin(), target, () -> {
                org.bukkit.Location at = target.getLocation();
                de.raindancer.core.platform.util.Scheduling.entity(live.plugin(), viewer, () -> viewer.teleportAsync(at)
                        .thenRun(() -> live.messages().send(viewer, "anticheat.went", "player", target.getName())));
            });
        });
    }

    /** {@code /anticheat back}: to where they stood before the last {@code go}. */
    private void back(AntiCheatServices live, CommandSender sender) {
        if (!(sender instanceof Player viewer)) {
            live.messages().send(sender, "anticheat.only-a-player");
            return;
        }
        org.bukkit.Location where = before.remove(viewer.getUniqueId());
        if (where == null) {
            live.messages().send(sender, "anticheat.nowhere-to-go-back");
            return;
        }
        viewer.teleportAsync(where).thenRun(() -> live.messages().send(viewer, "anticheat.back"));
    }

    /**
     * {@code /anticheat alert off|on <check> [global]} and {@code /anticheat alert list}: one check quietened in chat
     * for whoever asks, or — for an operator or the console — for every member of staff. Evidence, replays and
     * reports are not affected.
     */
    private static void alert(AntiCheatServices live, CommandSender sender, String[] args) {
        de.raindancer.modules.moderation.store.AlertMutes mutes = live.alerts().mutes();
        String what = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (mutes == null || what.isEmpty()) {
            live.messages().send(sender, "anticheat.usage");
            return;
        }
        UUID self = sender instanceof Player player ? player.getUniqueId() : null;
        if (what.equals("list")) {
            String own = self == null ? "" : String.join(", ", mutes.mutedBy(self).stream().map(CheckType::title).toList());
            String all = String.join(", ", mutes.mutedForEveryone().stream().map(CheckType::title).toList());
            live.messages().send(sender, "anticheat.alert-mutes", "own", own.isEmpty() ? "none" : own,
                    "everyone", all.isEmpty() ? "none" : all);
            return;
        }
        boolean off = what.equals("off");
        if (!off && !what.equals("on") || args.length < 3) {
            live.messages().send(sender, "anticheat.usage");
            return;
        }
        java.util.Optional<CheckType> check = CheckType.find(args[2]);
        if (check.isEmpty()) {
            live.messages().send(sender, "anticheat.no-such-check", "check", args[2]);
            return;
        }
        boolean global = args.length >= 4 && args[3].equalsIgnoreCase("global");
        if (global) {
            if (!sender.isOp() && self != null) {
                live.messages().send(sender, "anticheat.alert-global-op-only");
                return;
            }
            if (off) {
                mutes.muteForEveryone(check.get());
            } else {
                mutes.unmuteForEveryone(check.get());
            }
            live.messages().send(sender, off ? "anticheat.alert-muted-global" : "anticheat.alert-unmuted-global",
                    "check", check.get().title());
            return;
        }
        if (self == null) {
            live.messages().send(sender, "anticheat.only-a-player");
            return;
        }
        if (off) {
            mutes.mute(self, check.get());
        } else {
            mutes.unmute(self, check.get());
        }
        boolean stillGlobal = !off && mutes.mutedForEveryone().contains(check.get());
        live.messages().send(sender, off ? "anticheat.alert-muted" : stillGlobal ? "anticheat.alert-unmuted-but-global"
                : "anticheat.alert-unmuted", "check", check.get().title());
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
        live.messages().send(sender, "anticheat.info-meta", "ping", target.getPing(),
                "brand", brand == null ? "unknown" : brand);
    }

    /**
     * Measured afresh: the checks see somebody new from now on, for a player online or not. Evidence, replays
     * and punishments stay as history; the log marks what came before.
     */
    private static void fresh(AntiCheatServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "anticheat.usage");
            return;
        }
        UUID who = resolve(live, args[1]);
        if (who == null) {
            Player online = Bukkit.getPlayerExact(args[1]);
            who = online == null ? null : online.getUniqueId();
        }
        if (who == null) {
            live.messages().send(sender, "anticheat.no-record", "player", args[1]);
            return;
        }
        live.violations().measureAfresh(who, live.tracks().find(who).orElse(null), System.currentTimeMillis());
        live.alerts().freshStart(who);
        String name = live.evidence().nameOf(who);
        live.messages().send(sender, "anticheat.fresh", "player", name == null ? args[1] : name);
    }

    /** A fresh start: levels, evidence and replays, for a player online or not. */
    private static void reset(AntiCheatServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "anticheat.usage");
            return;
        }
        UUID who = resolve(live, args[1]);
        if (who == null) {
            live.messages().send(sender, "anticheat.no-record", "player", args[1]);
            return;
        }
        String name = live.evidence().nameOf(who);
        live.violations().freshStart(who, live.tracks().find(who).orElse(null));
        live.messages().send(sender, "anticheat.reset", "player", name == null ? args[1] : name);
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
        long measuredFrom = live.evidence().measuredFrom(who);
        if (measuredFrom > 0) {
            live.messages().send(sender, "anticheat.log-fresh", "time", time.format(new Date(measuredFrom)));
        }
        for (Evidence entry : all.subList((page - 1) * LOG_PAGE, Math.min(all.size(), page * LOG_PAGE))) {
            String title = CheckType.find(entry.check()).map(CheckType::title).orElse(entry.check());
            live.messages().send(sender, entry.atMillis() < measuredFrom ? "anticheat.log-line-before" : "anticheat.log-line",
                    "time", time.format(new Date(entry.atMillis())),
                    "check", title, "level", String.format(Locale.ROOT, "%.1f", entry.level()), "detail", entry.detail(),
                    "where", entry.world() + " " + entry.x() + " " + entry.y() + " " + entry.z(), "ping", entry.ping(),
                    "tps", String.format(Locale.ROOT, "%.1f", entry.tps()));
        }
    }

    /** Draws a frozen replay around the staff member, taking them there first if it happened elsewhere. */
    private static void checks(AntiCheatServices live, CommandSender sender) {
        AntiCheatSettings settings = live.config();
        live.messages().send(sender, "anticheat.checks-header", "count", CheckType.values().length);
        for (CheckType check : CheckType.values()) {
            String key = settings.disabled(check.key()) ? "anticheat.check-off"
                    : settings.silent(check.key()) ? "anticheat.check-silent" : "anticheat.check-on";
            live.messages().send(sender, key, "check", check.title(), "key", check.key(),
                    "category", check.category().title(), "description", check.description());
        }
    }

    private static void status(AntiCheatServices live, CommandSender sender) {
        live.messages().send(sender, "anticheat.status", "players", live.tracks().size(),
                "grim", de.raindancer.modules.moderation.util.GrimBridge.available(live.server()) ? "on" : "not installed",
                "families", CheckType.values().length,
                "tap", live.tap().broken() ? "does not fit this server" : live.config().packetTap() ? "on" : "off");
    }

    private static boolean manage(AntiCheatServices live, CommandSender sender) {
        if (!sender.hasPermission(CheatPermissions.MANAGE)) {
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
            services.get().server().getOnlinePlayers().forEach(player -> options.add(player.getName()));
        } else if (args.length == 2 && !List.of("alerts", "alert", "verbose", "checks", "status", "back").contains(args[0].toLowerCase(Locale.ROOT))) {
            AntiCheatServices live = services.get();
            live.server().getOnlinePlayers().forEach(player -> options.add(player.getName()));
            if (List.of("log", "replay", "reset", "fresh").contains(args[0].toLowerCase(Locale.ROOT))) {
                // Offline players with a record can be looked up and cleared too.
                live.evidence().everybody().stream().map(live.evidence()::nameOf)
                        .filter(name -> name != null && !options.contains(name)).forEach(options::add);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("alert")) {
            options.addAll(List.of("off", "on", "list"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("alert") && !args[1].equalsIgnoreCase("list")) {
            for (CheckType check : CheckType.values()) {
                options.add(check.key());
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("alert")
                && source.getSender() instanceof org.bukkit.command.CommandSender who && (who.isOp() || !(who instanceof Player))) {
            options.add("global");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("exempt")) {
            options.addAll(List.of("30", "60", "300", "3600"));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return CheatPermissions.COMMAND;
    }
}
