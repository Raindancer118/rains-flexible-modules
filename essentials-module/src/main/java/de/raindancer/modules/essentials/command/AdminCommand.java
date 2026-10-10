package de.raindancer.modules.essentials.command;

import de.raindancer.modules.essentials.EssentialsServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /admin} — into admin mode and back out.
 *
 * <p>No permission on the command itself: going in is checked by the service, and coming out must work
 * for somebody whose permission was taken away while they were in.
 */
public final class AdminCommand implements IEssentialsCommand {

    private final Supplier<EssentialsServices> services;

    public AdminCommand(Supplier<EssentialsServices> services) {
        this.services = services;
    }

    @Override
    public String describe() {
        return "switches to the admin inventory and back";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        EssentialsServices live = services.get();
        if (!(source.getSender() instanceof Player player)) {
            live.messages().send(source.getSender(), "essentials.only-a-player");
            return;
        }
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean in = live.adminMode().isInAdminMode(player.getUniqueId());
        switch (word) {
            case "" -> live.adminMode().toggle(player);
            case "on" -> {
                if (in) {
                    live.messages().send(player, "essentials.admin.already-in");
                } else {
                    live.adminMode().enter(player);
                }
            }
            case "off" -> live.adminMode().leave(player);
            case "status" -> live.messages().send(player, in ? "essentials.admin.status-in" : "essentials.admin.status-out");
            case "bypass" -> {
                if (!player.hasPermission(de.raindancer.modules.essentials.util.PermissionNodes.ADMIN_BYPASS)) {
                    live.messages().send(player, "essentials.no-permission");
                } else if (!live.adminMode().toggleBypass(player)) {
                    live.messages().send(player, "essentials.admin.bypass-not-in");
                } else {
                    boolean on = live.adminMode().bypassing(player.getUniqueId());
                    live.core().audit().record(de.raindancer.core.moderation.audit.AuditEntry.of("essentials",
                            on ? "switched admin-mode bypass on" : "switched admin-mode bypass off")
                            .by(player.getUniqueId(), player.getName()));
                    live.messages().send(player, on ? "essentials.admin.bypass-on" : "essentials.admin.bypass-off");
                }
            }
            default -> live.messages().send(player, "essentials.usage", "usage", "/admin [on|off|status|bypass]");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return args.length > 1 ? List.of()
                : List.of("on", "off", "status", "bypass").stream().filter(word -> word.startsWith(typed)).toList();
    }
}
