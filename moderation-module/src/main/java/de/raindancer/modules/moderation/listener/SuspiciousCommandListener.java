package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.UUID;

/**
 * Watching what players type, for the one thing worth watching it for: a command that only makes sense
 * alongside an outside tool.
 *
 * <h2>Why {@code MONITOR} and why nothing here is ever cancelled</h2>
 * This decides nothing about whether the command runs — flagging is not refusing, and a player who is
 * about to be reported should not learn that from their command suddenly failing. {@code MONITOR}
 * priority means every other plugin has already had its say by the time this only watches.
 */
public final class SuspiciousCommandListener implements IModerationListener {

    /**
     * Exempts an account from every kind of automatic flagging this module does — suspicious
     * commands and the x-ray watch alike. One node rather than one per detector: the reason to hold
     * it is the same in both cases, "trusted, do not auto-report this account", and a second node
     * would only be one more thing to remember to grant a builder or a tester.
     */
    public static final String BYPASS = "rainsmoderation.suspicious.bypass";

    private final ModerationServices services;

    public SuspiciousCommandListener(ModerationServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        services.suspiciousCommands().check(player.getUniqueId(), player.getName(), event.getMessage());
    }

    @Override
    public void forget(UUID player) {
        services.suspiciousCommands().forget(player);
    }

    @Override
    public String describe() {
        return "watching for a typed command that suggests an outside tool";
    }
}
