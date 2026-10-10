package de.raindancer.modules.economy.listener;

import de.raindancer.modules.economy.EconomyServices;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.UUID;

/** Advancements that announce themselves pay, the harder ones more; recipes and hidden ones do not. */
public final class RewardListener implements IEconomyListener {

    private final EconomyServices services;

    public RewardListener(EconomyServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) {
            return;
        }
        services.rewards().advanced(event.getPlayer(),
                PlainTextComponentSerializer.plainText().serialize(display.title()), display.frame());
    }

    /** Back pay waiting is mentioned a little after joining, once the join messages have gone by. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        de.raindancer.core.platform.util.Scheduling.entityLater(services.plugin(), player, 20L * 12,
                () -> services.backpay().remind(player));
    }

    @Override
    public void forget(UUID player) {
        // The hourly windows forget themselves when their hour is up.
    }

    @Override
    public String describe() {
        return "advancements made";
    }
}
