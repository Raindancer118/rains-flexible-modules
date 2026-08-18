package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Joins, clicks and quits, for the one speedrun lobby.
 *
 * <h2>Why every join lands in the lobby world</h2>
 * This module is only ever installed on a dedicated event server built around one thing — asked for
 * explicitly, and the opposite of the incident that split this module out of RainsCore in the first
 * place (see {@link SpeedrunModule}'s own javadoc): there, a join handler cleared everyone's inventory
 * server-wide, on a plugin every install carries, because it never checked where a player actually was.
 * Here the whole point of the server is this lobby, so {@link #onJoin} sends anybody who joins anywhere
 * else there — {@link #onWorldChange} is what actually hands out the two lobby items once they land,
 * the same check {@link #onJoin} used to do inline for somebody who logged out already standing there.
 *
 * <h2>What handing out the items does, and when</h2>
 * Only when both are true: {@link SpeedrunLobby#state()} is {@link SpeedrunLobbyState#READY}, <em>and</em>
 * the player is standing in the configured lobby world. A player arriving mid-run, mid-countdown,
 * mid-pause, or into a finished round waiting to reset keeps whatever they were carrying regardless, for
 * the original reason: clearing a spectator's inventory to hand them a compass and a block that does
 * nothing useful yet would be a worse surprise than leaving them alone.
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

    private final Plugin plugin;
    private final SpeedrunLobby lobby;
    private final SpeedrunLobbyItems items;
    private final Brand brand;
    private final Messages messages;

    public SpeedrunLobbyListener(Plugin plugin, SpeedrunLobby lobby, SpeedrunLobbyItems items,
                                 Brand brand, Messages messages) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.items = items;
        this.brand = brand;
        this.messages = messages;
    }

    /**
     * Sends anybody who joins into some other world straight to the lobby's — see the class javadoc
     * for why this server-wide reach is deliberate here and was exactly the mistake elsewhere. A
     * player who logged out already standing in the lobby world needs no teleport; this only hands out
     * the items for that case; {@link #onWorldChange} does it for everyone the teleport actually moves.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getName().equals(lobby.config().worldName())) {
            giveItemsIfReady(player);
            return;
        }
        World lobbyWorld = Bukkit.getWorld(lobby.config().worldName());
        if (lobbyWorld != null) {
            player.teleportAsync(lobbyWorld.getSpawnLocation());
        }
    }

    /** What {@link #onJoin}'s teleport lands on, and what {@code /speedrun} lands on too. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        giveItemsIfReady(event.getPlayer());
    }

    private void giveItemsIfReady(Player player) {
        if (lobby.state() != SpeedrunLobbyState.READY) {
            return;
        }
        if (!player.getWorld().getName().equals(lobby.config().worldName())) {
            return;   // anywhere else on the server is not this feature's business
        }
        items.give(player);
    }

    /**
     * Hands the items to everybody already standing in the lobby world the moment it becomes usable
     * again — registered with {@link SpeedrunLobby#onReady}. Neither {@link #onJoin} nor
     * {@link #onWorldChange} fires for somebody who arrived earlier and simply stayed while a run
     * finished and the world reset around them; without this they would be stuck with empty hands
     * until they left and came back.
     *
     * <p>Folia: a reset finishes on the global region thread, and clearing somebody's inventory from
     * there throws — unlike {@link #onJoin} and {@link #onWorldChange}, which already arrive on the
     * thread owning the player they are about. Hence the hop per player.
     */
    void giveItemsToEveryoneInLobby() {
        World lobbyWorld = Bukkit.getWorld(lobby.config().worldName());
        if (lobbyWorld == null) {
            return;
        }
        for (Player player : lobbyWorld.getPlayers()) {
            Scheduling.entity(plugin, player, () -> giveItemsIfReady(player));
        }
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
        if (lobby.isReleased(player.getUniqueId())) {
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
                .filter(id -> !lobby.isSpectator(id))
                .collect(Collectors.toUnmodifiableSet());
        SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(present);
        if (outcome == SpeedrunLobby.StartOutcome.STARTED) {
            for (Player racer : lobbyWorld.getPlayers()) {
                if (present.contains(racer.getUniqueId())) {
                    racer.getInventory().clear();
                }
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
