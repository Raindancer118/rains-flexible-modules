package de.raindancer.modules.claims.service;

import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.listener.MovementListener;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAtmosphere;
import de.raindancer.modules.claims.model.ClaimEffect;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.ClaimPantry;
import de.raindancer.modules.claims.model.PotionStore;
import de.raindancer.modules.claims.rules.ClaimNames;
import de.raindancer.modules.claims.rules.Features;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.visual.RainPackets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the two "living claim" features: the potion effects a claim grants, and the shared pantry that
 * feeds hungry players.
 * <p>
 * Both are driven from one repeating task per player, on that player's entity scheduler. Doing it per
 * player rather than by scanning every claim keeps the cost proportional to who is actually online, and
 * an entity-scheduled task is automatically cancelled when the player disconnects.
 */
public final class AmbienceService {

    /** How often each online player is checked. Well under the effect duration so it never lapses. */
    private static final long INTERVAL_TICKS = 40L;
    /** Minimum gap between two automatic feedings of the same player. */
    private static final long FEED_COOLDOWN_MILLIS = 4_000L;

    private final Plugin plugin;
    private final Features features;
    private final ClaimRegistry claims;
    private final Land protection;
    private final ClaimService claimService;
    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final Messages messages;
    private final ClaimNames claimNames;

    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFed = new ConcurrentHashMap<>();
    private final Map<UUID, AtmosphereState> atmosphereState = new ConcurrentHashMap<>();
    /** claim id → last visual lightning, so a storm flashes rather than strobes. */
    private final Map<UUID, Long> lastLightning = new ConcurrentHashMap<>();
    // ThreadLocalRandom, not a shared Random: this is read from every player's region thread, and a
    // shared Random makes them contend on one atomic seed for the sake of deciding where lightning goes.
    private EquipService equipService;
    /** The border tracker, injected late because it is built after this service. */
    private MovementListener movement;
    /** Effects this plugin granted, so they can be cleared again on leaving. */
    private final Map<UUID, java.util.Set<org.bukkit.potion.PotionEffectType>> granted =
            new ConcurrentHashMap<>();

