package de.raindancer.modules.essentials.listener;

import de.raindancer.modules.essentials.EssentialsServices;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Input;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Everything that happens because somebody is here: joining, leaving, AFK, and the nickname they
 * are shown by.
 *
 * <h2>Why one listener rather than one per feature</h2>
 * Join and quit are read by four different things — the welcome line, the AFK tracker, the
 * messaging service's own session state, and the nickname being redrawn — and Paper delivers each
 * event once. Splitting that into four listeners is four places to keep in step about which one
 * runs first, for no reader that benefits from it.
 */
public final class EssentialsSessionListener implements IEssentialsListener {

    private final EssentialsServices services;

    public EssentialsSessionListener(EssentialsServices services) {
        this.services = services;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        boolean firstJoin = !event.getPlayer().hasPlayedBefore();
        if (services.welcome().ownsJoinQuitLines() || firstJoin) {
            event.joinMessage(null);
        }
        services.afk().activity(event.getPlayer());
        services.nicknames().apply(event.getPlayer());
        services.welcome().joined(event.getPlayer(), firstJoin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (services.welcome().ownsJoinQuitLines()) {
            event.quitMessage(null);
            services.welcome().quit(event.getPlayer());
        }
        forget(event.getPlayer().getUniqueId());
    }

    /**
     * Pressing a movement key counts as activity; being moved counts for nothing on its own — a
     * piston, an explosion, a knockback, a boat drifting on current, or {@code /tp} all move a
     * player without them touching anything, and {@code PlayerMoveEvent} cannot tell any of those
     * apart from walking. {@code PlayerInputEvent} can: it fires only from the client's own input
     * packet, which an outside force never sends. Turning your head does not count either, for the
     * same old reason — Core's own teleport warm-up draws the same line: somebody reading a book
     * while standing still should not be shaken out of being AFK by nothing at all.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInput(PlayerInputEvent event) {
        Input input = event.getInput();
        if (input.isForward() || input.isBackward() || input.isLeft() || input.isRight()
                || input.isJump() || input.isSneak() || input.isSprint()) {
            services.afk().activity(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        services.afk().activity(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        services.afk().activity(event.getPlayer());
    }

    @Override
    public void forget(UUID player) {
        services.afk().forget(player);
        services.messaging().forget(player);
    }
}
