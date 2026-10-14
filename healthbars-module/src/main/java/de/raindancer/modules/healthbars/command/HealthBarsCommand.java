package de.raindancer.modules.healthbars.command;

import de.raindancer.modules.healthbars.HealthBarsServices;
import de.raindancer.modules.healthbars.rules.HealthStyle;
import net.kyori.adventure.text.minimessage.MiniMessage;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /healthbars} (or {@code /hp}) — switches the health bars you see on or off; {@code on|off|status}; {@code style hearts|bar}. */
public final class HealthBarsCommand implements BasicCommand {

    private final Supplier<HealthBarsServices> services;

    public HealthBarsCommand(Supplier<HealthBarsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        HealthBarsServices live = services.get();
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "healthbars.only-a-player");
            return;
        }
        String wanted = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (wanted.equals("style")) {
            HealthStyle style = args.length > 1 ? HealthStyle.of(args[1]) : live.bars().style(player) == HealthStyle.HEARTS
                    ? HealthStyle.BAR : HealthStyle.HEARTS;
            live.bars().style(player, style);
            live.messages().send(sender, "healthbars.style", "example", MiniMessage.miniMessage().serialize(
                    live.bars().preview(player, player)));
            return;
        }
        boolean on = switch (wanted) {
            case "on" -> {
                live.bars().wants().set(player, true);
                yield true;
            }
            case "off" -> {
                live.bars().wants().set(player, false);
                yield false;
            }
            case "status" -> live.bars().wants().isOn(player);
            case "" -> live.bars().wants().toggle(player);
            default -> {
                live.messages().send(sender, "healthbars.usage");
                yield live.bars().wants().isOn(player);
            }
        };
        if (wanted.equals("status")) {
            live.messages().send(sender, on ? "healthbars.status-on" : "healthbars.status-off");
        } else if (!wanted.equals("on") && !wanted.equals("off") && !wanted.isEmpty()) {
            return;
        } else {
            live.messages().send(sender, on ? "healthbars.switched-on" : "healthbars.switched-off");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            return List.of("on", "off", "status", "style").stream().filter(option -> option.startsWith(typed)).toList();
        }
        return args.length == 2 && args[0].equalsIgnoreCase("style")
                ? List.of("hearts", "bar").stream().filter(option -> option.startsWith(typed)).toList() : List.of();
    }
}
