package de.raindancer.modules.essentials.service;

import de.raindancer.core.data.loadout.Loadout;
import de.raindancer.core.data.loadout.LoadoutStore;
import de.raindancer.core.data.loadout.Loadouts;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * {@code /admin}: a second life for staff — its own inventory, ender chest, experience, health, effects and
 * game mode — swapped in and out on one command.
 *
 * <h2>The disk is the truth</h2>
 * Somebody is in admin mode exactly when their survival side is on disk. It is written <em>before</em> the
 * admin side is put on and deleted only <em>after</em> it has been put back, and the player's own data file
 * is saved after each switch — so a crash at any point leaves either the old state or the new one, never a
 * survival inventory that exists twice or not at all.
 */
public final class AdminModeService implements IEssentialsService {

    public static final String SURVIVAL = "survival";
    public static final String ADMIN = "admin";
    private static final String BAR = "essentials-admin-mode";
    private static final de.raindancer.core.platform.log.LogChannel log =
            de.raindancer.core.platform.log.Log.of("essentials");

    private final LoadoutStore store;
    private final Loadouts loadouts;
    private final Messages messages;
    private final ActionBars actionBars;
    private final Vanish vanish;
    private final de.raindancer.core.moderation.players.PlayerPowers powers;
    private final BiConsumer<Player, Loadout.Place> moveTo;
    private volatile EssentialsSettings settings;

    private final Set<UUID> inAdminMode = ConcurrentHashMap.newKeySet();
    /** Vanished by going in, so coming out shows them again; anybody vanished before stays vanished. */
    private final Set<UUID> vanishedByUs = ConcurrentHashMap.newKeySet();
    /** The same for god mode: only what going in switched on is switched off coming out. */
    private final Set<UUID> godByUs = ConcurrentHashMap.newKeySet();

    public AdminModeService(LoadoutStore store, Loadouts loadouts, Messages messages, ActionBars actionBars,
                            Vanish vanish, de.raindancer.core.moderation.players.PlayerPowers powers,
                            BiConsumer<Player, Loadout.Place> moveTo, EssentialsSettings settings) {
        this.store = store;
        this.loadouts = loadouts;
        this.messages = messages;
        this.actionBars = actionBars;
        this.vanish = vanish;
        this.powers = powers;
        this.moveTo = moveTo;
        this.settings = settings;
    }

    @Override
    public void settings(EssentialsSettings settings) {
        this.settings = settings;
    }

    public boolean isInAdminMode(UUID player) {
        return inAdminMode.contains(player);
    }

    /** Whether this player's items must stay on the admin side right now. */
    public boolean keepsItemsApart(UUID player) {
        return settings.adminKeepItemsApart() && isInAdminMode(player) && !bypassing.contains(player);
    }

    /** Who lifted keeping admin items apart for themselves — in memory, gone when they leave admin mode or go. */
    private final java.util.Set<UUID> bypassing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean bypassing(UUID player) {
        return bypassing.contains(player);
    }

    /**
     * Switches bypass on or off for somebody in admin mode with the right to it.
     *
     * @return false, and nothing changed, when they are not in admin mode or may not
     */
    public boolean toggleBypass(Player player) {
        UUID id = player.getUniqueId();
        if (!isInAdminMode(id) || !player.hasPermission(de.raindancer.modules.essentials.util.PermissionNodes.ADMIN_BYPASS)) {
            return false;
        }
        if (!bypassing.remove(id)) {
            bypassing.add(id);
        }
        return true;
    }

    /** Whether somebody keeping items apart may still use containers, frames and the like — logged when they do. */
    public boolean usesContainers(UUID player) {
        return settings.adminContainers() && isInAdminMode(player);
    }

    /**
     * A broken file cannot say which side they wear, so they count as in admin mode and stay there until it is
     * fixed: coming out would make whatever they hold their survival inventory.
     */
    private boolean brokenFile(Player player) {
        UUID id = player.getUniqueId();
        if (store.readable(id)) {
            return false;
        }
        inAdminMode.add(id);
        log.error("The admin-mode file of {} ({}) cannot be read. They are treated as in admin mode until it is "
                + "fixed.", player.getName(), id);
        messages.send(player, "essentials.admin.file-broken");
        return true;
    }

    /** In or out, whichever they are not. Must run on the player's own thread. */
    public boolean toggle(Player player) {
        return isInAdminMode(player.getUniqueId()) ? leave(player) : enter(player);
    }

