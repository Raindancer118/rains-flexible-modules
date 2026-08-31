package de.raindancer.modules.manhunt.command;

import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
        passthrough(sender, args);
    }

    private void openOrClose(ManhuntServices live, CommandSender sender, boolean open) {
        if (!sender.hasPermission(PermissionNodes.WHITELIST)) {
            live.messages().send(sender, "manhunt.not-yours");
            return;
        }
        if (open) {
            live.whitelist().open();
            if (sender instanceof org.bukkit.entity.Player opener) {
                live.achievements().awardOpenDoors(opener);
            }
            live.messages().send(sender, "manhunt.whitelist.opened");
            return;
        }
        int added = live.whitelist().close();
        if (sender instanceof org.bukkit.entity.Player closer) {
            live.achievements().awardGatekeeper(closer);
        }
        live.messages().send(sender, "manhunt.whitelist.closed", "added", String.valueOf(added));
    }

    /** Everything that is not {@code open} or {@code close}, unchanged, straight to vanilla. */
    private void passthrough(CommandSender sender, String[] args) {
        String command = args.length == 0
                ? "minecraft:whitelist"
                : "minecraft:whitelist " + String.join(" ", args);
        Bukkit.dispatchCommand(sender, command);
    }

    // ------------------------------------------------------------------------ completion

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("open", "close", "add", "remove", "list", "on", "off", "reload").stream()
                    .filter(word -> word.startsWith(typed))
                    .toList();
        }
        // Beyond the first word this is no longer open/close territory, and there is no public API
        // here to hand completion to vanilla's own command the way execute() hands it the run
        // itself — Bukkit exposes no CommandMap accessor. Offering nothing is honest; offering a
        // guess (player names, say) would be wrong for `list`/`reload`, which take none at all.
        return List.of();
    }

    @Override
    public String describe() {
        return "open/close the server whitelist for a hunt; every other word passes through to vanilla";
    }
}
