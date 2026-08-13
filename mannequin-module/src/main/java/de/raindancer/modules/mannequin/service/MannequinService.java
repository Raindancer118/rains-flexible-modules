package de.raindancer.modules.mannequin.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import de.raindancer.modules.mannequin.store.MannequinStore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a stored {@link Mannequin} into a live one, and back.
 *
 * <h2>Static, but not invulnerable</h2>
 * Every spawn or respawn places the entity at the mannequin's own anchor block and calls {@code
 * setImmovable(true)} — no knockback, no piston, no water, no explosion moves it. It is
 * <em>not</em> invulnerable, though: it has a real health pool ({@link Mannequin#resolvedMaxHealth}
 * against {@link MannequinSettings#maxHealth}) and can genuinely be killed. What replaces
 * invincibility is {@link #scheduleRespawn}: an identical replacement — same block, same loadout,
 * same skin — appears after {@code MannequinSettings#respawnDelaySeconds}, and {@code
 * MannequinDeathListener} makes sure nothing obtainable is ever left behind on the way there.
 */
public final class MannequinService implements IMannequinService {

    /**
     * How a respawn is scheduled — production hands this Core's {@code Scheduling.globalLater};
     * a test hands it something that records the call and lets the test decide when to run it,
     * without needing a live server tick.
     */
    public interface DelayedScheduler {
        void schedule(long delayTicks, Runnable task);
    }

    private final Plugin plugin;
    private final LogChannel log;
    private final MannequinRegistry registry;
    private final MannequinStore store;
    private final MannequinEquipService equip;
    private final DelayedScheduler delayedScheduler;
    private volatile MannequinSettings settings;

    public MannequinService(Plugin plugin, LogChannel log, MannequinRegistry registry,
                            MannequinStore store, MannequinEquipService equip,
                            DelayedScheduler delayedScheduler, MannequinSettings settings) {
        this.plugin = plugin;
        this.log = log;
        this.registry = registry;
        this.store = store;
        this.equip = equip;
        this.delayedScheduler = delayedScheduler;
        this.settings = settings;
    }

    @Override
    public void settings(MannequinSettings settings) {
        this.settings = settings;
    }

    // ---------------------------------------------------------------------------- creating / removing

    /** A brand-new mannequin, on the block the player is standing on. */
    public Mannequin create(UUID owner, Location anchor) {
        String id = registry.nextId();
        Mannequin mannequin = Mannequin.freshlyPlaced(id, owner, anchor.getWorld().getName(),
                anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ());
        registry.put(mannequin);
        store.save(mannequin);
        spawn(mannequin);
        return mannequin;
    }

    /** Removes it entirely: despawns the live entity, forgets it, and deletes its file. */
    public void remove(String id) {
        despawn(id);
        registry.remove(id);
        store.delete(id);
    }

    /** Persists whatever has changed about a mannequin's stored data (a rename, a new loadout slot…). */
    public void save(Mannequin mannequin) {
        registry.put(mannequin);
        store.save(mannequin);
    }

    // ---------------------------------------------------------------------------- (re)spawning

    /** Every mannequin belonging to this world, spawned fresh — called when the world loads. */
    public void spawnAllIn(World world) {
        for (Mannequin mannequin : registry.inWorld(world.getName())) {
            spawn(mannequin);
        }
    }

    /** Despawns the live entity for every mannequin in this world — called when it unloads. */
    public void despawnAllIn(String worldName) {
        for (Mannequin mannequin : registry.inWorld(worldName)) {
            despawn(mannequin.id());
        }
    }

    /** Places (or replaces) the live entity for one stored mannequin. */
    public org.bukkit.entity.Mannequin spawn(Mannequin mannequin) {
        World world = Bukkit.getWorld(mannequin.world());
        if (world == null) {
            return null;
        }
        despawn(mannequin.id());

        Location at = new Location(world, mannequin.x() + 0.5, mannequin.y(), mannequin.z() + 0.5);
        org.bukkit.entity.Mannequin entity = world.spawn(at, org.bukkit.entity.Mannequin.class,
                spawned -> configure(spawned, mannequin));
        registry.bindEntity(mannequin.id(), entity.getUniqueId());
        return entity;
    }

    /**
     * Brings a killed mannequin back: identical loadout and skin, same anchor block, after the
     * configured delay. The stored record was never touched by the death, so this simply spawns it
     * again — the same path {@link #spawn} always takes, including the durability-rebuild-style
     * equip calls, is what guarantees the replacement is indistinguishable from the original.
     *
     * <h2>The training tally survives a death — it used to be wiped here, deliberately</h2>
     * The first version of this reset {@code registry.resetSession} on every death, on the theory
     * that a fresh mannequin means a fresh training session. In practice that meant landing a
     * killing blow was the one hit that erased the evidence it happened: total damage, hit count
     * and the longest combo a player had actually reached all reported zero right after the
     * moment they would matter most. A mannequin's own {@code StatsScreen} already has a reset
     * button, behind a confirmation — that is the one place a tally is meant to be cleared, not a
     * side effect of the thing the tally exists to measure.
     */
    public void scheduleRespawn(Mannequin mannequin) {
        registry.unbindEntity(mannequin.id());
        long delay = settings.respawnDelayTicks();
        delayedScheduler.schedule(delay, () -> spawn(mannequin));
    }

    private void configure(org.bukkit.entity.Mannequin entity, Mannequin mannequin) {
        entity.customName(Component.text(mannequin.displayName()));
        entity.setCustomNameVisible(true);
        entity.setImmovable(true);
        entity.setCanPickupItems(true);
        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(true);

        double health = mannequin.resolvedMaxHealth(settings.maxHealthClamped());
        entity.setMaxHealth(health);
        entity.setHealth(health);

        applySkin(entity, mannequin.skinSource());
        for (Map.Entry<EquipmentSlot, ItemSpec> entry : mannequin.loadout().entrySet()) {
            equip.apply(entity, entry.getKey(), entry.getValue().toItemStack());
        }
        if (mannequin.emitsRedstoneSignal()) {
            placeBarrel(entity.getWorld(), mannequin);
        }
    }

    /**
     * Applies a player's skin to a live mannequin — {@code null} resets it to vanilla's own
     * default profile. Public, and the only place this happens: {@link #configure} calls it on
     * every spawn, and {@code screen.SkinScreen} calls it directly when an owner picks a new one,
     * rather than repeating the same three Bukkit calls a second time in the screen.
     *
     * <h2>Why an offline player's skin needs {@code completeFromCache()} at all</h2>
     * {@code OfflinePlayer#getPlayerProfile()} for somebody who is not currently connected can come
     * back with a profile that only knows their name and UUID — no texture properties — because
     * nothing has asked the profile to actually resolve them. For an online player this is never
     * visible, since the client already has its own skin loaded; for an offline one it silently
     * produced the default Steve/Alex skin regardless of who was picked, which is exactly what
     * "give it an offline player's skin" looked like from the loadout screen. {@code
     * completeFromCache()} reads Paper's own local profile cache — built from every player who has
     * ever actually joined this server — synchronously and without a network call, so it is safe to
     * run right here rather than needing to be scheduled off-thread.
     *
     * <p>If even the cache does not have it (a profile Paper has genuinely never seen texture data
     * for), an async {@link com.destroystokyo.paper.profile.PlayerProfile#update()} is kicked off as
     * a best-effort upgrade: the mannequin shows whatever it already has immediately, and only gets
     * a better skin later if Mojang actually answers.
     */
    public void applySkin(org.bukkit.entity.Mannequin entity, UUID skinSource) {
        if (entity == null) {
            return;
        }
        if (skinSource == null) {
            entity.setProfile(org.bukkit.entity.Mannequin.defaultProfile());
            return;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(skinSource);
        var profile = player.getPlayerProfile();
        if (profile == null) {
            entity.setProfile(org.bukkit.entity.Mannequin.defaultProfile());
            return;
        }
        if (!profile.isComplete()) {
            profile.completeFromCache();
        }
        entity.setProfile(ResolvableProfile.resolvableProfile(profile));

        if (!profile.isComplete() && plugin != null) {
            // Scheduling.entity silently drops this if the entity has since been removed — a
            // mannequin that died and respawned in the meantime just does not get the upgrade,
            // which is fine for a best-effort skin refresh.
            profile.update().thenAcceptAsync(updated ->
                    de.raindancer.core.platform.util.Scheduling.entity(plugin, entity,
                            () -> entity.setProfile(ResolvableProfile.resolvableProfile(updated))));
        }
    }

    /** The container an opted-in mannequin's redstone pulse is written to — directly under it. */
    private void placeBarrel(World world, Mannequin mannequin) {
        Block block = world.getBlockAt(mannequin.x(), mannequin.barrelY(), mannequin.z());
        if (block.getType() != Material.BARREL) {
            block.setType(Material.BARREL);
        }
    }

    /**
     * Places the barrel a redstone-emitting mannequin needs, right now, without waiting for its
     * next despawn/respawn cycle.
     *
     * <p>Exists because turning {@link Mannequin#emitsRedstoneSignal()} on is something an owner
     * does from the behaviour screen to an already-live mannequin — {@link #configure} only ever
     * placed the barrel at spawn time, so a mannequin created before the flag was ever switched on
     * would never get one and every hit's pulse would silently do nothing (the same reason it never
     * worked at all before there was any screen to flip the flag with).
     */
    public void ensureBarrel(Mannequin mannequin) {
        if (mannequin == null || !mannequin.emitsRedstoneSignal()) {
            return;
        }
        liveEntity(mannequin.id()).ifPresent(entity -> placeBarrel(entity.getWorld(), mannequin));
    }

    /** Removes the live entity, if there is one, without touching the stored record. */
    public void despawn(String id) {
        Optional<UUID> live = registry.liveEntity(id);
        if (live.isEmpty()) {
            return;
        }
        Entity entity = Bukkit.getEntity(live.get());
        if (entity != null) {
            entity.remove();
        }
        registry.unbindEntity(id);
    }

    /** The live entity for a mannequin, if its world is currently loaded. */
    public Optional<LivingEntity> liveEntity(String id) {
        return registry.liveEntity(id)
                .map(Bukkit::getEntity)
                .filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast);
    }
}
