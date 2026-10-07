package de.raindancer.modules.veintoggle.command;

import de.raindancer.modules.veintoggle.VeinToggleServices;
import de.raindancer.modules.veintoggle.util.PermissionNodes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /vein} — switches Veinminer on or off for yourself; {@code /vein on|off|status}; staff add a
 * name: {@code /vein <player> [on|off]}.
 */
public final class VeinCommand implements BasicCommand {

    private final Supplier<VeinToggleServices> services;

    public VeinCommand(Supplier<VeinToggleServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        VeinToggleServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
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

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("on", "off", "status"));
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
