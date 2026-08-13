package de.raindancer.modules.worldgate.command;

import de.raindancer.modules.worldgate.WorldGateServices;
import de.raindancer.modules.worldgate.model.Dimension;
import de.raindancer.modules.worldgate.model.GateState;
import de.raindancer.modules.worldgate.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /worldgate} — lock, open, evacuate, or just look.
 *
 * <h2>Why one command level-gated on the low bar, and every changing subcommand gated again</h2>
 * The command itself asks for {@link PermissionNodes#STATUS} — on by default — so it still resolves
 * for an ordinary player and {@code status} works for anybody. {@code lock}, {@code open} and
 * {@code evacuate} each check {@link PermissionNodes#ADMIN} for themselves, exactly the way
 * {@code RtpCommand} checks its own {@code prepare} permission underneath the command-level
 * {@code USE} — the two checks are not redundant, they answer different questions: whether the
 * command exists for this sender at all, and whether this particular subcommand does.
 */
public final class WorldGateCommand implements IWorldGateCommand {

    private static final List<String> ADMIN_SUBCOMMANDS = List.of("status", "lock", "open", "evacuate");
    private static final List<String> DIMENSIONS = List.of("nether", "end");
    private static final List<String> LOCK_MODES = List.of("drain", "close");

    private final Supplier<WorldGateServices> services;

    /**
     * @param services asked for when the command runs, never captured — see {@link IWorldGateCommand}
     *                 on why a command built at bootstrap cannot hold anything the module built
     */
    public WorldGateCommand(Supplier<WorldGateServices> services) {
        this.services = services;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        WorldGateServices live = services.get();
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            usage(live, sender);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(live, sender);
            case "lock" -> lock(live, sender, args);
            case "open" -> open(live, sender, args);
            case "evacuate" -> evacuate(live, sender, args);
            default -> usage(live, sender);
        }
    }

    private void status(WorldGateServices live, CommandSender sender) {
        live.messages().send(sender, "worldgate.status-heading");
        for (Dimension dimension : Dimension.values()) {
            live.messages().sendPlain(sender, "worldgate.status-row",
                    "dimension", dimension.label(),
                    "state", live.gate().state(dimension).name());
        }
    }

    private void lock(WorldGateServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "worldgate.no-permission");
            return;
        }
        if (args.length < 3) {
            live.messages().send(sender, "worldgate.usage.lock");
            return;
        }
        Dimension dimension = Dimension.parse(args[1]).orElse(null);
        if (dimension == null) {
            live.messages().send(sender, "worldgate.unknown-dimension", "value", args[1]);
            return;
        }
        GateState state = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "drain" -> GateState.DRAINED;
            case "close" -> GateState.CLOSED;
            default -> null;
        };
        if (state == null) {
            live.messages().send(sender, "worldgate.unknown-lock-mode", "value", args[2]);
            return;
        }
        if (!live.gate().set(dimension, state)) {
            live.messages().send(sender, "worldgate.could-not-save");
            return;
        }
        live.messages().send(sender, state == GateState.CLOSED
                        ? "worldgate.now-closed" : "worldgate.now-drained",
                "dimension", dimension.label());
    }

    private void open(WorldGateServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "worldgate.no-permission");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "worldgate.usage.open");
            return;
        }
        Dimension dimension = Dimension.parse(args[1]).orElse(null);
        if (dimension == null) {
            live.messages().send(sender, "worldgate.unknown-dimension", "value", args[1]);
            return;
        }
        if (!live.gate().set(dimension, GateState.OPEN)) {
            live.messages().send(sender, "worldgate.could-not-save");
            return;
        }
        live.messages().send(sender, "worldgate.now-open", "dimension", dimension.label());
    }

    /**
     * A one-shot action, not a state change — see {@code WorldGateService} on why this never touches
     * the lock itself. An admin wanting both closes the dimension first (or after) as a second command.
     */
    private void evacuate(WorldGateServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "worldgate.no-permission");
            return;
        }
        if (args.length < 2) {
            live.messages().send(sender, "worldgate.usage.evacuate");
            return;
        }
        Dimension dimension = Dimension.parse(args[1]).orElse(null);
        if (dimension == null) {
            live.messages().send(sender, "worldgate.unknown-dimension", "value", args[1]);
            return;
        }
        int moved = live.gate().evacuate(dimension, live.server());
        live.messages().send(sender, "worldgate.evacuated",
                "dimension", dimension.label(), "count", moved);
    }

    private void usage(WorldGateServices live, CommandSender sender) {
        live.messages().lines("worldgate.help").forEach(sender::sendMessage);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source,
                                               String @NotNull [] args) {
        CommandSender sender = source.getSender();
        boolean admin = sender.hasPermission(PermissionNodes.ADMIN);

        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = admin ? ADMIN_SUBCOMMANDS : List.of("status");
            return startingWith(options, typed);
        }
        if (args.length == 2 && admin && !args[0].equalsIgnoreCase("status")) {
            return startingWith(DIMENSIONS, args[1].toLowerCase(Locale.ROOT));
        }
        if (args.length == 3 && admin && args[0].equalsIgnoreCase("lock")) {
            return startingWith(LOCK_MODES, args[2].toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    private static Collection<String> startingWith(List<String> options, String typed) {
        return options.stream().filter(word -> word.startsWith(typed)).toList();
    }

    @Override
    public @NotNull String permission() {
        return PermissionNodes.STATUS;
    }

    @Override
    public String describe() {
        return "check, or change, whether the Nether and the End are open";
    }
}
