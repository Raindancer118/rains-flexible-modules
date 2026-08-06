package de.raindancer.modules.claims.listener;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandFlag;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Border crossings: titles, notifications, the border flash, entry tolls and ban enforcement.
 * <p>
 * The move handler only does work when the player actually changed block, and the claim lookup is a
 * chunk-bucket hit, so the hot path stays cheap.
 */
public final class MovementListener implements IClaimListener {

    @Override
    public String describe() {
        return "who is standing in which claim, and the messages about it";
    }


    private final ClaimServices services;
    /** Which claim each player is currently standing in; absent means wilderness. */
    private final Map<UUID, UUID> currentClaim = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotification = new ConcurrentHashMap<>();
    /** The last arrival announced to each player, so one crossing is not announced twice. */
    private final Map<UUID, Announcement> lastAnnouncement = new ConcurrentHashMap<>();

    public MovementListener(ClaimServices services) {
        this.services = services;
    }

    public void forget(UUID uuid) {
        currentClaim.remove(uuid);
        lastNotification.remove(uuid);
        lastAnnouncement.remove(uuid);
        // The provider's map too, or it grows by one entry per player who has ever been on the server.
        services.provider().forget(uuid);
    }

    /**
     * The claim this player is currently counted as standing in.
     * <p>
     * The border tracker is the one place that decides this, so everything that follows a player around
     * — effects, weather, the pantry, auto-equip — agrees with the enter and leave messages instead of
     * each re-deriving it and flickering at a seam.
     */
    public Optional<Claim> claimOf(Player player) {
        UUID id = currentClaim.get(player.getUniqueId());
        return id == null ? Optional.empty() : services.claims().byId(id);
    }

    /** Keeps the tracker in sync when a player joins or is teleported by another plugin. */
    /**
     * Records where a player is, in both places that need to know.
     *
     * <p>The tracker's own map answers "did they just cross a border", and the provider's answers Core's
     * {@code around(player)} — which is what gives the remembered claim the benefit of the doubt when a
     * position is ambiguous. Writing only one of them was a real bug: the provider's stayed empty, so every
     * lookup fell back to the raw one and flickered on borders and rooftops.
     */
    private void record(Player player, Claim claim) {
        if (claim == null) {
            currentClaim.remove(player.getUniqueId());
        } else {
            currentClaim.put(player.getUniqueId(), claim.id());
        }
        services.provider().moved(player.getUniqueId(), claim);
    }

