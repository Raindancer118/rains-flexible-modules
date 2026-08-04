package de.raindancer.modules.farmworld.command;

import de.raindancer.core.world.time.Times;
import de.raindancer.modules.farmworld.FarmWorldServices;
import de.raindancer.modules.farmworld.model.Arrival;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import de.raindancer.modules.farmworld.rules.FarmWorldNameRule;
import de.raindancer.modules.farmworld.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /farm} — the front door, and everything an admin needs to type.
 *
 * <h2>Why one command and not two</h2>
 * {@code /farm} for players and {@code /farmadmin} for owners would be two names to learn and two places for
 * the list of farm worlds to come from. One command, with the managing half behind a permission and behind a
 * word, means a player types {@code /farm} and gets exactly what they can do.
 *
 * <h2>What bare {@code /farm} does</h2>
 * It opens the list, wherever the player is standing. That is the front door and it must not depend on
 * anything: a command that means one thing here and another there is one nobody can describe to somebody else.
 * Anything else is read as a farm world's name, so {@code /farm mining} works without a {@code go} nobody
 * would think to type.
 *
 * <p>Everything it decides lives in the rules and the services, tested without a server. What is here is
 * argument handling, which is the part a test cannot check anyway.
 */
public final class FarmWorldCommand implements IFarmWorldCommand {

