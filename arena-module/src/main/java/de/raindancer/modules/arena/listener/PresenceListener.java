package de.raindancer.modules.arena.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.service.IntentBook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Whoever arrives from the survival server starts in the hub with empty hands, and then gets what they came for — the
 * queue, a mob run, a fight they agreed to. Arriving and leaving are not announced: on a network they are going to and
 * coming from the other server, not joining and quitting, and the survival side says that itself.
 */
public final class PresenceListener implements IArenaListener {

    private final ArenaServices services;

    public PresenceListener(ArenaServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();
        // A tick later, on their own thread: the join is not the moment to teleport.
        Scheduling.entityLater(services.plugin(), player, 1L, () -> {
            if (!player.isOnline()) {
                return;
            }
            var announced = services.intents().take(player.getUniqueId());
            services.arena().arrived(player, announced.isPresent());
            announced.ifPresent(intent -> act(player, intent));
        });
        // Later, so it is not lost among the arrival lines: the fights waiting for more people.
        Scheduling.entityLater(services.plugin(), player, 40L, () -> {
            if (player.isOnline() && !services.matches().inFight(player.getUniqueId())) {
                services.queue().tellOpen(player);
            }
        });
    }

    private void act(Player player, IntentBook.Intent intent) {
        switch (intent.kind()) {
            case HUB -> {
            }
            case QUEUE, WAVES -> services.queue().join(player, intent.spec());
            case AGREED -> services.invites().arrived(intent.agreed(), player.getUniqueId())
                    .ifPresentOrElse(services.queue()::reserve,
                            () -> services.messages().send(player, "arena.invite.waiting"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        UUID id = event.getPlayer().getUniqueId();
        services.queue().forget(id);
        services.invites().forget(id);
        services.rematches().forget(id);
        services.arena().release(id);
        services.arena().forgetOrigin(id);
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered about anybody here.
    }
}
