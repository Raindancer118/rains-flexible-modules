package de.raindancer.modules.warp.command;

import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.WarpServices;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpNameRule;
import de.raindancer.modules.warp.service.ClaimWarpDirectory;
import de.raindancer.modules.warp.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
            case "owner", "give" -> owner(live, sender, args);
            case "member", "members" -> member(live, sender, args);
            case "mine" -> mine(live, sender);
            case "token", "tokens" -> token(live, sender, args);
            // With nothing after them these are still a warp of that name, if the server has one: a
            // warp called "home" was reachable as /warp home before claims had homes, and stays so.
            case "claim", "claims" -> {
                if (args.length >= 2) {
                    claim(live, sender, args);
                } else if (live.catalogue().byName(args[0]).isPresent()) {
                    go(live, sender, args[0]);
                } else {
                    claims(live, sender);
                }
            }
            case "home" -> {
                if (args.length >= 2) {
                    home(live, sender, args[1]);
                } else {
                    go(live, sender, args[0]);
                }
            }
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
                ? live.catalogue().visibleTo(player.getUniqueId(), player::hasPermission, live.access())
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
        if (live.access().mayCreate(sender::hasPermission, false)
                || !live.catalogue().ownedBy(idOf(sender)).isEmpty()) {
            live.messages().lines("warps.help-own").forEach(sender::sendMessage);
        }
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
            case "private", "only-me", "mine" -> WarpAccess.PRIVATE;
            case "staff" -> WarpAccess.STAFF;
            case "own" -> new WarpAccess.Needing(WarpAccess.ownPermissionFor(args[1]));
            default -> new WarpAccess.Needing(args[2]);
        };
        live.admin().setAccess(sender, args[1], wanted);
    }

    // ------------------------------------------------------------------------ owned warps

    /** {@code /warp owner <warp> <player>}: staff hand a warp to somebody. */
    private void owner(WarpServices live, CommandSender sender, String[] args) {
        if (args.length < 3) {
            live.messages().send(sender, "warps.usage.owner");
            return;
        }
        OfflinePlayer to = known(args[2]);
        if (to == null) {
            live.messages().send(sender, "warps.no-such-player", "player", args[2]);
            return;
        }
        live.admin().giveTo(sender, args[1], to.getUniqueId(), nameOf(to, args[2]));
    }

    /** {@code /warp member add|remove <warp> <player>}: who may use a private warp. */
    private void member(WarpServices live, CommandSender sender, String[] args) {
        if (args.length < 4 || !(args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            live.messages().send(sender, "warps.usage.member");
            return;
        }
        OfflinePlayer who = known(args[3]);
        if (who == null) {
            live.messages().send(sender, "warps.no-such-player", "player", args[3]);
            return;
        }
        if (args[1].equalsIgnoreCase("add")) {
            live.admin().addMember(sender, args[2], who.getUniqueId(), nameOf(who, args[3]));
        } else {
            live.admin().removeMember(sender, args[2], who.getUniqueId(), nameOf(who, args[3]));
        }
    }

    /** {@code /warp mine}: the warps this player owns, as a page they can change them from. */
    private void mine(WarpServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "warps.only-a-player");
            return;
        }
        live.screens().mine(player);
    }

    /** {@code /warp token [player] [amount]}: staff hand out warp tokens. */
    private void token(WarpServices live, CommandSender sender, String[] args) {
        if (!live.access().mayManage(sender::hasPermission)) {
            live.messages().send(sender, "warps.not-yours");
            return;
        }
        Player to = args.length >= 2 ? live.server().getPlayerExact(args[1])
                : sender instanceof Player self ? self : null;
        if (to == null) {
            live.messages().send(sender, args.length >= 2 ? "warps.not-online" : "warps.usage.token",
                    "player", args.length >= 2 ? args[1] : "");
            return;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException notANumber) {
                live.messages().send(sender, "warps.usage.token");
                return;
            }
        }
        live.tokens().give(sender, to, amount);
    }

    // ------------------------------------------------------------------------ claims' warps

    /** {@code /warp claim <claim>}: a claim's warp, by its name or owner/claim. */
    private void claim(WarpServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "warps.only-a-player");
            return;
        }
        String typed = String.join("_", java.util.Arrays.copyOfRange(args, 1, args.length));
        switch (live.claimWarps().find(typed, live.arriving(player))) {
            case ClaimWarpDirectory.Found.One one -> live.travelling().goToPlace(player,
                    one.point().name(), one.point());
            case ClaimWarpDirectory.Found.Several several -> live.messages().send(player, "warps.claim.several",
                    "name", typed, "which", String.join(", ", several.points().stream()
                            .map(point -> live.claimWarps().ownerName(point).toLowerCase(Locale.ROOT) + "/" + typed)
                            .toList()));
            case ClaimWarpDirectory.Found.None none -> live.messages().send(player, "warps.claim.unknown",
                    "name", typed);
        }
    }

    /** {@code /warp claim}, bare: the claims' warps this player may go to, as a page. */
    private void claims(WarpServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "warps.only-a-player");
            return;
        }
        live.screens().claims(player);
    }

    /** {@code /warp home <player>}: the claim that player calls home. */
    private void home(WarpServices live, CommandSender sender, String whose) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "warps.only-a-player");
            return;
        }
        OfflinePlayer owner = known(whose);
        java.util.Optional<de.raindancer.core.world.poi.Poi> home = owner == null ? java.util.Optional.empty()
                : live.claimWarps().homeOf(owner.getUniqueId(), live.arriving(player));
        if (home.isEmpty()) {
            // The same line for "has none" and "would not let you in": which it is, is theirs to know.
            live.messages().send(player, "warps.home.none", "player", whose);
            return;
        }
        live.travelling().goToPlace(player, nameOf(owner, whose) + "'s home", home.get());
    }

    /** A player the server has seen, by name — online or not. Null for somebody it never has. */
    private static OfflinePlayer known(String name) {
        OfflinePlayer online = org.bukkit.Bukkit.getPlayerExact(name);
        return online != null ? online : org.bukkit.Bukkit.getOfflinePlayerIfCached(name);
    }

    private static String nameOf(OfflinePlayer player, String typed) {
        return player.getName() == null ? typed : player.getName();
    }

    private static java.util.UUID idOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        WarpServices live = services.get();
        CommandSender sender = source.getSender();
        boolean admin = live.access().mayManage(sender::hasPermission);

        java.util.UUID id = idOf(sender);
        boolean owner = admin || live.access().mayCreate(sender::hasPermission, false)
                || !live.catalogue().ownedBy(id).isEmpty();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (args.length <= 1) {
            List<String> options = new ArrayList<>(names(live, sender));
            options.addAll(List.of("list", "help", "mine", "claim", "home"));
            if (owner) {
                options.addAll(List.of("set", "move", "delete", "label", "icon", "access", "member"));
            }
            if (admin) {
                options.addAll(List.of("admin", "config", "category", "owner", "token"));
            }
            return startingWith(options, sub);
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("claim") || sub.equals("claims")) && sender instanceof Player player) {
            java.util.function.Predicate<de.raindancer.core.world.poi.Poi> may = live.arriving(player);
            return startingWith(live.claimWarps().visible(may).stream()
                    .map(point -> live.claimWarps().typedAs(point, may)).toList(), typed);
        }
        if (args.length == 2 && sub.equals("home")) {
            return startingWith(online(live), typed);
        }
        if (args.length == 2 && admin && sub.equals("token")) {
            return startingWith(online(live), typed);
        }
        if (args.length == 2 && owner && (sub.equals("member") || sub.equals("members"))) {
            return startingWith(List.of("add", "remove"), typed);
        }
        if (args.length == 2 && owner) {
            // Every other subcommand takes a warp name second.
            return startingWith(names(live, sender), typed);
        }
        if (args.length == 3 && owner && (sub.equals("access") || sub.equals("permission"))) {
            return startingWith(admin ? List.of("everybody", "private", "staff", "own")
                    : List.of("everybody", "private"), typed);
        }
        if (args.length == 3 && admin && sub.equals("category")) {
            return startingWith(new ArrayList<>(live.catalogue()
                            .categoriesVisibleTo(idOf(sender), sender::hasPermission, live.access())),
                    typed);
        }
        if (args.length == 3 && admin && (sub.equals("owner") || sub.equals("give"))) {
            return startingWith(online(live), typed);
        }
        if (args.length == 3 && owner && (sub.equals("member") || sub.equals("members"))) {
            return startingWith(names(live, sender), typed);
        }
        if (args.length == 4 && owner && (sub.equals("member") || sub.equals("members"))) {
            return startingWith(online(live), typed);
        }
        return List.of();
    }

    private static List<String> online(WarpServices live) {
        return live.server().getOnlinePlayers().stream().map(Player::getName).toList();
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
        return live.catalogue().visibleTo(idOf(sender), sender::hasPermission, live.access()).stream()
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
