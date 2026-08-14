package de.raindancer.modules.warp.command;

import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.WarpServices;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpNameRule;
import de.raindancer.modules.warp.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /warp} — the front door, and everything an admin needs to type.
 *
 * <h2>Why one command and not two</h2>
 * {@code /warp} for players and {@code /warpadmin} for owners would be two names to learn and two
 * places for the list of warps to come from. One command, with the managing half behind a permission
 * and behind the word {@code admin}, means a player types {@code /warp} and gets exactly what they
 * can do.
 *
 * <h2>What bare {@code /warp} does</h2>
 * It opens the menu, wherever the player is standing. That is the front door and it must not depend
 * on anything: a command that means one thing here and another there is one nobody can describe to
 * somebody else. {@code /warp admin} opens the admin page, and anything else is read as a warp's
 * name — so {@code /warp spawn} works without a {@code go} nobody would think to type.
 *
 * <p>Everything it decides lives in the rules and the services, tested without a server. What is here
 * is argument handling, which is the part a test cannot check anyway.
 */
public final class WarpCommand implements IWarpCommand {

    private final Supplier<WarpServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link IWarpCommand} on
     *                 why a command built at bootstrap cannot hold anything the module built
     */
    public WarpCommand(Supplier<WarpServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WarpServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            openTheMenu(live, sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(live, sender);
            case "help" -> help(live, sender);
            case "admin" -> admin(live, sender);
            case "config", "settings" -> config(live, sender);
            case "set", "setwarp" -> set(live, sender, args);
            case "move" -> move(live, sender, args);
            case "delete", "remove", "delwarp" -> delete(live, sender, args);
            case "category" -> category(live, sender, args);
            case "label" -> label(live, sender, args);
            case "icon" -> icon(live, sender, args);
            case "access", "permission" -> access(live, sender, args);
            // Anything else is a warp's name, so /warp spawn needs no subcommand.
            default -> go(live, sender, args[0]);
        }
    }

    // ------------------------------------------------------------------------ going

    private void openTheMenu(WarpServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // The console has no inventory to open, so it gets the listing instead of nothing.
            list(live, sender);
            return;
        }
        live.screens().warps(player);
    }

    private void go(WarpServices live, CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "warps.only-a-player");
            return;
        }
        live.travelling().goTo(player, name);
    }

    // ------------------------------------------------------------------------ listing

    /**
     * The warps as lines of chat.
     *
     * <p>Kept beside the menu for the console, which has no inventory, and for pasting to somebody
     * else. Only the warps this sender may see — a listing that leaks a staff warp's name undoes the
     * hiding the menu does.
     */
    private void list(WarpServices live, CommandSender sender) {
        List<Warp> visible = sender instanceof Player player
                ? live.catalogue().visibleTo(player::hasPermission, live.access())
                : live.catalogue().all();
        if (visible.isEmpty()) {
            live.messages().send(sender, "warps.list-empty");
            return;
        }
        live.messages().send(sender, "warps.list-heading", "count", visible.size());
        for (Warp warp : visible) {
            live.messages().sendPlain(sender, "warps.list-row",
                    "name", warp.label(),
                    "world", warp.world(),
                    "where", warp.coordinates(),
                    "category", warp.category().orElse("—"));
        }
    }

    private void help(WarpServices live, CommandSender sender) {
        live.messages().lines("warps.help").forEach(sender::sendMessage);
        if (live.access().mayManage(sender::hasPermission)) {
            live.messages().lines("warps.help-admin").forEach(sender::sendMessage);
        }
    }

    // ------------------------------------------------------------------------ managing

    private void admin(WarpServices live, CommandSender sender) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "warps.not-yours");
            return;
        }
        if (!(sender instanceof Player player)) {
            list(live, sender);
            return;
        }
        live.screens().admin(player);
    }

    /**
     * The settings page.
     *
     * <p>Earns a subcommand under the third clause — nothing else reaches it — and takes no
     * arguments at all: every one of the eleven settings is a click, and none of them is a value a
     * menu cannot ask for.
     */
    private void config(WarpServices live, CommandSender sender) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "warps.not-yours");
            return;
        }
        if (!(sender instanceof Player player)) {
            // The console has no inventory. Pointing at /settings beats a silent nothing: that is
            // the same settings, and it works from a console.
            live.messages().send(sender, "warps.usage.config");
            return;
        }
        live.screens().config(player);
    }

    private void set(WarpServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "warps.set-needs-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "warps.usage.set");
            return;
        }
        live.admin().create(player, args[1]);
    }

    private void move(WarpServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "warps.set-needs-a-player");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "warps.usage.move");
            return;
        }
        live.admin().move(player, args[1]);
    }

    private void delete(WarpServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "warps.usage.delete");
            return;
        }
        live.admin().delete(sender, args[1]);
    }

    private void category(WarpServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "warps.usage.category");
            return;
        }
        live.admin().setCategory(sender, args[1], args.length >= 3 ? args[2] : null);
    }

    /** The label may have spaces in it — it is what a menu shows, not what anybody types. */
    private void label(WarpServices live, CommandSender sender, String[] args) {
        if (args.length < 2) {
            live.messages().send(sender, "warps.usage.label");
            return;
        }
        String label = args.length >= 3
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : null;
        live.admin().setLabel(sender, args[1], label);
    }

    private void icon(WarpServices live, CommandSender sender, String[] args) {
        if (args.length < 3) {
            live.messages().send(sender, "warps.usage.icon");
            return;
        }
        Material material = Material.matchMaterial(args[2]);
        if (material == null || !material.isItem()) {
            live.messages().send(sender, "warps.no-such-item", "item", args[2]);
            return;
        }
        live.admin().setIcon(sender, args[1], material);
    }

    /**
     * Who a warp is for.
     *
     * <p>{@code everybody} and {@code staff} are words rather than nodes so that the two answers
     * nearly everybody wants do not need the permission string typed correctly. Anything else is
     * taken as a node.
     */
    private void access(WarpServices live, CommandSender sender, String[] args) {
        if (args.length < 3) {
            live.messages().send(sender, "warps.usage.access");
            return;
        }
        WarpAccess wanted = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "everybody", "everyone", "public", "none" -> WarpAccess.EVERYONE;
            case "staff" -> WarpAccess.STAFF;
            case "own" -> new WarpAccess.Needing(WarpAccess.ownPermissionFor(args[1]));
            default -> new WarpAccess.Needing(args[2]);
        };
        live.admin().setAccess(sender, args[1], wanted);
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        WarpServices live = services.get();
        CommandSender sender = source.getSender();
        boolean admin = live.access().mayManage(sender::hasPermission);

        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(names(live, sender));
            options.add("list");
            options.add("help");
            if (admin) {
                options.addAll(List.of("admin", "config", "set", "move", "delete", "category",
                        "label", "icon", "access"));
            }
            return startingWith(options, typed);
        }
        if (args.length == 2 && admin) {
            // Every subcommand but the first takes a warp name second.
            return startingWith(names(live, sender), args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && admin && args[0].equalsIgnoreCase("access")) {
            return startingWith(List.of("everybody", "staff", "own"),
                    args[2].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && admin && args[0].equalsIgnoreCase("category")) {
            return startingWith(new ArrayList<>(live.catalogue()
                            .categoriesVisibleTo(sender::hasPermission, live.access())),
                    args[2].toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    private static Collection<String> startingWith(List<String> options, String typed) {
        return options.stream()
                .filter(word -> word.toLowerCase(Locale.ROOT).startsWith(typed))
                .limit(50)
                .toList();
    }

    /**
     * The warps this sender may see.
     *
     * <p>Never all of them: completion that offers a staff warp's name tells everybody it exists,
     * which is exactly what the menu takes care not to do.
     */
    private static List<String> names(WarpServices live, CommandSender sender) {
        return live.catalogue().visibleTo(sender::hasPermission, live.access()).stream()
                .map(Warp::name)
                .toList();
    }

    @Override
    public @NotNull String permission() {
        // The lower of the two, so an ordinary player can still type it. Every managing branch above
        // asks for MANAGE itself — the two guards are not redundant: this one decides whether the
        // command appears at all.
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "go to a warp, or manage the list of them";
    }

    /** The words this command reads as instructions — the list {@code WarpNameRule} refuses. */
    public static List<String> subcommands() {
        return WarpNameRule.RESERVED;
    }
}