    public boolean enter(Player player) {
        UUID id = player.getUniqueId();
        if (brokenFile(player)) {
            return false;
        }
        if (isInAdminMode(id)) {
            messages.send(player, "essentials.admin.already-in");
            return false;
        }
        if (!settings.adminModeEnabled()) {
            messages.send(player, "essentials.admin.switched-off");
            return false;
        }
        if (!player.hasPermission(PermissionNodes.ADMIN_MODE)) {
            messages.send(player, "essentials.no-permission");
            return false;
        }
        Loadout survival = loadouts.capture(player);
        if (!store.save(id, SURVIVAL, survival)) {
            messages.send(player, "essentials.admin.could-not-save");
            return false;
        }
        Loadout admin = store.load(id, ADMIN).orElseGet(() -> Loadout.empty(settings.adminStartingGameMode().name()));
        if (!loadouts.apply(player, admin)) {
            store.delete(id, SURVIVAL);
            messages.send(player, "essentials.admin.unreadable", "count", loadouts.unreadable(admin));
            return false;
        }
        inAdminMode.add(id);
        player.saveData();
        powersOn(player);
        showBar(id);
        messages.send(player, "essentials.admin.entered");
        return true;
    }

    /** Anybody in admin mode may always leave it — losing the permission must not lock them in. */
    public boolean leave(Player player) {
        UUID id = player.getUniqueId();
        bypassing.remove(id);
        if (brokenFile(player)) {
            return false;
        }
        Loadout survival = store.load(id, SURVIVAL).orElse(null);
        if (survival == null) {
            inAdminMode.remove(id);
            messages.send(player, "essentials.admin.not-in");
            return false;
        }
        int unreadable = loadouts.unreadable(survival);
        if (unreadable > 0) {
            // Staying in admin mode keeps the survival side safe on disk until the server can read it again.
            messages.send(player, "essentials.admin.unreadable", "count", unreadable);
            return false;
        }
        if (!store.save(id, ADMIN, loadouts.capture(player).withPlace(null))) {
            messages.send(player, "essentials.admin.could-not-save");
            return false;
        }
        loadouts.apply(player, survival);
        store.delete(id, SURVIVAL);
        inAdminMode.remove(id);
        player.saveData();
        powersOff(id);
        actionBars.clear(id, BAR);
        if (settings.adminReturnToPlace() && survival.place() != null) {
            moveTo.accept(player, survival.place());
        }
        messages.send(player, "essentials.admin.left");
        return true;
    }

    /** Picks up admin mode left on from before a quit or a restart. */
    public void joined(Player player) {
        UUID id = player.getUniqueId();
        if (brokenFile(player)) {
            showBar(id);
            return;
        }
        if (!store.has(id, SURVIVAL)) {
            inAdminMode.remove(id);
            return;
        }
        inAdminMode.add(id);
        if (!player.hasPermission(PermissionNodes.ADMIN_MODE)) {
            messages.send(player, "essentials.admin.permission-gone");
            leave(player);
            return;
        }
        powersOn(player);
        showBar(id);
        messages.send(player, "essentials.admin.still-in");
    }

    /** They stay in admin mode while away; only what is held in memory goes. */
    public void forget(UUID player) {
        inAdminMode.remove(player);
        bypassing.remove(player);
        powersOff(player);
    }

    /**
     * What admin mode is on top of the admin side's own things: its game mode every time, flight, god mode and
     * vanish as the owner set them. Flight comes back with the survival side's own loadout on the way out.
     */
    private void powersOn(Player player) {
        UUID id = player.getUniqueId();
        player.setGameMode(settings.adminStartingGameMode());
        if (settings.adminFly()) {
            player.setAllowFlight(true);
        }
        if (settings.adminGod() && !powers.isInvulnerable(id) && powers.god(id, true)) {
            godByUs.add(id);
        }
        if (settings.adminVanish() && !vanish.isVanished(id) && vanish.vanish(id)) {
            vanishedByUs.add(id);
        }
    }

    private void powersOff(UUID id) {
        if (godByUs.remove(id)) {
            powers.god(id, false);
        }
        if (vanishedByUs.remove(id)) {
            vanish.reveal(id);
        }
    }

    private void showBar(UUID id) {
        actionBars.show(id, BAR, messages.get("essentials.admin.bar"), ActionBars.UNTIL_CLEARED,
                ActionBarPriority.LOW);
    }
}