    private final Supplier<FarmWorldServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link IFarmWorldCommand} on why a
     *                 command built at bootstrap cannot hold anything the module built
     */
    public FarmWorldCommand(Supplier<FarmWorldServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        FarmWorldServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            openTheList(live, sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(live, sender);
            case "help" -> help(live, sender);
            case "info" -> info(live, sender, args);
            case "config", "settings" -> config(live, sender);
            case "admin" -> admin(live, sender, args);
            case "create", "make" -> create(live, sender, args);
            case "delete", "remove" -> delete(live, sender, args);
            case "forget" -> forget(live, sender, args);
            case "regen", "regenerate" -> regenerate(live, sender, args);
            case "reload" -> reload(live, sender);
            // Anything else is a farm world's name, so /farm mining needs no subcommand — and a word
            // after it says how to arrive: /farm mining rtp.
            default -> go(live, sender, args[0], args.length >= 2 ? args[1] : null);
        }
    }

    // ------------------------------------------------------------------------ going

    private void openTheList(FarmWorldServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // The console has no inventory to open, so it gets the listing instead of nothing.
            list(live, sender);
            return;
        }
        live.screens().farms(player);
    }

    private void go(FarmWorldServices live, CommandSender sender, String name, String how) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "farmworlds.only-a-player");
            return;
        }
        live.travelling().goTo(player, name, Arrival.of(how));
    }

    // ------------------------------------------------------------------------ listing

    /**
     * The farm worlds as lines of chat.
     *
     * <p>Kept beside the menu for the console, which has no inventory, and for pasting to somebody else. Only
     * the ones this sender may see, which for an ordinary player is all of them — see {@code FarmAccessRule}
     * on why this module hides nothing.
     */
    private void list(FarmWorldServices live, CommandSender sender) {
        List<FarmWorldView> visible = sender instanceof Player player
                ? live.catalogue().visibleTo(player::hasPermission, live.access())
                : live.catalogue().all();
        if (visible.isEmpty()) {
            live.messages().send(sender, "farmworlds.none-yet");
            return;
        }
        live.messages().send(sender, "farmworlds.list-heading", "count", visible.size());
        for (FarmWorldView farm : visible) {
            live.messages().sendPlain(sender, "farmworlds.list-row",
                    "name", farm.name(),
                    "count", farm.worlds().size(),
                    "time", lifespanOf(farm));
        }
    }

    private void info(FarmWorldServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "farmworlds.usage.info");
            return;
        }
        FarmWorldView farm = live.catalogue().byName(args[1]).orElse(null);
        if (farm == null) {
            live.messages().send(sender, "farmworlds.unknown", "name", args[1]);
            return;
        }
        live.messages().send(sender, "farmworlds.info-heading", "name", farm.name());
        for (String world : farm.worlds()) {
            live.messages().sendPlain(sender, "farmworlds.info-world",
                    "world", world,
                    "state", live.catalogue().isLoaded(world) ? "loaded" : "not loaded");
        }
        live.messages().sendPlain(sender, "farmworlds.info-lifespan", "time", lifespanOf(farm));
        farm.border().ifPresent(radius ->
                live.messages().sendPlain(sender, "farmworlds.info-border", "blocks", radius));
    }

    /**
     * How long a farm world has left, in words.
     *
     * <p>Plain words rather than markup: it goes through {@code Chat.arg}, which escapes what it is given —
     * always, and rightly, since nearly everything through it is text somebody typed. A colour written in here
     * would reach the player as a tag.
     */
    private static String lifespanOf(FarmWorldView farm) {
        if (!farm.isScheduled()) {
            return "kept until somebody regenerates it";
        }
        return farm.untilRegenerated()
                .map(left -> Times.describe(left) + " left")
                .orElse("due to be made again");
    }

    private void help(FarmWorldServices live, CommandSender sender) {
        live.messages().lines("farmworlds.help").forEach(sender::sendMessage);
        if (live.access().mayManage(sender::hasPermission)) {
            live.messages().lines("farmworlds.help-admin").forEach(sender::sendMessage);
        }
    }

    // ------------------------------------------------------------------------ managing

    /**
     * One farm world's admin page.
     *
     * <p>Takes a name because there is no single farm world to mean — and falls back to the list rather than
     * refusing, so {@code /farm admin} with nothing after it is a way in rather than a usage line.
     */
    private void admin(FarmWorldServices live, CommandSender sender, String[] args) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "farmworlds.not-yours");
            return;
        }
        if (!(sender instanceof Player player)) {
            list(live, sender);
            return;
        }
        if (args.length < 2) {
            live.screens().farms(player);
            return;
        }
        if (live.catalogue().byName(args[1]).isEmpty()) {
            live.messages().send(sender, "farmworlds.unknown", "name", args[1]);
            return;
        }
        live.screens().manage(player, args[1]);
    }

    /**
     * The settings page.
     *
     * <p>Earns a subcommand because nothing else reaches it, and takes no arguments at all: every one of the
     * twelve settings is a click, and none of them is a value a menu cannot ask for.
     */
    private void config(FarmWorldServices live, CommandSender sender) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "farmworlds.not-yours");
            return;
        }
        if (!(sender instanceof Player player)) {
            // The console has no inventory. Pointing at /settings beats a silent nothing: that is the same
            // settings, and it works from a console.
            live.messages().send(sender, "farmworlds.usage.config");
            return;
        }
        live.screens().config(player);
    }

    /**
     * {@code /farm create <name> [every] [border]}.
     *
     * <p>Three arguments a menu cannot ask for, because the farm world does not exist yet and there is no page
     * to put them on. The period is read by {@code Times.parse}, so {@code 7d} and {@code 2 weeks} both
     * work, and the word {@code never} is the one people reach for.
     */
    private void create(FarmWorldServices live, CommandSender sender, String[] args) {
        if (!live.access().mayManage(sender::hasPermission)) {
            // Asked here as well as in the service, and not redundantly: without it, somebody who may not
            // create anything is told their period was unparseable, which is a refusal about the wrong thing.
            live.messages().send(sender, "farmworlds.not-yours");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "farmworlds.usage.create");
            return;
        }
        Duration every = null;
        // Times.isForEver, not a word list of this command's own. It already knows never, none, perm,
        // permanent, forever, inf, infinite and always — and the point of that living in Core is that a
        // moderator's "perm" and an owner's "never" mean the same thing to every plugin on the server.
        if (args.length >= 3 && !Times.isForEver(args[2])) {
            every = Times.parse(args[2]).orElse(null);
            if (every == null) {
                live.messages().send(sender, "farmworlds.bad-period", "text", args[2]);
                return;
            }
        }
        Integer border = null;
        if (args.length >= 4) {
            try {
                border = Integer.parseInt(args[3]);
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "farmworlds.bad-border", "text", args[3]);
                return;
            }
        }
        live.admin().create(sender, args[1], every, border);
    }

    /**
     * {@code /farm delete <name> confirm} — the farm world and its worlds, gone.
     *
     * <p>Asks twice, for the same reason {@code regen} does: the console has no inventory to be shown a
     * confirmation page in, and what is behind this removes three worlds with no undo. This one is worse than
     * regen — regen puts something back.
     */
    private void delete(FarmWorldServices live, CommandSender sender, String[] args) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "farmworlds.not-yours");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "farmworlds.usage.delete");
            return;
        }
        FarmWorldView farm = live.catalogue().byName(args[1]).orElse(null);
        if (farm == null) {
            live.messages().send(sender, "farmworlds.unknown", "name", args[1]);
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            live.messages().send(sender, "farmworlds.delete-are-you-sure",
                    "name", farm.name(), "count", farm.worlds().size());
            return;
        }
        if (live.admin().delete(sender, farm.name())) {
            live.notices().forget(farm.name());
        }
    }

    /**
     * {@code /farm forget <name>} — off the list, worlds kept.
     *
     * <p>Its own word rather than a flag on delete. The two are different decisions and somebody typing one
     * must never get the other.
     */
    private void forget(FarmWorldServices live, CommandSender sender, String[] args) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "farmworlds.not-yours");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "farmworlds.usage.forget");
            return;
        }
        if (live.admin().forget(sender, args[1])) {
            live.notices().forget(args[1]);
        }
    }

    /**
     * {@code /farm regen <name> confirm} — the one command that deletes worlds.
     *
     * <p>Asks twice, and the second word is deliberately not completed: it is meant to be typed rather than
     * tabbed past. The menu confirms with a page instead; both entrances confirm, because a guard on one of two
     * is not a guard.
     */
    private void regenerate(FarmWorldServices live, CommandSender sender, String[] args) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "farmworlds.not-yours");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "farmworlds.usage.regen");
            return;
        }
        FarmWorldView farm = live.catalogue().byName(args[1]).orElse(null);
        if (farm == null) {
            live.messages().send(sender, "farmworlds.unknown", "name", args[1]);
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            live.messages().send(sender, "farmworlds.regen-are-you-sure",
                    "name", farm.name(), "count", farm.worlds().size());
            return;
        }
        live.admin().regenerate(sender, farm.name());
    }

    /**
     * Rereads the settings file.
     *
     * <p>Earns its place under the third clause — nothing else reaches it — and matters more here than in most
     * modules: the settings that decide where people land are the ones an owner is most likely to be editing by
     * hand while somebody is standing in the farm world.
     */
    private void reload(FarmWorldServices live, CommandSender sender) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "farmworlds.not-yours");
            return;
        }
        live.store().load();
        live.messages().send(sender, "farmworlds.reloaded");
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        FarmWorldServices live = services.get();
        CommandSender sender = source.getSender();
        boolean admin = live.access().mayManage(sender::hasPermission);

        List<String> names = live.catalogue().names();
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(names);
            options.add("list");
            options.add("info");
            options.add("help");
            if (admin) {
                options.addAll(List.of("admin", "config", "create", "delete", "forget", "regen",
                        "reload"));
            }
            return startingWith(options, typed);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("create")) {
                // Nothing to offer: the name is new, which is the whole point of the subcommand.
                return List.of();
            }
            return startingWith(names, args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && names.contains(args[0].toLowerCase(Locale.ROOT))) {
            // /farm mining <here>. Offered rather than left to be guessed: rtp is the half of this feature
            // nobody finds by accident.
            return startingWith(Arrival.words(), args[2].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && admin && args[0].equalsIgnoreCase("create")) {
            return startingWith(List.of("never", "1d", "3d", "7d", "14d", "30d"),
                    args[2].toLowerCase(Locale.ROOT));
        }
        // The confirmation is not completed on purpose: it is meant to be typed deliberately.
        return List.of();
    }

    private static Collection<String> startingWith(List<String> options, String typed) {
        return options.stream()
                .filter(word -> word.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(50)
                .toList();
    }

    @Override
    public @NotNull String permission() {
        // The lower of the two, so an ordinary player can still type it. Every managing branch above asks for
        // MANAGE itself — the two guards are not redundant: this one decides whether the command appears at all.
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "go to a farm world, or manage the ones this server has";
    }

    /** The words this command reads as instructions — the list {@code FarmWorldNameRule} refuses. */
    public static List<String> subcommands() {
        return FarmWorldNameRule.RESERVED;
    }
}
