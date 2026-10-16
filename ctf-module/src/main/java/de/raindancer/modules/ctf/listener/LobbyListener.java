package de.raindancer.modules.ctf.listener;

import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.service.LobbyService;
import de.raindancer.modules.ctf.service.ProgressService;
import de.raindancer.modules.ctf.store.LobbyStore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The lobby's items, the parkour (start, checkpoints, finish, falling back) and the throwing range. */
public final class LobbyListener implements ICtfListener {

    /** How near a parkour spot counts as reaching it, blocks. */
    private static final double REACH = 1.2;
    /** How far below the start somebody has fallen off the parkour. */
    private static final double FALLEN = 12;

    private final CtfServices services;

    public LobbyListener(CtfServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> purpose = services.lobby().purposeOf(event.getItem());
        if (purpose.isEmpty() || !services.lobby().isInside(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        switch (purpose.get()) {
            case LobbyService.MENU -> services.screens().main(player);
            case LobbyService.VOTE -> services.screens().vote(player);
            case LobbyService.CLASS -> services.screens().classes(player);
            case LobbyService.LEAVE -> services.rounds().leave(player);
            case LobbyService.PRACTICE_FLAG -> practiceThrow(player);
            default -> {
            }
        }
    }

    private void practiceThrow(Player player) {
        LobbyStore.Place line = services.lobby().lobby().throwLine();
        Optional<Location> start = services.lobby().place(line);
        if (start.isEmpty() || !start.get().getWorld().equals(player.getWorld())
                || start.get().distanceSquared(player.getLocation()) > 9) {
            services.messages().send(player, "ctf.lobby.throw-from-line");
            return;
        }
        Location from = player.getEyeLocation();
        Location mark = start.get();
        services.flags().fly(from, from.getDirection().normalize().multiply(1.25).add(new org.bukkit.util.Vector(0, 0.2,
                0)), Material.WHITE_BANNER, mark.getY() - 40, -2, landed -> {
                    if (landed == null) {
                        services.messages().send(player, "ctf.lobby.throw-lost");
                        return;
                    }
                    double blocks = Math.hypot(landed.x() - mark.getX(), landed.z() - mark.getZ());
                    Scheduling.async(services.plugin(), () -> {
                        RunHistory.Standing standing = services.lobby().thrown(player, blocks);
                        services.messages().send(player, standing.newRecord() ? "ctf.lobby.throw-record"
                                : "ctf.lobby.throw-distance", "blocks", String.format(Locale.ROOT, "%.1f", blocks),
                                "rank", standing.rank());
                    });
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !services.config().get().parkour()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!services.lobby().isInside(id) || services.rounds().isIn(id)) {
            return;
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.isFlying() || player.isGliding()) {
            // Not a run anybody made on foot.
            services.lobby().stopParkour(id);
            return;
        }
        LobbyStore.Lobby lobby = services.lobby().lobby();
        if (!lobby.hasParkour()) {
            return;
        }
        Location to = event.getTo();
        if (near(to, lobby.parkourStart())) {
            if (services.lobby().parkourOf(id).isEmpty()) {
                services.messages().send(player, "ctf.parkour.started");
                services.core().effects().play(id, Cues.COUNTDOWN_DONE);
            }
            services.lobby().startParkour(id);
            return;
        }
        if (services.lobby().parkourOf(id).isEmpty()) {
            return;
        }
        List<LobbyStore.Place> checkpoints = lobby.checkpoints();
        for (int index = 0; index < checkpoints.size(); index++) {
            if (near(to, checkpoints.get(index)) && services.lobby().parkourOf(id).get().checkpoint() < index + 1) {
                services.lobby().checkpoint(id, index + 1);
                services.messages().send(player, "ctf.parkour.checkpoint", "number", index + 1);
                services.core().effects().play(id, Cues.OK);
            }
        }
        if (near(to, lobby.parkourFinish())) {
            if (!LobbyService.mayFinish(services.lobby().parkourOf(id).get(), checkpoints.size())) {
                services.messages().send(player, "ctf.parkour.missed-checkpoint");
                return;
            }
            long startedAt = services.lobby().parkourOf(id).get().startedAt();
            long took = System.currentTimeMillis() - startedAt;
            services.lobby().stopParkour(id);
            Scheduling.async(services.plugin(), () -> {
                RunHistory.Standing standing = services.lobby().recordParkour(player, startedAt, took);
                services.messages().send(player, standing.newRecord() ? "ctf.parkour.record" : "ctf.parkour.finished",
                        "time", time(took), "rank", standing.rank());
            });
            services.progress().award(id, ProgressService.PARKOUR);
            services.core().effects().play(id, Cues.EARNED);
            return;
        }
        Point start = lobby.parkourStart().point();
        if (to.getY() < start.y() - FALLEN) {
            services.lobby().parkourRespawn(id).ifPresent(player::teleportAsync);
        }
    }

    private static boolean near(Location at, LobbyStore.Place place) {
        if (place == null || !place.world().equals(at.getWorld().getName())) {
            return false;
        }
        Point point = place.point();
        return point.distanceSquared(at.getX(), at.getY(), at.getZ()) <= REACH * REACH * 2;
    }

    private static String time(long millis) {
        Duration took = Duration.ofMillis(millis);
        return String.format(Locale.ROOT, "%d:%02d.%02d", took.toMinutes(), took.toSecondsPart(),
                took.toMillisPart() / 10);
    }
}
