package de.raindancer.modules.roles.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.roles.model.Ownership;
import de.raindancer.modules.roles.service.RoleShop;
import de.raindancer.modules.roles.RolesServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.UUID;

/** A nudge for whoever has no role yet, and staff's bypass dropped when they leave. */
public final class RoleListener implements IRolesListener {

    /** Long enough for the join messages to have scrolled past. */
    private static final long REMIND_AFTER_TICKS = 20L * 8;

    private final RolesServices services;

    public RoleListener(RolesServices services) {
        this.services = services;
    }

    /** Takes rent that has come due and says what happened; the one place rent is collected from. */
    public static void collectRent(RolesServices services, Player player) {
        for (RoleShop.RentEvent event : services.shop().collect(player.getUniqueId())) {
            if (event.paid()) {
                services.messages().send(player, "roles.rent-paid", "role", new Markup(event.role().coloured()),
                        "amount", Fees.format(event.amount()));
            } else {
                services.messages().send(player, "roles.rent-lapsed", "role", new Markup(event.role().coloured()),
                        "id", event.role().id());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Scheduling.entityLater(services.plugin(), player, 20L * 3, () -> {
            if (!player.isOnline()) {
                return;
            }
            collectRent(services, player);
            for (Ownership due : services.shop().dueSoon(player.getUniqueId())) {
                services.roles().role(due.role()).ifPresent(role -> services.messages().send(player, "roles.rent-warning",
                        "role", new Markup(role.coloured()), "amount",
                        Fees.format(Fees.quote(RoleShop.RENT, role.rentPrice())),
                        "left", Times.describe(Duration.ofMillis(Math.max(0, due.dueAt() - System.currentTimeMillis())))));
            }
        });
        if (!services.settings().get().remindOnJoin() || services.roles().roles().isEmpty()
                || services.roles().choiceOf(player.getUniqueId()).isPresent()) {
            return;
        }
        Scheduling.entityLater(services.plugin(), player, REMIND_AFTER_TICKS, () -> {
            if (player.isOnline() && services.roles().choiceOf(player.getUniqueId()).isEmpty()) {
                services.messages().send(player, "roles.no-role-yet");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.roles().forget(player);
    }
}
