package de.raindancer.modules.speedrun.manhunt.command;

import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.speedrun.util.TargetPick;
import de.raindancer.modules.speedrun.manhunt.ManhuntServices;
import de.raindancer.modules.speedrun.manhunt.util.PermissionNodes;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.speedrun.SpeedrunLobby;
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
            onGlobal(live, () -> openOrClose(live, sender, true));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("close")) {
            onGlobal(live, () -> openOrClose(live, sender, false));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            onGlobal(live, () -> clear(live, sender));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("vip")) {
            onGlobal(live, () -> vip(live, sender, args));
            return;
        }
        onGlobal(live, () -> passthrough(live, sender, args));
    }

    /**
     * {@code /whitelist clear} — everybody off the list except the VIPs, with the door left exactly
     * as open or shut as it was. See {@code ManhuntWhitelistService.clear} for why the flag is not
     * part of this.
     */
    private void clear(ManhuntServices live, CommandSender sender) {
        if (!PermissionNodes.mayEditWhitelist(sender)) {
            // The door node opens and closes; changing who is on the list is an admin's.
            live.messages().send(sender, "manhunt.whitelist.admin-only");
            return;
        }
        int removed = live.whitelist().clear();
        live.messages().send(sender, "manhunt.whitelist.cleared",
                "removed", String.valueOf(removed),
                "vips", String.valueOf(live.whitelist().vips().size()));
    }

    /** {@code /whitelist vip add|remove|list} — who a clear spares and a close always lets in. */
    private void vip(ManhuntServices live, CommandSender sender, String[] args) {
        if (!PermissionNodes.mayEditWhitelist(sender)) {
            // The door node opens and closes; changing who is on the list is an admin's.
            live.messages().send(sender, "manhunt.whitelist.admin-only");
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
        Vip vip = resolve(live, sender, args[2]);
        if (vip == null) {
            return;
        }
        UUID who = vip.id();
        String name = vip.name();
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

    /** Who a VIP command means, with the real name to show and to store — never the nickname that was typed. */
    private record Vip(UUID id, String name) {
    }

    /**
     * A typed name, nickname or selector to the player behind it, without ever asking Mojang, or null
     * after saying why not.
     *
     * <p>Real names win over nicknames: when {@code typed} is not a name the server knows, this
     * module's own VIP list is asked before a nickname may answer — it is the only thing that can still
     * name somebody made a VIP long ago and not seen since, exactly who {@code vip remove} is about.
     * What is deliberately <em>not</em> here is {@code Bukkit.getOfflinePlayer(String)}: on an unknown
     * name it blocks on a web request and invents an id.
     */
    private Vip resolve(ManhuntServices live, CommandSender sender, String typed) {
        PlayerLookup found = PlayerTargets.lookup(Bukkit.getServer(), sender, typed);
        if (found.kind() == PlayerLookup.Kind.NONE || found.kind() == PlayerLookup.Kind.NICKNAME) {
            Optional<UUID> listed = live.whitelist().vips().byName(typed);
            if (listed.isPresent()) {
                return new Vip(listed.get(), typed);
            }
        }
        TargetPick pick = TargetPick.anyone(Bukkit.getServer(), sender, typed);
        if (pick.tell(live.messages(), sender, ManhuntCommand.PICK_KEYS)) {
            return null;
        }
        OfflinePlayer who = pick.who();
        String real = who.getName();
        return new Vip(who.getUniqueId(),
                real != null ? real : who.getUniqueId().toString().substring(0, 8));
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
        Runnable dispatch = () -> {
            try {
                Bukkit.dispatchCommand(sender, command);
            } catch (CommandException badWord) {
                live.messages().send(sender, "manhunt.whitelist.unknown-command",
                        "word", args.length == 0 ? "" : args[0]);
            }
        };
        dispatch.run();
    }

    /**
     * Folia: the server's whitelist is global state, and a command lands on the sender's region thread.
     * Everything this command does to it — and vanilla's own /whitelist — runs on the global region,
     * the one thread that owns it. On Paper that is the thread it is already on.
     */
    private static void onGlobal(ManhuntServices live, Runnable work) {
        SpeedrunLobby lobby = live.lobby().get();
        if (Scheduling.isFolia() && lobby != null) {
            Scheduling.global(lobby.plugin(), work);
        } else {
            work.run();
        }
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            boolean editor = PermissionNodes.mayEditWhitelist(source.getSender());
            return List.of("open", "close", "clear", "vip", "add", "remove", "list", "on", "off",
                            "reload").stream()
                    .filter(word -> editor || !(word.equals("clear") || word.equals("vip")))
                    .filter(word -> word.startsWith(typed))
                    .toList();
        }
        if (args[0].equalsIgnoreCase("vip")) {
            if (!PermissionNodes.mayEditWhitelist(source.getSender())) {
                return List.of();
            }
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
                return PlayerTargets.suggest(Bukkit.getServer(), source.getSender(), args[2], who -> true);
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
