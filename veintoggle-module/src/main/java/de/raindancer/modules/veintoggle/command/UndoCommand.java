package de.raindancer.modules.veintoggle.command;

import de.raindancer.modules.veintoggle.VeinToggleServices;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import de.raindancer.modules.veintoggle.util.PermissionNodes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /ctrl-z} (and {@code /veinundo}, {@code /vein undo}) — puts your last vein back;
 * {@code pay} settles the bill for what nobody could give back, {@code cancel} declines it.
 */
public final class UndoCommand implements BasicCommand {

    /** How close somebody has to be to the vein to undo it: the drops lying there are part of the price. */
    static final double UNDO_REACH = 32.0;

    private static final UndoRule UNDO = new UndoRule();

    private final Supplier<VeinToggleServices> services;

    public UndoCommand(Supplier<VeinToggleServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        run(services.get(), source.getSender(), args.length == 0 ? "" : args[0]);
    }

    /** Shared with {@code /vein undo}. */
    static void run(VeinToggleServices live, CommandSender sender, String word) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "veintoggle.only-a-player");
            return;
        }
        if (!sender.hasPermission(PermissionNodes.UNDO)) {
            live.messages().send(sender, "veintoggle.undo-not-allowed");
            return;
        }
        switch (word.toLowerCase(Locale.ROOT)) {
            case "pay" -> live.undo().pay(player);
            case "cancel" -> live.undo().decline(player);
            case "" -> undo(live, player);
            default -> live.messages().send(sender, "veintoggle.undo-usage");
        }
    }

    private static void undo(VeinToggleServices live, Player player) {
        int seconds = live.settings().get().undoWithinSeconds();
        if (seconds <= 0) {
            live.messages().send(player, "veintoggle.undo-off");
            return;
        }
        long now = System.currentTimeMillis();
        Optional<VeinOperation> latest = live.history().latest(player.getUniqueId(), now, seconds * 1000L);
        if (latest.isEmpty()) {
            live.messages().send(player, "veintoggle.undo-nothing", "seconds", String.valueOf(seconds));
            return;
        }
        VeinOperation vein = latest.get();
        if (!UNDO.settled(vein.changedAt(), now)) {
            live.messages().send(player, "veintoggle.undo-still-falling");
            return;
        }
        World world = live.server().getWorld(vein.source().world());
        Location there = world == null ? null : vein.source().centre(world);
        if (there == null || !world.equals(player.getWorld())
                || player.getLocation().distanceSquared(there) > UNDO_REACH * UNDO_REACH) {
            live.messages().send(player, "veintoggle.undo-too-far");
            return;
        }
        live.undo().start(player, vein);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length > 1) {
            return List.of();
        }
        return List.of("pay", "cancel").stream().filter(option -> option.startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.UNDO;
    }
}
