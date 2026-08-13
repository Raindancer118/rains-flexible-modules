package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Joins, clicks and quits, for the one speedrun lobby.
 *
 * <h2>What a join does, and when</h2>
 * Only when both are true: {@link SpeedrunLobby#state()} is {@link SpeedrunLobbyState#READY}, <em>and</em>
 * the player is joining into the configured lobby world. Checking only the first was a real incident, not
 * a hypothetical one: on a shared server the lobby is READY almost all the time, and without the world
 * check every single join — into whatever world the server actually spawns people in — was cleared and
 * handed the two lobby items. Ordinary players lost their own gear simply by logging in. A player arriving
 * mid-run, mid-countdown, mid-pause, or into a finished round waiting to reset keeps whatever they were
 * carrying regardless, for the original reason: clearing a spectator's inventory to hand them a compass
 * and a block that does nothing useful yet would be a worse surprise than leaving them alone.
 *
 * <h2>Why the start block does not fix its own participant list</h2>
 * "Everybody currently in the lobby world" is read at the moment of the click, not kept as a
 * roster somebody joins and leaves — the same reasoning {@code FarmWorlds} scatters arrivals rather
 * than pre-registering them. A player who wandered into the lobby world without ever taking the
 * compass off it (dropped, given away) still races if they are standing there when somebody else
 * presses start; that is the whole point of a shared lobby.
 *
 * <h2>Why the inventory is cleared as soon as the countdown begins, not once the run starts</h2>
 * {@link SpeedrunLobby#beginCountdown} freezes participants for a few seconds first — see
 * {@link SpeedrunCountdown} — and there is nothing useful either item can still do once that starts:
 * the menu would only offer to reconfigure a run already under way, and the block cannot be pressed
 * twice into one run. Clearing it now rather than at zero also means the items are gone for the
 * whole countdown, not just visible props for it.
 *
 * <h2>Why movement is frozen even before the countdown</h2>
 * See {@link #onMove} — asked for explicitly, so nobody can wander off with the lobby items instead
 * of actually racing once somebody presses start.
 */
public final class SpeedrunLobbyListener implements Listener {

    private final SpeedrunLobby lobby;
    private final SpeedrunLobbyItems items;
    private final Brand brand;
    private final Messages messages;

    public SpeedrunLobbyListener(SpeedrunLobby lobby, SpeedrunLobbyItems items, Brand brand,
                                 Messages messages) {
        this.lobby = lobby;
        this.items = items;
        this.brand = brand;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (lobby.state() != SpeedrunLobbyState.READY) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals(lobby.config().worldName())) {
            return;   // anywhere else on the server is not this feature's business
        }
        items.give(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lobby.resetIfAbandoned(event.getPlayer().getUniqueId());
    }

    /**
     * Freezes anybody standing in the lobby world before a run exists to race in — asked for
     * explicitly: choosing a goal from the compass menu needs no movement, and wandering off with the
     * items before pressing the start block is not "waiting in the lobby", it is leaving it. Once
     * {@link SpeedrunLobby#beginCountdown} actually launches, {@link SpeedrunCountdown} takes over the
     * same freeze for the frozen roster it was given; this handler's job is only the gap before that —
     * {@link SpeedrunLobbyState#READY}, and nothing later, so a run once under way is never touched
     * here.
     *
     * <p>Block-quantised, the same trick {@link SpeedrunCountdown#onMove} uses, so looking around
     * still works — only an actual step is cancelled.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (lobby.state() != SpeedrunLobbyState.READY) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals(lobby.config().worldName())) {
            return;
        }
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;   // one click, one action — the off hand fires a second event for the same click
        }
        if (!event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        var held = event.getItem();
        if (items.isMenu(held)) {
            event.setCancelled(true);
            new SpeedrunLobbyMenu(lobby, messages, brand, player, null).open();
        } else if (items.isStart(held)) {
            event.setCancelled(true);
            startFromLobby(player);
        }
    }

    private void startFromLobby(Player clicker) {
        World lobbyWorld = clicker.getWorld();
        if (!lobbyWorld.getName().equals(lobby.config().worldName())) {
            messages.send(clicker, "speedrun.start.wrong-world", "world", lobby.config().worldName());
            return;
        }
        Set<UUID> present = lobbyWorld.getPlayers().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toUnmodifiableSet());
        SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(present);
        if (outcome == SpeedrunLobby.StartOutcome.STARTED) {
            for (Player racer : lobbyWorld.getPlayers()) {
                racer.getInventory().clear();
            }
            return;
        }
        messages.send(clicker, refusalKey(outcome));
    }

    private static String refusalKey(SpeedrunLobby.StartOutcome outcome) {
        return switch (outcome) {
            case NOT_READY -> "speedrun.start.not-ready";
            case NO_END_CONDITION -> "speedrun.start.no-end-condition";
            case NO_PARTICIPANTS -> "speedrun.start.no-participants";
            case WORLD_MISSING -> "speedrun.start.world-missing";
            case STARTED -> throw new IllegalStateException("STARTED is not a refusal");
        };
    }
}
