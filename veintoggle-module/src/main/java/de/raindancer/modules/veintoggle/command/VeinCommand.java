package de.raindancer.modules.veintoggle.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.veintoggle.VeinToggleServices;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import de.raindancer.modules.veintoggle.service.VeinUndoService;
import de.raindancer.modules.veintoggle.util.PermissionNodes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /vein} — switches Veinminer on or off for yourself; {@code /vein on|off|status}; staff add a
 * name: {@code /vein <player> [on|off]}. {@code /vein undo} puts your last vein back.
 */
public final class VeinCommand implements BasicCommand {

    /** How close somebody has to be to the vein to undo it: the drops lying there are part of the price. */
    static final double UNDO_REACH = 32.0;

    private static final UndoRule UNDO = new UndoRule();

    private final Supplier<VeinToggleServices> services;

    public VeinCommand(Supplier<VeinToggleServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        VeinToggleServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.equals("undo")) {
            if (!(sender instanceof Player self)) {
                live.messages().send(sender, "veintoggle.only-a-player");
            } else if (!sender.hasPermission(PermissionNodes.UNDO)) {
                live.messages().send(sender, "veintoggle.undo-not-allowed");
            } else {
                undo(live, self);
            }
            return;
        }
        if (args.length == 0 || first.equals("on") || first.equals("off") || first.equals("status")) {
            if (!(sender instanceof Player self)) {
                live.messages().send(sender, "veintoggle.only-a-player");
                return;
            }
            apply(live, sender, self, first);
            return;
        }
        if (!sender.hasPermission(PermissionNodes.OTHERS)) {
            live.messages().send(sender, "veintoggle.usage");
            return;
        }
        Player target = live.server().getPlayerExact(args[0]);
        if (target == null) {
            live.messages().send(sender, "veintoggle.not-online", "player", args[0]);
            return;
        }
        apply(live, sender, target, args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "");
    }

    private void apply(VeinToggleServices live, CommandSender sender, Player target, String wanted) {
        boolean on = switch (wanted) {
            case "on" -> {
                live.veins().set(target, true);
                yield true;
            }
            case "off" -> {
                live.veins().set(target, false);
                yield false;
            }
            case "status" -> live.wantsVeins(target);
            default -> live.veins().toggle(target);
        };
        boolean self = sender == target;
        String key = wanted.equals("status") ? (on ? "veintoggle.status-on" : "veintoggle.status-off")
                : (on ? "veintoggle.switched-on" : "veintoggle.switched-off");
        if (self) {
            live.messages().send(sender, key);
        } else {
            live.messages().send(sender, on ? "veintoggle.others-on" : "veintoggle.others-off",
                    "player", target.getName());
            if (!wanted.equals("status")) {
                live.messages().send(target, key);
            }
        }
        if (!live.veinminerInstalled()) {
            live.messages().send(sender, "veintoggle.no-veinminer");
        }
    }

    private void undo(VeinToggleServices live, Player player) {
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
        // The vein's region does the work: its blocks and the drops lying there belong to that thread.
        Scheduling.region(live.plugin(), there, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!live.server().isOwnedByCurrentRegion(player)) {
                live.messages().send(player, "veintoggle.undo-too-far");
                return;
            }
            tell(live, player, live.undo().undo(player, vein));
        });
    }

    private static void tell(VeinToggleServices live, Player player, VeinUndoService.Outcome outcome) {
        int total = outcome.restored() + outcome.inTheWay() + outcome.unpaid();
        if (total == 0) {
            live.messages().send(player, "veintoggle.undo-nothing", "seconds",
                    String.valueOf(live.settings().get().undoWithinSeconds()));
            return;
        }
        if (outcome.restored() == total) {
            live.messages().send(player, "veintoggle.undo-done");
            return;
        }
        if (outcome.restored() == 0) {
            live.messages().send(player, "veintoggle.undo-none");
        } else {
            live.messages().send(player, "veintoggle.undo-partly", "blocks", String.valueOf(outcome.restored()),
                    "total", String.valueOf(total));
        }
        if (outcome.inTheWay() > 0) {
            live.messages().send(player, "veintoggle.undo-in-the-way", "count", String.valueOf(outcome.inTheWay()));
        }
        if (outcome.unpaid() > 0) {
            live.messages().send(player, "veintoggle.undo-unpaid", "count", String.valueOf(outcome.unpaid()));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("on", "off", "status"));
            if (sender.hasPermission(PermissionNodes.UNDO)) {
                options.add("undo");
            }
            if (sender.hasPermission(PermissionNodes.OTHERS)) {
                services.get().server().getOnlinePlayers().forEach(player -> options.add(player.getName()));
            }
        } else if (args.length == 2 && sender.hasPermission(PermissionNodes.OTHERS)) {
            options.addAll(List.of("on", "off", "status"));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }
}
