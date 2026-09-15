package de.raindancer.modules.manhunt.command;

import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /whitelist} — takes over the bare name vanilla's own whitelist command answers to, so
 * {@code open} and {@code close} are the actual words a Runner types, exactly as asked for, rather
 * than a Manhunt-specific alias nobody remembers.
 *
 * <h2>Why this is safe to claim the bare name for</h2>
 * <b>Only {@code open} and {@code close} are new.</b> Every other word — {@code add}, {@code remove},
 * {@code list}, {@code on}, {@code off}, {@code reload}, or nothing at all — is handed straight to
 * {@code minecraft:whitelist} through {@link #passthrough}, unchanged, so every admin workflow that
 * already exists on a server keeps working exactly as it did before this module was installed. Vanilla
 * itself is still reachable directly at {@code /minecraft:whitelist} regardless, the way any command a
 * plugin overrides always stays reachable — this is only about which answer the bare, muscle-memory
 * word gives.
 *
 * <h2>Why {@code open}/{@code close} are not gated by {@code bukkit.command.whitelist}</h2>
 * That is vanilla's own admin-only node, and the entire point here — asked for directly — is that a
 * Runner can open and close the server's doors around a hunt <em>without</em> being handed full
 * whitelist administration (adding, removing or listing anybody by name). So these two check
 * {@link PermissionNodes#WHITELIST} instead, a node of this module's own, {@code OP} by default but
 * meant to be handed to Runners on a server that wants them running the show. The passthrough
 * subcommands are not re-gated at all here — {@code Bukkit.dispatchCommand} enforces vanilla's own
 * permission for whichever of them actually ran, the same as if this command did not exist.
 */
public final class WhitelistCommand implements IManhuntCommand {

    private final Supplier<ManhuntServices> services;

    public WhitelistCommand(Supplier<ManhuntServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        ManhuntServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 1 && args[0].equalsIgnoreCase("open")) {
            openOrClose(live, sender, true);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("close")) {
            openOrClose(live, sender, false);
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            clear(live, sender);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("vip")) {
            vip(live, sender, args);
            return;
        }
        passthrough(live, sender, args);
    }

    /**
     * {@code /whitelist clear} — everybody off the list except the VIPs, with the door left exactly
     * as open or shut as it was. See {@code ManhuntWhitelistService.clear} for why the flag is not
     * part of this.
     */
    private void clear(ManhuntServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.WHITELIST)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        int removed = live.whitelist().clear();
        live.messages().send(sender, "manhunt.whitelist.cleared",
                "removed", String.valueOf(removed),
                "vips", String.valueOf(live.whitelist().vips().size()));
    }

    /** {@code /whitelist vip add|remove|list} — who a clear spares and a close always lets in. */
    private void vip(ManhuntServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.WHITELIST)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        String word = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (word.equals("list")) {
            List<String> names = live.whitelist().vips().names();
            if (names.isEmpty()) {
                live.messages().send(sender, "manhunt.whitelist.vip.none");
            } else {
                live.messages().send(sender, "manhunt.whitelist.vip.list",
                        "players", String.join(", ", names));
            }
            return;
        }
        if (args.length < 3 || !(word.equals("add") || word.equals("remove"))) {
            live.messages().send(sender, "manhunt.whitelist.vip.usage");
            return;
        }
        String name = args[2];
        UUID who = resolve(live, name).orElse(null);
        if (who == null) {
            live.messages().send(sender, "manhunt.no-such-player", "player", name);
            return;
        }
        if (word.equals("add")) {
            boolean fresh = live.whitelist().addVip(who, name);
            live.messages().send(sender,
                    fresh ? "manhunt.whitelist.vip.added" : "manhunt.whitelist.vip.already",
                    "player", name);
            return;
        }
        boolean was = live.whitelist().removeVip(who);
        live.messages().send(sender,
                was ? "manhunt.whitelist.vip.removed" : "manhunt.whitelist.vip.not-one",
                "player", name);
    }

    /**
     * A typed name to the id behind it, without ever asking Mojang.
     *
     * <h2>Why three places are tried, in this order</h2>
     * Online is the certain answer and the common one. The server's own cache is next, which is what
     * makes {@code vip add} work for somebody who has played here before but is not on right now.
     * Last is this module's own VIP list, which is the only one of the three that can still answer
     * for somebody who was made a VIP long ago and has not been seen since — exactly the person
     * {@code vip remove} is usually about. What is deliberately <em>not</em> here is
     * {@code Bukkit.getOfflinePlayer(String)}: on a name the server has never seen it blocks the
     * calling thread on a web request and then invents an id for a player who may not exist.
     */
    private Optional<UUID> resolve(ManhuntServices live, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return Optional.of(cached.getUniqueId());
        }
        return live.whitelist().vips().byName(name);
    }

    private void openOrClose(ManhuntServices live, CommandSender sender, boolean open) {
        if (!sender.hasPermission(PermissionNodes.WHITELIST)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (open) {
            live.whitelist().open();
            live.messages().send(sender, "manhunt.whitelist.opened");
            return;
        }
        int added = live.whitelist().close();
        live.messages().send(sender, "manhunt.whitelist.closed", "added", String.valueOf(added));
    }

    /**
     * Everything that is not {@code open} or {@code close}, unchanged, straight to vanilla.
     *
     * <p>A word that is neither this module's ({@code open}/{@code close}) nor vanilla's own
     * ({@code add}/{@code remove}/{@code list}/{@code on}/{@code off}/{@code reload}) — {@code enable},
     * say — reaches {@link Bukkit#dispatchCommand} and fails to parse there. A directly-typed command's
     * own top-level dispatcher catches exactly that and shows a clean usage line; going through
     * {@code dispatchCommand} from inside another command does not get the same treatment; the
     * unhandled {@link CommandException} would otherwise reach the console as a full stack trace over
     * what is, from wherever it was typed, a plain typo.
     */
    private void passthrough(ManhuntServices live, CommandSender sender, String[] args) {
        String command = args.length == 0
                ? "minecraft:whitelist"
                : "minecraft:whitelist " + String.join(" ", args);
        try {
            Bukkit.dispatchCommand(sender, command);
        } catch (CommandException badWord) {
            live.messages().send(sender, "manhunt.whitelist.unknown-command",
                    "word", args.length == 0 ? "" : args[0]);
        }
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("open", "close", "clear", "vip", "add", "remove", "list", "on", "off",
                            "reload").stream()
                    .filter(word -> word.startsWith(typed))
                    .toList();
        }
        if (args[0].equalsIgnoreCase("vip")) {
            if (args.length == 2) {
                String typed = args[1].toLowerCase(Locale.ROOT);
                return List.of("add", "remove", "list").stream()
                        .filter(word -> word.startsWith(typed))
                        .toList();
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
                // This module's own list, not the server's: removing a VIP is usually about somebody
                // who is not here, and those are exactly the names nothing else can complete.
                String typed = args[2].toLowerCase(Locale.ROOT);
                return services.get().whitelist().vips().names().stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                        .limit(50)
                        .toList();
            }
            if (args.length == 3) {
                String typed = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                        .limit(50)
                        .toList();
            }
            return List.of();
        }
        // Beyond the first word this is no longer open/close territory, and there is no public API
        // here to hand completion to vanilla's own command the way execute() hands it the run
        // itself — Bukkit exposes no CommandMap accessor. Offering nothing is honest; offering a
        // guess (player names, say) would be wrong for `list`/`reload`, which take none at all.
        return List.of();
    }

    @Override
    public String describe() {
        return "open/close/clear the server whitelist for a hunt, and keep a VIP list a clear spares; "
                + "every other word passes through to vanilla";
    }
}