    public AmbienceService(Plugin plugin, Features features, ClaimRegistry claims,
                           Land protection,
                           ClaimService claimService, ClaimSettings settings, Messages messages,
                           ClaimNames claimNames) {
        this.plugin = plugin;
        this.features = features;
        this.claims = claims;
        this.protection = protection;
        this.claimService = claimService;
        this.settings = settings;
        this.messages = messages;
        this.claimNames = claimNames;
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>Called on reload. The field is a snapshot rather than a live view, so nothing here has to think about a
     * value changing halfway through a calculation — and replacing the whole snapshot means a reload takes effect
     * on the next event rather than on the next restart.
     */
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    /**
     * Starts the per-player loop. Safe to call repeatedly.
     * <p>
     * Must be called again after a respawn: the player's entity is replaced, which retires anything
     * scheduled on it. The retired callback below clears the bookkeeping so a re-track actually takes.
     */
    public void track(Player player) {
        UUID uuid = player.getUniqueId();
        ScheduledTask existing = tasks.get(uuid);
        if (existing != null && !existing.isCancelled()) {
            return;
        }
        tasks.remove(uuid);

        ScheduledTask task = Scheduling.entityTimer(plugin, player, INTERVAL_TICKS, INTERVAL_TICKS,
                scheduled -> {
                    if (!player.isOnline()) {
                        scheduled.cancel();
                        tasks.remove(uuid);
                        return;
                    }
                    tick(player);
                },
                // Fires when the entity is retired — on respawn, so the next track() re-schedules.
                () -> tasks.remove(uuid));
        if (task != null) {
            tasks.put(uuid, task);
        }
    }

    /** Whether this player is currently being ticked. */
    public boolean isTracked(Player player) {
        ScheduledTask task = tasks.get(player.getUniqueId());
        return task != null && !task.isCancelled();
    }

    /**
     * Re-attaches everything after a respawn.
     * <p>
     * The old entity's task is gone and the cached client state belongs to a body that no longer exists,
     * so both are dropped before a fresh task is started.
     */
    public void retrack(Player player) {
        UUID uuid = player.getUniqueId();
        ScheduledTask old = tasks.remove(uuid);
        if (old != null) {
            old.cancel();
        }
        // A respawned player starts with a clean client: no weather override, no granted effects.
        granted.remove(uuid);
        atmosphereState.remove(uuid);
        track(player);
    }

    public void forget(UUID uuid) {
        ScheduledTask task = tasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        lastFed.remove(uuid);
        granted.remove(uuid);
        atmosphereState.remove(uuid);
    }

    public void stopAll() {
        tasks.values().forEach(ScheduledTask::cancel);
        tasks.clear();
    }

    /**
     * Picks up anybody whose loop has stopped.
     * <p>
     * A safety net rather than the mechanism: respawns are handled directly, but any other cause of an
     * entity being replaced would otherwise leave a player silently unserved until they reconnect. Run
     * periodically, it costs a map lookup per online player.
     *
     * @return how many players had to be re-attached
     */
    public int sweep() {
        int recovered = 0;
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!isTracked(online)) {
                track(online);
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * Which claim to treat this player as being in.
     * <p>
     * Deliberately the same answer the border tracker gives, so a jump out of the top of a claim that
     * sits inside a taller one does not blink the effects, resend the weather and re-run auto-equip. The
     * tracker's claim is only a hint here — {@code at(location, previous)} falls back to the plain
     * lookup the moment it no longer holds, so a stale tracker cannot pin somebody to the wrong claim.
     */
    private Optional<Claim> resolve(Player player) {
        Claim tracked = movement == null ? null : movement.claimOf(player).orElse(null);
        return claims.at(player.getLocation(), tracked);
    }

    private void tick(Player player) {
        Optional<Claim> claim = resolve(player);
        if (claim.isEmpty()) {
            clearGranted(player);
            clearAtmosphere(player);
            return;
        }
        applyEffects(player, claim.get());
        applyAtmosphere(player, claim.get());
        feedIfHungry(player, claim.get());
        if (equipService != null) {
            equipService.equip(player, claim.get());
        }
    }

    /** Set after construction — the equip service is built alongside this one. */
    public void movement(MovementListener movement) {
        this.movement = movement;
    }

    public void equipService(EquipService equipService) {
        this.equipService = equipService;
    }

    // ------------------------------------------------------------ weather and time

    /**
     * Shows the claim's own weather and time to this player.
     * <p>
     * Client side only: the world keeps its real weather, so crops, sleeping and mob spawning are
     * unaffected. An eternal-noon claim therefore does not accidentally become a mob-free zone.
     */
    private void applyAtmosphere(Player player, Claim claim) {
        var atmosphere = claim.atmosphere();
        // Each half asks the same question the pantry and auto-equip ask: is the server offering it, has
        // the owner switched it on, and did they include the group this player is in.
        boolean weatherWanted = features.appliesTo(claim, ClaimFeature.CLAIM_WEATHER, player);
        boolean timeWanted = features.appliesTo(claim, ClaimFeature.CLAIM_TIME, player);

        if (!weatherWanted && !timeWanted) {
            clearAtmosphere(player);
            return;
        }
        // Somebody who may not be here does not get the ambience either.
        if (!protection.has(claim.area(), player, LandAction.ENTER)) {
            clearAtmosphere(player);
            return;
        }

        var state = atmosphereState.computeIfAbsent(player.getUniqueId(), key -> new AtmosphereState());

        if (weatherWanted) {
            ClaimAtmosphere.WeatherMode mode = atmosphere.weather();
            mode.toWeatherType().ifPresent(type -> {
                // Re-sent every pass rather than only on change, so anything that resyncs the world
                // weather cannot quietly undo the override with no way to notice.
                player.setPlayerWeather(type);
                state.weather = type;
            });
            // The weather packet only flips the "is it raining" switch — the client then waits for a
            // rain level before it draws a single drop. That level has to be sent separately.
            RainPackets.send(player, mode.rainLevel(), mode.thunderLevel(), plugin.getLogger());
            if (mode == ClaimAtmosphere.WeatherMode.THUNDER) {
                applyThunder(claim);
            }
        } else if (state.weather != null) {
            player.resetPlayerWeather();
            // Hand the client back the world's real intensity, otherwise it keeps ours.
            var world = player.getWorld();
            RainPackets.send(player, world.hasStorm() ? 1f : 0f, world.isThundering() ? 1f : 0f,
                    plugin.getLogger());
            state.weather = null;
        }

        if (timeWanted) {
            int ticks = atmosphere.effectiveTicks();
            if (ticks >= 0) {
                // Absolute, so the claim's time stands still rather than drifting with the world.
                player.setPlayerTime(ticks, false);
                state.time = ticks;
            }
        } else if (state.time >= 0) {
            player.resetPlayerTime();
            state.time = -1;
        }
    }

    /**
     * Completes the thunderstorm the weather packet cannot deliver on its own.
     * <p>
     * {@code setPlayerWeather} only knows CLEAR and DOWNFALL — there is no per-player thunder level. The
     * rumble, however, is just a sound, and sounds <em>are</em> per player: everybody standing in the
     * claim hears it on the weather channel, so it respects their own volume slider and nobody outside
     * hears a thing. A visible bolt is offered on top, but that one really is world state, so it is a
     * separate switch.
     */
    private void applyThunder(Claim claim) {
        long now = System.currentTimeMillis();
        Long last = lastLightning.get(claim.id());
        // Rolled per claim, not per player, so everybody inside hears the same storm at the same moment.
        if (last != null && now - last < 5_000L + java.util.concurrent.ThreadLocalRandom.current().nextInt(11_000)) {
            return;
        }
        lastLightning.put(claim.id(), now);

        List<Player> inside = playersInside(claim);
        if (inside.isEmpty()) {
            return;
        }

        // Distant rumble or a close crack, decided once for the whole claim.
        boolean close = java.util.concurrent.ThreadLocalRandom.current().nextInt(4) == 0;
        float volume = close ? 1.0f : 0.4f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.3f;
        float pitch = 0.8f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.4f;

        for (Player listener : inside) {
            Player target = listener;
            Scheduling.entity(plugin, target, () -> {
                if (!target.isOnline()) {
                    return;
                }
                target.playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                        SoundCategory.WEATHER, volume, pitch);
                if (close) {
                    target.playSound(target.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT,
                            SoundCategory.WEATHER, 0.6f, pitch);
                }
            });
        }

        if (settings.claimThunderBolts()) {
            strikeVisibleBolt(inside.get(0), claim);
        }
    }

    /**
     * A harmless visual bolt inside the claim.
     * <p>
     * {@code strikeLightningEffect} lights no fires and hurts nobody, but it is a real entity and so is
     * visible to anyone nearby — including people outside the claim. That is why it is optional.
     */
    private void strikeVisibleBolt(Player near, Claim claim) {
        var shape = claim.shape();
        var origin = near.getLocation();
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = origin.getBlockX() + java.util.concurrent.ThreadLocalRandom.current().nextInt(33) - 16;
            int z = origin.getBlockZ() + java.util.concurrent.ThreadLocalRandom.current().nextInt(33) - 16;
            if (!shape.containsColumn(x, z)) {
                continue;
            }
            var world = near.getWorld();
            var target = new org.bukkit.Location(world, x + 0.5D,
                    world.getHighestBlockYAt(x, z) + 1, z + 0.5D);
            Scheduling.region(plugin, target, () -> world.strikeLightningEffect(target));
            return;
        }
    }