    public void syncPosition(Player player) {
        record(player, services.claims().at(player.getLocation()).orElse(null));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        UUID previousId = currentClaim.get(player.getUniqueId());
        // Resolved against the claim they were already in, so a jump off the top of a claim is not
        // mistaken for leaving and coming back — whether what is above is wilderness or another claim.
        Claim previous = previousId == null ? null : services.claims().byId(previousId).orElse(null);
        Optional<Claim> to = services.claims().at(event.getTo(), previous);

        UUID targetId = to.map(Claim::id).orElse(null);
        if (java.util.Objects.equals(previousId, targetId)) {
            return;
        }

        if (to.isPresent()) {
            Claim claim = to.get();
            Gate gate = checkGate(player, claim, event.getTo(), false);
            if (gate != Gate.OPEN) {
                // Stop the player at the border. Their previous position is still valid ground.
                event.setCancelled(true);
                resyncIfAirborne(player, event.getFrom());
                return;
            }
        }

        // The transition is going through: fire the leave hooks for the old claim, then the enter hooks.
        if (previous != null) {
            onLeave(player, previous);
        }
        record(player, to.orElse(null));
        if (targetId != null) {
            to.ifPresent(claim -> onEnter(player, claim));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location destination = event.getTo();
        Optional<Claim> to = services.claims().at(destination);

        if (to.isPresent()) {
            Claim claim = to.get();
            boolean pearl = event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
            LandFlag flag = pearl ? LandFlag.ENDER_PEARL_IN : LandFlag.TELEPORT_IN;
            // Who may warp in is now part of the flag itself, one value per audience, so there is no
            // separate exemption for owners and trusted players any more.
            //
            // Where the teleport came FROM matters, and used to be ignored. See refusesArrival.
            Claim standingIn = services.claims().at(event.getFrom()).orElse(null);
            if (isSystemTeleport(event.getCause())
                    && refusesArrival(
                            standingIn == null ? null : standingIn.id(),
                            claim.id(),
                            services.land().landFlags().isEnforced(flag),
                            services.land().landFlags()
                                    .isAllowedForTracked(claim.area(), destination, flag, player),
                            services.land().isBypassing(player))) {
                event.setCancelled(true);
                if (pearl) {
                    refundPearl(player);
                }
                services.messages().send(player, "protection.teleport-denied", "claim", claim.name());
                return;
            }
            // The same rule the flag above now follows, and for the same reason: somebody already in this
            // claim is not arriving in it. Without this a pearl thrown inside a claim that charges a toll
            // could ask its own owner's guests to pay again for ground they never left — while walking the
            // same distance is free, because onMove returns early when the claim has not changed.
            boolean alreadyHere = standingIn != null && standingIn.id().equals(claim.id());
            if (!alreadyHere) {
                Gate gate = checkGate(player, claim, destination, true);
                if (gate != Gate.OPEN) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        UUID previousId = currentClaim.get(player.getUniqueId());
        UUID targetId = to.map(Claim::id).orElse(null);
        if (java.util.Objects.equals(previousId, targetId)) {
            return;
        }
        if (previousId != null) {
            services.claims().byId(previousId).ifPresent(previous -> onLeave(player, previous));
        }
        record(player, to.orElse(null));
        if (targetId != null) {
            to.ifPresent(claim -> onEnter(player, claim));
        }
    }

    /**
     * Gives back the pearl a refused teleport already ate.
     *
     * <p>The pearl is spent at the moment it is thrown; the teleport is a separate event a second later, and
     * cancelling that does not unthrow anything. So refusing the arrival charged the player for a journey they
     * were not allowed to make — reported as "it says I may not teleport there, but the pearl is consumed
     * anyway", which is somebody losing an ender pearl every time they misjudge a border.
     *
     * <p>Given on the player's own scheduler because a teleport event can arrive on a region thread that does
     * not own their inventory, and dropped at their feet if there is no room — the alternative is charging them
     * anyway for having a full inventory.
     */
    private void refundPearl(Player player) {
        de.raindancer.core.platform.util.Scheduling.entity(services.plugin(), player, () -> {
            org.bukkit.inventory.ItemStack pearl =
                    new org.bukkit.inventory.ItemStack(org.bukkit.Material.ENDER_PEARL, 1);
            player.getInventory().addItem(pearl).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        });
    }

    private boolean isSystemTeleport(PlayerTeleportEvent.TeleportCause cause) {
        return switch (cause) {
            // CONSUMABLE_EFFECT covers chorus fruit and anything else that warps on consumption.
            case COMMAND, PLUGIN, ENDER_PEARL, CONSUMABLE_EFFECT, SPECTATE -> true;
            default -> false;
        };
    }

    /** Elytra flight inside a claim. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || !event.isGliding()) {
            return;
        }
        if (!services.land().landFlags().isEnforced(LandFlag.ELYTRA_FLIGHT)) {
            return;
        }
        Optional<Claim> claim = services.claims().at(player.getLocation());
        if (services.land().landFlags().isAllowedForTracked(claim.map(Claim::area).orElse(null), player.getLocation(),
                LandFlag.ELYTRA_FLIGHT, player)) {
            return;
        }
        // No owner exemption here either: the flag carries one value per audience, so an owner who wants
        // to keep flying says so on the owner switch.
        if (services.land().isBypassing(player)) {
            return;
        }
        event.setCancelled(true);
        // map, not get: with the flag enforced server-wide this fires in the wilderness too, where
        // there is no claim to name — and get() there is a NoSuchElementException on every glide.
        services.messages().send(player, "protection.elytra-denied",
                "claim", claim.map(Claim::name).orElse("the wilderness"));
    }

    // ------------------------------------------------------------ gate

    private enum Gate {
        OPEN, BANNED, NO_ENTRY_PERMISSION, FEE_DUE
    }

    /**
     * Decides whether the player may cross into the claim, and produces the matching feedback.
     * Shared by walking and teleporting so both behave the same.
     */
    /**
     * Whether a teleport is an arrival the flag refuses.
     *
     * <p>Pure, and separate from the event handling, because the interesting part is a handful of cases and
     * every one of them was worth writing down after this went wrong on a live server.
     *
     * <p><b>The bug it fixes.</b> This used to look only at where the teleport <em>landed</em>. So an owner who
     * switched ender pearls off to keep strangers out also stopped everybody already inside — their trusted
     * friends included — from pearling around within their own claim, because landing inside was read as
     * arriving. An admin with the bypass on did not notice, which is why the report arrived as "the bypass got
     * broken": the bypass was the only thing making it work, and toggling it looked like the cure.
     *
     * <p>Core states the rule out loud for {@code WALK_IN} and this simply did not follow it: moving within an
     * area is never refused, and neither is leaving. Somebody who was inside when a flag was switched off has to
     * be able to move, and to get out. A way-in flag is about coming in.
     *
     * @param from      the area they were standing in, or {@code null} for open ground
     * @param to        the area they are landing in; never {@code null}, this is only asked when there is one
     * @param enforced  whether the server enforces this flag at all
     * @param allowed   what the flag says for this player in that area
     * @param bypassing whether the admin bypass is on. Checked last and wins outright — no flag any owner sets
     *                  may take it away, or an admin cannot reach the thing they were called in to fix
     */
    public static boolean refusesArrival(UUID from, UUID to, boolean enforced, boolean allowed,
                                         boolean bypassing) {
        if (to == null || !enforced || allowed || bypassing) {
            return false;
        }
        // Already here: moving about inside, or on the way out. Not an arrival.
        return !to.equals(from);
    }

    private Gate checkGate(Player player, Claim claim, Location destination, boolean teleport) {
        if (services.land().isBypassing(player) || claim.isOwner(player.getUniqueId())) {
            return Gate.OPEN;
        }

        Optional<ClaimBan> ban = claim.activeBan(player.getUniqueId());
        if (ban.isPresent()) {
            announceBan(player, claim, ban.get());
            return Gate.BANNED;
        }

        if (!services.land().has(claim.area(), player, LandAction.ENTER)) {
            throttled(player, () -> services.messages().send(player, "protection.entry-denied",
                    "claim", claim.name()));
            return Gate.NO_ENTRY_PERMISSION;
        }

        if (services.entryFees().requiresPayment(claim, player)) {
            services.entryFees().offer(player, claim, teleport ? destination : null, teleport);
            return Gate.FEE_DUE;
        }
        return Gate.OPEN;
    }

    /**
     * Forces an authoritative resync after a refused border crossing, for a player who was airborne.
     *
     * <p>Cancelling {@link PlayerMoveEvent} snaps the server's position back to {@code from} but does
     * nothing about the client's own predicted trajectory. Standing still that costs nothing; gliding on an
     * elytra or flying it does, because the client keeps predicting forward motion every tick the border
     * keeps refusing, and the two never agree again — the player is left hovering in place at the line,
     * unable to glide on, fall, or do anything else. An explicit teleport back to the same {@code from}
     * spot — the position the server already considers valid — is what actually clears it; the cancel alone
     * is not enough once the client has left the ground.
     */
    private void resyncIfAirborne(Player player, Location from) {
        if (player.isOnGround()) {
            return;
        }
        Location safe = from.clone();
        de.raindancer.core.platform.util.Scheduling.entity(services.plugin(), player, () -> {
            player.teleport(safe);
            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        });
    }

    // ------------------------------------------------------------ vehicles

    /**
     * Riding across a border a player's own feet could not cross.
     *
     * <p>{@link PlayerMoveEvent} does not fire for a passenger — the boat, horse or Happy Ghast under them
     * moves and the passenger's position moves with it, but only {@link org.bukkit.event.vehicle.VehicleMoveEvent}
     * reports it. Without this, a player banned from a claim, or one it merely refuses entry to, only had to
     * bring a mount along to sit inside it undisturbed — the gate that stops walking and teleporting never
     * ran at all.
     *
     * <p>The event carries no cancel: Bukkit gives no way to refuse a vehicle's move, only to react to it. So
     * a refused crossing puts the vehicle straight back where it came from and kills its momentum, the same
     * outcome a cancelled {@link PlayerMoveEvent} gives a player on foot.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        java.util.List<Player> riders = event.getVehicle().getPassengers().stream()
                .filter(Player.class::isInstance).map(Player.class::cast).toList();
        if (riders.isEmpty()) {
            return;
        }

        Optional<Claim> toClaim = services.claims().at(to);
        if (toClaim.isPresent()) {
            Claim claim = toClaim.get();
            for (Player rider : riders) {
                if (checkGate(rider, claim, to, false) != Gate.OPEN) {
                    event.getVehicle().teleport(from);
                    event.getVehicle().setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    return;
                }
            }
        }

        UUID targetId = toClaim.map(Claim::id).orElse(null);
        for (Player rider : riders) {
            UUID previousId = currentClaim.get(rider.getUniqueId());
            if (java.util.Objects.equals(previousId, targetId)) {
                continue;
            }
            if (previousId != null) {
                services.claims().byId(previousId).ifPresent(previous -> onLeave(rider, previous));
            }
            record(rider, toClaim.orElse(null));
            if (targetId != null) {
                toClaim.ifPresent(claim -> onEnter(rider, claim));
            }
        }
    }

    private void announceBan(Player player, Claim claim, ClaimBan ban) {
        throttled(player, () -> {
            String remaining = ban.permanent() ? "permanent" : de.raindancer.core.moderation.punishment.Durations.describe(java.time.Duration.ofMillis(ban.remainingMillis()));
            services.messages().send(player, ban.permanent() ? "protection.banned" : "protection.timed-out",
                    "claim", services.names().possessive(claim),
                            "reason", ban.reason().isBlank() ? "no reason given" : ban.reason(),
                            "remaining", remaining);
        });
    }

    private void throttled(Player player, Runnable action) {
        long now = System.currentTimeMillis();
        Long last = lastNotification.get(player.getUniqueId());
        if (last != null && now - last < services.config().notificationCooldownSeconds() * 1000L) {
            return;
        }
        lastNotification.put(player.getUniqueId(), now);
        action.run();
    }

    /**
     * Whether the arrival in this claim should be announced, or is a repeat of one just made.
     * <p>
     * Crossing a border twice in a few seconds is one arrival as far as the player is concerned —
     * stepping over the line and back, or any brief excursion out of the claim's range. Announcing it
     * again reads as a bug even when the crossing was real. Arriving somewhere <em>else</em> is always
     * announced, however quickly it follows: that is news.
     */
    private boolean announceable(Player player, Claim claim) {
        long now = System.currentTimeMillis();
        long window = services.config().notificationCooldownSeconds() * 1000L;
        Announcement last = lastAnnouncement.get(player.getUniqueId());

        boolean sayIt = last == null
                || !last.claimId().equals(claim.id())
                || now - last.at() >= window;
        if (sayIt) {
            // Only on the path that actually says something. Writing it before deciding — which is the
            // obvious way round — means every suppressed attempt renews the window, so somebody standing on
            // a border being refused twenty times a second is never told again, not even when they walk in
            // properly a minute later.
            lastAnnouncement.put(player.getUniqueId(), new Announcement(claim.id(), now));
        }
        return sayIt;
    }

    /** The last claim arrival announced to a player, so the same one is not announced twice over. */
    private record Announcement(UUID claimId, long at) {
    }

    // ------------------------------------------------------------ enter and leave effects

    private void onEnter(Player player, Claim claim) {
        if (isMuted(claim) || !announceable(player, claim)) {
            return;
        }
        if (services.features().appliesTo(claim, ClaimFeature.TITLES, player)
                && claim.titles().hasEnterTitle()) {
            player.showTitle(claim.titles().buildEnter());
        }
        if (services.features().appliesTo(claim, ClaimFeature.ENTER_MESSAGE, player)) {
            var message = services.messages().prefixed("notify.entered", 
                    "claim", claim.name(),
                    "owner", ownerNames(claim));
            if (services.config().enterMessageActionBar()) {
                player.sendActionBar(message);
            } else {
                player.sendMessage(message);
            }
        }
        if (services.features().isOffered(ClaimFeature.BORDER_FLASH)
                && services.features().appliesTo(claim, ClaimFeature.BORDER_FLASH, player)) {
            services.visualizer().showClaim(player, claim, services.config().borderOnEnterSeconds());
        }
    }

    private void onLeave(Player player, Claim claim) {
        // Effects lapse the moment somebody steps out, rather than lingering for their full duration.
        if (!claim.effects().isEmpty()) {
            services.ambience().onLeaveClaim(player);
        }
        if (isMuted(claim)) {
            return;
        }
        if (services.features().appliesTo(claim, ClaimFeature.TITLES, player)
                && claim.titles().hasLeaveTitle()) {
            player.showTitle(claim.titles().buildLeave());
        }
        if (services.features().isOffered(ClaimFeature.LEAVE_MESSAGE)) {
            var message = services.messages().prefixed("notify.left", "claim", claim.name());
            if (services.config().enterMessageActionBar()) {
                player.sendActionBar(message);
            } else {
                player.sendMessage(message);
            }
        }
    }

    /**
     * A hidden underground claim gives no sign of its existence — that is the whole point of letting
     * claims start below the surface.
     */
    private boolean isMuted(Claim claim) {
        if (!services.config().hiddenUndergroundNotificationsMuted()) {
            return false;
        }
        var world = services.server().getWorld(claim.worldId());
        if (world == null) {
            return false;
        }
        // Muted when the claim does not reach the build limit, i.e. somebody could stand above it.
        return claim.shape().maxY() < world.getMaxHeight() - 1
                && claim.shape().maxY() < world.getSeaLevel();
    }

    /** Every owner, comma separated — already answered by ClaimNames, which knows how to read a uuid. */
    private String ownerNames(Claim claim) {
        return services.names().allOwners(claim);
    }
}
