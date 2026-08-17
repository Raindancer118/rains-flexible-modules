package de.raindancer.modules.mannequin.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.MannequinKind;
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
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a stored {@link Mannequin} into a live one, and back.
 *
 * <h2>Static, but not invulnerable</h2>
 * Every spawn or respawn places the entity at the mannequin's own anchor block and pins it there:
 * {@link Mannequin#kind()} {@code PLAYER} gets {@code setImmovable(true)} directly, and every other
 * kind gets {@code setGravity(false)} plus {@code listener.MannequinKnockbackListener} instead,
 * since {@code setImmovable} is a Mannequin-only API — no knockback, no piston, no water, no
 * explosion moves any of them. It is <em>not</em> invulnerable, though: it has a real health pool
 * ({@link Mannequin#resolvedMaxHealth}
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

    /**
     * Tags an entity as the live copy of one stored mannequin — never null when {@link #plugin} is
     * a real plugin, since only {@link #spawn} ever asks for it, and that path never runs in a
     * test that hands this class {@code null} in place of a live plugin.
     */
    private org.bukkit.NamespacedKey idKey() {
        return new org.bukkit.NamespacedKey(plugin, "mannequin-id");
    }

    @Override
    public void settings(MannequinSettings settings) {
        this.settings = settings;
    }

    // ---------------------------------------------------------------------------- creating / removing

    /** A brand-new {@link MannequinKind#PLAYER} mannequin, on the block the player is standing on. */
    public Mannequin create(UUID owner, Location anchor) {
        return create(owner, MannequinKind.PLAYER, anchor);
    }

    /**
     * A brand-new mannequin of a chosen kind, on the block the player is standing on, facing the
     * same way they were — {@code anchor}'s own yaw, read before anything block-snaps the
     * location, so the dummy looks the way its owner was looking rather than vanilla's default
     * (due south).
     */
    public Mannequin create(UUID owner, MannequinKind kind, Location anchor) {
        String id = registry.nextId();
        Mannequin mannequin = Mannequin.freshlyPlaced(id, owner, anchor.getWorld().getName(),
                anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ(), kind, anchor.getYaw());
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

    /**
     * Every mannequin belonging to this world, spawned fresh — called when the world loads.
     *
     * <p>Each one gets its own attempt. A single mannequin whose spawn throws — a loadout entry
     * this server no longer resolves, an anchor above a build height that shrank since it was
     * saved — used to take every mannequin after it in the same world down with it, since one
     * uncaught exception aborted the whole loop right here. That is exactly the "some of them are
     * just gone after a restart" report: which ones survived depended on where in the registry's
     * iteration order the broken one happened to fall, not on anything a server owner could see or
     * fix from the outside.
     */
    public void spawnAllIn(World world) {
        for (Mannequin mannequin : registry.inWorld(world.getName())) {
            try {
                spawn(mannequin);
            } catch (RuntimeException failed) {
                if (log != null) {
                    log.warn(failed, "{} could not be spawned in {} and was skipped.",
                            mannequin.id(), world.getName());
                }
            }
        }
    }

    /** Despawns the live entity for every mannequin in this world — called when it unloads. */
    public void despawnAllIn(String worldName) {
        for (Mannequin mannequin : registry.inWorld(worldName)) {
            despawn(mannequin.id());
        }
    }

    /**
     * Places (or replaces) the live entity for one stored mannequin — dispatched on {@link
     * Mannequin#kind()} to the matching real Bukkit entity class. Five explicit branches rather
     * than anything generic over {@code Class<? extends LivingEntity>}: {@link MannequinKind}
     * already enumerates exactly these five, each with its own spawn-time extras, and a sixth kind
     * is a change to the enum before it is ever a change here.
     */
    public LivingEntity spawn(Mannequin mannequin) {
        World world = Bukkit.getWorld(mannequin.world());
        if (world == null) {
            return null;
        }
        despawn(mannequin.id());

        Location at = new Location(world, mannequin.x() + 0.5, mannequin.y(), mannequin.z() + 0.5,
                mannequin.yaw(), 0f);
        removeAllCopiesIn(world, mannequin.id());
        LivingEntity entity = switch (mannequin.kind()) {
            case PLAYER -> world.spawn(at, org.bukkit.entity.Mannequin.class,
                    spawned -> configurePlayer(spawned, mannequin));
            case ZOMBIE -> world.spawn(at, Zombie.class,
                    spawned -> configureMob(spawned, mannequin));
            case SKELETON -> world.spawn(at, Skeleton.class,
                    spawned -> configureMob(spawned, mannequin));
            case WITHER -> world.spawn(at, Wither.class,
                    spawned -> configureWither(spawned, mannequin));
            case IRON_GOLEM -> world.spawn(at, IronGolem.class,
                    spawned -> configureMob(spawned, mannequin));
        };
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

    /**
     * Never rendered visibly — {@code ambient=true, particles=false, icon=false} — so a Zombie or
     * Skeleton dummy standing in daylight with no helmet chosen does not spontaneously catch fire
     * and die from something that has nothing to do with combat training. {@link
     * PotionEffect#INFINITE_DURATION} rather than a very large number, since that is the real
     * constant vanilla itself uses for "never expires".
     *
     * <p>Built lazily, on the one code path that actually needs it, rather than as a static field:
     * constructing a {@code PotionEffect} touches {@code PotionEffectType}'s own registry lookup,
     * which — like every other real-server registry this module reads — does not exist outside a
     * running Paper server. A static field would fail this class's own {@code <clinit>} the moment
     * anything in this module was loaded by a plain unit test, taking every other test in the class
     * down with it.
     */
    private static PotionEffect fireResistanceForever() {
        return new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION,
                0, true, false, false);
    }

    /**
     * Everything every kind needs, regardless of which real entity it is: the name, persistence,
     * health, and — where the kind supports it — the loadout and the skin. {@link #spawn} calls
     * this from every one of its five branches before applying whatever is specific to that one
     * kind.
     */
    private void configureCommon(LivingEntity entity, Mannequin mannequin) {
        entity.customName(Component.text(mannequin.displayName()));
        entity.setCustomNameVisible(true);
        entity.setCanPickupItems(true);
        entity.setRemoveWhenFarAway(false);
        // Not persistent: this module is the one thing that ever creates or removes this entity,
        // from the stored record in MannequinStore, on every enable() including a plain server
        // restart. Persistent left it to vanilla's own save/reload as well, so a restart produced
        // two of it — the one this module just spawned fresh from the store, standing on top of
        // the one the world file itself remembered from before the restart. A custom name already
        // keeps a mob from vanishing to ordinary despawn-culling on its own, and
        // setRemoveWhenFarAway(false) above covers the no-players-nearby case explicitly, so
        // nothing about staying put while the server runs depended on this flag being true.
        entity.setPersistent(false);
        entity.getPersistentDataContainer().set(idKey(), org.bukkit.persistence.PersistentDataType.STRING,
                mannequin.id());

        double health = mannequin.resolvedMaxHealth(settings.maxHealthClamped());
        entity.setMaxHealth(health);
        entity.setHealth(health);

        if (mannequin.kind().supportsSkin() && entity instanceof org.bukkit.entity.Mannequin skinnable) {
            applySkin(skinnable, mannequin.skinSource());
        }
        if (mannequin.kind().supportsLoadout()) {
            for (Map.Entry<EquipmentSlot, ItemSpec> entry : mannequin.loadout().entrySet()) {
                equip.apply(entity, entry.getKey(), entry.getValue().toItemStack());
            }
        }
        if (mannequin.kind().burnsInDaylight()) {
            entity.addPotionEffect(fireResistanceForever());
        }
        if (mannequin.emitsRedstoneSignal()) {
            placeBarrel(entity.getWorld(), mannequin);
        }
    }

    /**
     * {@link MannequinKind#PLAYER}: the original mannequin. {@code setImmovable(true)} is a
     * Mannequin-only API and the reason the other four kinds instead lean on {@code
     * setGravity(false)} plus {@code MannequinKnockbackListener}.
     *
     * <h2>{@code setSkinParts(allParts())}</h2>
     * Without this, a mannequin renders only its base skin layer — no jacket, hat, sleeve or pants
     * overlay, and no cape — regardless of which skin {@link #applySkin} gives it, because Paper's
     * own default for a freshly spawned {@code Mannequin} is not "every layer on". Every part is
     * switched on unconditionally here so a mannequin always looks like the player it is wearing.
     */
    private void configurePlayer(org.bukkit.entity.Mannequin entity, Mannequin mannequin) {
        configureCommon(entity, mannequin);
        entity.setImmovable(true);
        entity.setSkinParts(com.destroystokyo.paper.SkinParts.allParts());
    }

    /** {@link MannequinKind#ZOMBIE}, {@link MannequinKind#SKELETON} and {@link MannequinKind#IRON_GOLEM}. */
    private void configureMob(LivingEntity entity, Mannequin mannequin) {
        configureCommon(entity, mannequin);
        disableAiAndFalling(entity);
        if (entity instanceof Zombie zombie) {
            zombie.setBaby(false);
        }
    }

    /** {@link MannequinKind#WITHER}: additionally never allowed back into its brief spawn invulnerability. */
    private void configureWither(Wither entity, Mannequin mannequin) {
        configureCommon(entity, mannequin);
        disableAiAndFalling(entity);
        entity.setInvulnerableTicks(0);
    }

    /**
     * The AI-suppression recipe every non-{@code PLAYER} kind shares: no goal selector at all, no
     * targeting or aggression, and no falling. {@code setGravity(false)} is the "does not fall"
     * half of "fully static"; {@code MannequinKnockbackListener} is the other half, "does not get
     * shoved" — {@code setGravity} has nothing to say about knockback.
     */
    private void disableAiAndFalling(LivingEntity entity) {
        entity.setAI(false);
        entity.setGravity(false);
        if (entity instanceof Mob mob) {
            mob.setAware(false);
        }
    }

    /**
     * Applies a player's skin to a live mannequin — {@code null} resets it to vanilla's own
     * default profile. Public, and the only place this happens: {@link #configureCommon} calls it on
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
                    Scheduling.entity(plugin, entity,
                            () -> entity.setProfile(ResolvableProfile.resolvableProfile(updated))));
        }
    }

    /**
     * Looks somebody up by username — including somebody who has never joined this server at all,
     * like a well-known player picked as a joke or a friend from elsewhere — and applies their
     * skin once Mojang answers.
     *
     * <h2>Why this exists alongside {@link #applySkin}</h2>
     * {@code screen.SkinScreen}'s {@link de.raindancer.core.ui.choose.PlayerChooser} can only ever
     * offer players {@code Bukkit.getOfflinePlayers()} already knows about — everybody who has
     * actually connected to <em>this</em> server before. That chooser deliberately never became "a
     * text box that asks Mojang" because typing a name is the exact failure mode {@code
     * PlayerChooser}'s own javadoc exists to avoid for anyone already on the list. Someone who has
     * never joined is not on any list this server could enumerate, though — {@code
     * PlayerChooser}'s own project convention allows exactly this: "a duration or a reason may
     * still be a chat prompt … nothing to enumerate." A global username has nothing to enumerate.
     *
     * <p>Always asynchronous — {@code PlayerProfile#update()} is a real Mojang network call — and
     * the mannequin is only touched once back on its own region thread. A mannequin removed, or
     * whose world has since unloaded, while the lookup was in flight is handled by re-reading the
     * registry rather than trusting a captured reference.
     *
     * @param mannequinId  which mannequin this applies to, looked up fresh once the answer arrives
     * @param username     what was typed
     * @param onResolved   told the name that was actually found, once the skin has been applied
     * @param onNotFound   told nothing was found for that name (a typo, or nobody by that name exists)
     */
    public void lookupAndApplySkinByUsername(String mannequinId, String username,
                                             java.util.function.Consumer<String> onResolved,
                                             Runnable onNotFound) {
        if (plugin == null || mannequinId == null || username == null || username.isBlank()) {
            if (onNotFound != null) {
                onNotFound.run();
            }
            return;
        }
        var candidate = Bukkit.createProfile(username.trim());
        candidate.update().whenCompleteAsync((resolved, error) -> Scheduling.global(plugin, () -> {
            if (error != null || resolved == null || resolved.getId() == null || !resolved.hasTextures()) {
                if (onNotFound != null) {
                    onNotFound.run();
                }
                return;
            }
            registry.get(mannequinId).ifPresent(mannequin -> {
                Mannequin updated = mannequin.withSkinSource(resolved.getId());
                save(updated);
                liveEntity(mannequinId)
                        .filter(org.bukkit.entity.Mannequin.class::isInstance)
                        .map(org.bukkit.entity.Mannequin.class::cast)
                        .ifPresent(live -> live.setProfile(ResolvableProfile.resolvableProfile(resolved)));
            });
            if (onResolved != null) {
                onResolved.accept(resolved.getName());
            }
        }));
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
     * does from the behaviour screen to an already-live mannequin — {@link #configureCommon} only ever
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

    /**
     * Cleans up every live entity anywhere in {@code world} still tagged as this mannequin, before
     * the replacement is placed — {@link #despawn} only knows about a UUID this run's own registry
     * bound, which right after an {@code enable()} is never the copy left over from before. Matched
     * by {@link #idKey()}'s tag rather than simply "anything living standing here", so a player's own
     * pet or a real mob that wandered onto the same block is never touched.
     *
     * <p>Searched across the whole world rather than a box around the anchor: a leftover that has
     * drifted — pushed before {@code setGravity(false)} caught up with it, or standing where an
     * anchor used to be before it was moved — is exactly as much a duplicate as one sitting on top of
     * the new copy, and a tight radius around today's anchor can never find yesterday's. This is what
     * "spawn exactly one, and kill whatever old one might still be around" means in practice: not
     * "near where it should be" but "wearing this id, wherever it is".
     */
    private void removeAllCopiesIn(World world, String id) {
        for (Entity found : world.getEntities()) {
            if (found instanceof LivingEntity living
                    && id.equals(living.getPersistentDataContainer()
                            .get(idKey(), org.bukkit.persistence.PersistentDataType.STRING))) {
                living.remove();
            }
        }
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

    /**
     * Whether this live entity's id belongs to a mannequin this module is currently tracking —
     * {@code listener.MannequinKnockbackListener}'s one question, asked through this service rather
     * than reaching into {@link MannequinRegistry} directly, the same "the listener goes through a
     * service" shape every other listener in this package already follows.
     */
    public boolean isTracked(UUID liveEntityId) {
        return registry.idFor(liveEntityId).isPresent();
    }
}