    /** Everybody currently standing in the claim. */
    private List<Player> playersInside(Claim claim) {
        List<Player> inside = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.getWorld().getUID().equals(claim.worldId())) {
                continue;
            }
            var location = online.getLocation();
            if (claim.shape().containsBlock(location.getBlockX(), location.getBlockY(),
                    location.getBlockZ())) {
                inside.add(online);
            }
        }
        return inside;
    }

    /**
     * Explains, step by step, why the ambience is or is not being applied to this player.
     * <p>
     * Weather and time are invisible server side — they are packets to one client — so when they appear
     * not to work there is nothing in a log to look at. This walks the same conditions the tick does.
     */
    public List<String> diagnose(Player player) {
        List<String> report = new ArrayList<>();
        report.add("tracked: " + (isTracked(player) ? "yes" : "NO — not ticking"));

        Optional<Claim> claim = resolve(player);
        if (claim.isEmpty()) {
            report.add("standing in a claim: NO");
            return report;
        }
        Claim target = claim.get();
        report.add("standing in: " + target.name());

        var atmosphere = target.atmosphere();
        report.add("server allows weather: " + (features.isOffered(ClaimFeature.CLAIM_WEATHER) ? "yes" : "NO"));
        report.add("server allows time: " + (features.isOffered(ClaimFeature.CLAIM_TIME) ? "yes" : "NO"));
        report.add("claim sets weather: "
                + (atmosphere.overridesWeather() ? atmosphere.weather().displayName() : "no"));
        report.add("claim sets time: " + atmosphere.describeTime());
        report.add("may enter: "
                + (protection.has(target.area(), player, LandAction.ENTER) ? "yes" : "NO"));

        AtmosphereState state = atmosphereState.get(player.getUniqueId());
        report.add("pushed to you: " + (state == null
                ? "nothing"
                : "weather=" + (state.weather == null ? "-" : state.weather)
                + ", time=" + (state.time < 0 ? "-" : state.time)));
        report.add("your client time: " + player.getPlayerTime()
                + (player.isPlayerTimeRelative() ? " (relative)" : " (fixed)"));
        report.add("rain intensity packets: "
                + (RainPackets.available(plugin.getLogger()) ? "available" : "NO — rain stays invisible"));
        return report;
    }

    /** Hands the player back to the world's own weather and time. */
    public void clearAtmosphere(Player player) {
        AtmosphereState state = atmosphereState.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.weather != null) {
            player.resetPlayerWeather();
            // resetPlayerWeather restores the world's rain *state* but not the intensity we overrode,
            // so without this a visitor would walk out of the claim into permanent rain.
            var world = player.getWorld();
            RainPackets.send(player, world.hasStorm() ? 1f : 0f, world.isThundering() ? 1f : 0f,
                    plugin.getLogger());
        }
        if (state.time >= 0) {
            player.resetPlayerTime();
        }
    }

    /** What this plugin last pushed to a player, so nothing is reset that we did not set. */
    private static final class AtmosphereState {
        org.bukkit.WeatherType weather;
        int time = -1;
    }

    // ------------------------------------------------------------ potion effects

    private void applyEffects(Player player, Claim claim) {
        if (!features.appliesTo(claim, ClaimFeature.EFFECTS, player)) {
            clearGranted(player);
            return;
        }
        // Somebody who may not even be here should not get the perks either.
        if (!protection.has(claim.area(), player, LandAction.ENTER)) {
            clearGranted(player);
            return;
        }

        // Two quite different modes. With potion costs on, the claim has no effect list of its own: what
        // runs is simply whatever potion is currently burning, at the strength that potion was brewed to.
        List<PotionEffect> wanted = settings.effectsRequirePotions()
                ? burningEffects(claim)
                : selectedEffects(claim);

        if (wanted.isEmpty()) {
            clearGranted(player);
            return;
        }

        var active = granted.computeIfAbsent(player.getUniqueId(),
                key -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        var stillWanted = new java.util.HashSet<org.bukkit.potion.PotionEffectType>();

        for (PotionEffect effect : wanted) {
            stillWanted.add(effect.getType());
            // Never downgrade an effect the player already has from a stronger source of their own.
            var existing = player.getPotionEffect(effect.getType());
            if (existing != null && existing.getAmplifier() > effect.getAmplifier()
                    && !active.contains(effect.getType())) {
                continue;
            }
            player.addPotionEffect(effect);
            active.add(effect.getType());
        }

        // An effect that stopped while the player stood here has to go too.
        active.removeIf(type -> {
            if (stillWanted.contains(type)) {
                return false;
            }
            player.removePotionEffect(type);
            return true;
        });
    }

    /** The owner's chosen effects, refreshed to the plugin's own duration. */
    private List<PotionEffect> selectedEffects(Claim claim) {
        List<PotionEffect> effects = new ArrayList<>();
        for (ClaimEffect effect : claim.effects().values()) {
            effects.add(effect.toPotionEffect());
        }
        return effects;
    }

    /**
     * The effects of the potion the claim is currently burning, drawing the next one when needed.
     * <p>
     * Potions are worked through in the order they were put in, and their own amplifier is kept — a
     * Potion of Swiftness II really does grant Speed II. Only called with somebody standing in the claim,
     * so an empty claim never burns through its stock.
     */
    private List<PotionEffect> burningEffects(Claim claim) {
        var store = claim.potionStore();
        if (store.isActive()) {
            return refreshed(store.activeEffects());
        }

        boolean hadBrew = store.activeBrew() != null;
        var next = store.pollNext();
        if (next.isEmpty()) {
            if (hadBrew) {
                store.clearActive();
                claim.markDirty();
                claimService.saveAsync(claim);
                notifyOwners(claim, "effect.out-of-potions", 
                        "effect", "The claim effects", "claim", claim.name());
            }
            return List.of();
        }

        ItemStack brew = next.get();
        List<PotionEffect> effects = PotionStore.potionEffectsOf(brew);
        if (effects.isEmpty()) {
            // Should not happen — only potions with effects are accepted — but never loop on a bad item.
            return List.of();
        }
        int minutes = settings.effectPotionMinutes();
        long until = minutes <= 0
                ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L;
        store.activate(brew, effects, until);
        claim.markDirty();
        claimService.saveAsync(claim);
        return refreshed(effects);
    }

    /**
     * Re-times the potion's effects to the plugin's short refresh window.
     * <p>
     * The potion's own duration is irrelevant here — it is fuel for the claim, not something the player
     * drank, so the effect must lapse quickly once they walk out.
     */
    private List<PotionEffect> refreshed(List<PotionEffect> effects) {
        List<PotionEffect> timed = new ArrayList<>(effects.size());
        for (PotionEffect effect : effects) {
            timed.add(new PotionEffect(effect.getType(), ClaimEffect.DURATION_TICKS,
                    effect.getAmplifier(), true, effect.hasParticles(), true));
        }
        return timed;
    }


    /**
     * Re-applies weather, time and effects to everybody currently inside the claim.
     * <p>
     * Called after an owner changes something so the change is visible at once rather than on the next
     * scheduled pass.
     */
    public void refreshClaim(Claim claim) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.getWorld().getUID().equals(claim.worldId())) {
                continue;
            }
            var location = online.getLocation();
            if (!claim.shape().containsBlock(location.getBlockX(), location.getBlockY(),
                    location.getBlockZ())) {
                continue;
            }
            Player player = online;
            Scheduling.entity(plugin, player, () -> {
                if (player.isOnline()) {
                    tick(player);
                }
            });
        }
    }

    private void notifyOwners(Claim claim, String messageKey, Object... placeholders) {
        for (UUID owner : claim.owners()) {
            Player online = plugin.getServer().getPlayer(owner);
            if (online != null) {
                messages.send(online, messageKey, placeholders);
            }
        }
    }

    /**
     * Drops the effects this plugin granted.
     * <p>
     * Only the ones tracked as ours are removed — a speed potion the player drank themselves must survive
     * walking out of the claim.
     */
    private void clearGranted(Player player) {
        var active = granted.remove(player.getUniqueId());
        if (active == null || active.isEmpty()) {
            return;
        }
        for (var type : active) {
            player.removePotionEffect(type);
        }
    }

    /** Called when a player leaves a claim, so the effects and ambience lapse immediately. */
    public void onLeaveClaim(Player player) {
        clearGranted(player);
        clearAtmosphere(player);
    }

    // ------------------------------------------------------------ pantry

    private void feedIfHungry(Player player, Claim claim) {
        ClaimPantry pantry = claim.pantry();
        // One question covers the server policy, the owner's switch and whether they included this
        // player's group.
        if (!features.appliesTo(claim, ClaimFeature.PANTRY, player) || pantry.isEmpty()) {
            return;
        }
        if (player.getFoodLevel() > pantry.threshold()) {
            return;
        }
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }
        if (!protection.has(claim.area(), player, LandAction.ENTER)) {
            return;
        }
        Long last = lastFed.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < FEED_COOLDOWN_MILLIS) {
            return;
        }

        int missing = 20 - player.getFoodLevel();
        ItemStack food = pantry.takeBestFor(missing);
        if (food == null) {
            return;
        }

        int nutrition = ClaimPantry.nutritionOf(food);
        float saturation = ClaimPantry.saturationOf(food);
        player.setFoodLevel(Math.min(20, player.getFoodLevel() + nutrition));
        player.setSaturation(Math.min(player.getFoodLevel(), player.getSaturation() + saturation));
        lastFed.put(player.getUniqueId(), now);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.6f, 1.0f);
        player.sendActionBar(messages.prefixed("pantry.fed", 
                "food", food.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                // Whose pantry, not just which claim: "the pantry of home" tells a visitor nothing
                // about whose generosity they just ate.
                "claim", claimNames.possessive(claim)));

        claim.markDirty();
        claimService.saveAsync(claim);

        if (pantry.isEmpty()) {
            notifyOwnersEmpty(claim);
        }
    }

    private void notifyOwnersEmpty(Claim claim) {
        notifyOwners(claim, "pantry.empty-notice", "claim", claim.name());
    }
}
