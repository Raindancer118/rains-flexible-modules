package de.raindancer.modules.essentials.listener;

import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.service.ReactionService;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.UUID;

/**
 * Puts a Congrats! button under an advancement line. Only for advancements the game announces — no
 * message means a recipe or a hidden one, or {@code announceAdvancements} off, and those stay silent.
 */
public final class AdvancementListener implements IEssentialsListener {

    private final EssentialsServices services;

    public AdvancementListener(EssentialsServices services) {
        this.services = services;
    }

    // HIGHEST, not MONITOR: the line is taken over, so it has to happen while the event can still be
    // changed; anything at MONITOR still sees the original message.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component line = event.message();
        if (line == null || !services.config().congratsButton() || !services.reactions().isClickable()) {
            return;
        }
        event.message(null);
        services.reactions().broadcast(line, event.getPlayer(), ReactionService.CONGRATS);
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody.
    }
}
