package de.raindancer.modules.enchants.service;

import de.raindancer.core.RainsCore;
import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.EnchantsSettings;
import de.raindancer.modules.enchants.model.AirState;
import de.raindancer.modules.enchants.rules.AirRule;
import de.raindancer.modules.enchants.store.AirStore;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Flight on a chestplate and Double Jump on boots, which share one thing — the player's permission to
 * fly — and so share one owner. Run on the player's own thread, once a tick.
 *
 * <p>Creative and spectator players are left alone entirely, and so is flight somebody else granted:
 * only a permission this class set is ever taken back.
 */
public final class AirService {

    /** A flier leaves a couple of sparks behind this often, in ticks: a trail, not a cloud. */
    private static final int TRAIL_EVERY = 10;

    private static final long HOLD_NANOS = 100_000_000L;
    private static final long AIRBORNE_NANOS = 400_000_000L;
    private static final long LONGEST_STEP_MILLIS = 250;

    private final AirRule rule;
    private final AirStore store;
    private final Supplier<EnchantsSettings> settings;
    private final EnchantAdvancements advancements;
    private final Effects effects;
    private final Messages messages;
    private final Map<UUID, AirState> states = new ConcurrentHashMap<>();

    public AirService(AirRule rule, AirStore store, Supplier<EnchantsSettings> settings,
                      EnchantAdvancements advancements, Effects effects, Messages messages) {
        this.rule = rule;
        this.store = store;
        this.settings = settings;
        this.advancements = advancements;
        this.effects = effects;
        this.messages = messages;
    }

    /** Lets the charge follow the player between servers, and re-reads it when it arrives after a join. */
    public AutoCloseable follow(RainsCore core) {
        core.sharePlayerData(AirStore.NAMESPACE);
        return core.onSharedDataApplied((player, namespaces) -> {
            if (namespaces.contains(AirStore.NAMESPACE)) {
                refresh(player);
            }
        });
    }

    private void refresh(Player player) {
        AirState state = states.get(player.getUniqueId());
        AirState arrived = store.load(player);
        if (state != null && arrived.budget.isKnown()) {
            state.budget = arrived.budget;
            state.flownMillis = arrived.flownMillis;
        }
    }

    void adopt(Player player, AirState state) {
        states.put(player.getUniqueId(), state);
    }

    public void tick(Player player) {
        int flight = CustomEnchantments.levelOn(player.getInventory().getChestplate(), RainsEnchants.FLIGHT);
        int jump = CustomEnchantments.levelOn(player.getInventory().getBoots(), RainsEnchants.DOUBLE_JUMP);
        AirState state = states.get(player.getUniqueId());
        if (state == null) {
            if (flight == 0 && jump == 0) {
                return;
            }
            state = states.computeIfAbsent(player.getUniqueId(), id -> store.load(player));
        }
        long now = System.nanoTime();
        long elapsed = Math.min(LONGEST_STEP_MILLIS, (now - state.lastNanos) / 1_000_000L);
        state.lastNanos = now;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            state.granted = false;
            return;
        }

        EnchantsSettings config = settings.get();
        long capacity = rule.capacityMillis(flight, config.flightSecondsPerLevel());
        if (flight > 0) {
            state.budget.fitTo(capacity);
        }
        boolean grounded = standing(player) || player.isInWater() || player.isInsideVehicle();
        if (grounded && now >= state.airborneUntilNanos) {
            state.jumpsUsed = 0;
        }

        if (player.isFlying() && state.granted) {
            if (player.getTicksLived() % TRAIL_EVERY == 0) {
                player.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, player.getLocation(), 2, 0.2, 0.05, 0.2, 0.01);
            }
            if (rule.mayFly(flight, state.budget.remainingMillis())) {
                boolean emptied = state.budget.drain(elapsed);
                long before = state.flownMillis / 60_000L;
                state.flownMillis += elapsed;
                if (state.flownMillis / 60_000L > before) {
                    advancements.flewMinutes(player.getUniqueId(), (int) (state.flownMillis / 60_000L));
                }
                if (emptied) {
                    advancements.emptiedTank(player.getUniqueId());
                    land(player, config, true);
                }
            } else {
                land(player, config, false);
            }
        } else if (grounded && flight > 0 && !player.isFlying()) {
            state.budget.recharge(elapsed, capacity, config.flightRechargeSeconds() * 1000L);
        }

        boolean want = now >= state.holdUntilNanos
                && rule.allowFlight(flight, state.budget.remainingMillis(), jump, state.jumpsUsed);
        if (want && !player.getAllowFlight()) {
            player.setAllowFlight(true);
            state.granted = true;
        } else if (!want && state.granted) {
            state.granted = false;
            player.setAllowFlight(false);
        }
    }

    /** @return true to cancel the toggle: it was a jump, or flight that is not available */
    public boolean onToggleFlight(Player player, boolean startsFlying) {
        AirState state = states.get(player.getUniqueId());
        if (!startsFlying || state == null || !state.granted) {
            return false;
        }
        int flight = CustomEnchantments.levelOn(player.getInventory().getChestplate(), RainsEnchants.FLIGHT);
        if (rule.mayFly(flight, state.budget.remainingMillis())) {
            advancements.used(player.getUniqueId(), RainsEnchants.FLIGHT);
            return false;
        }
        int jump = CustomEnchantments.levelOn(player.getInventory().getBoots(), RainsEnchants.DOUBLE_JUMP);
        if (rule.jumpsLeft(jump, state.jumpsUsed) > 0) {
            jump(player, state);
        }
        return true;
    }

    private void jump(Player player, AirState state) {
        state.jumpsUsed++;
        int power = settings.get().doubleJumpPowerPercent();
        Vector ahead = player.getLocation().getDirection().setY(0);
        if (ahead.lengthSquared() > 1.0E-6) {
            ahead.normalize().multiply(0.25 * power / 100.0);
        }
        Vector now = player.getVelocity();
        player.setVelocity(new Vector(now.getX() + ahead.getX(), rule.jumpUp(power), now.getZ() + ahead.getZ()));
        player.setFallDistance(0);
        // Off and back on a moment later: that is what makes the client listen for the next double tap.
        state.holdUntilNanos = System.nanoTime() + HOLD_NANOS;
        // Right after the push the player still reads as standing; counting that as a landing would
        // hand the jump straight back.
        state.airborneUntilNanos = System.nanoTime() + AIRBORNE_NANOS;
        player.setAllowFlight(false);
        effects.play(player.getUniqueId(), Cues.ABILITY);
        player.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, player.getLocation(), 8, 0.3, 0.05, 0.3, 0.02);
        advancements.jumped(player.getUniqueId(), state.jumpsUsed);
    }

    /**
     * On the ground, as the server sees it. {@code isOnGround} is what the client says, and a client that
     * always says yes would never use a jump up and would recharge Flight in mid-air — so it must also be
     * true that something solid is right under the feet.
     */
    private static boolean standing(Player player) {
        return player.isOnGround()
                && player.getWorld().hasCollisionsIn(player.getBoundingBox().shift(0, -0.2, 0));
    }

    private void land(Player player, EnchantsSettings config, boolean emptied) {
        player.setFlying(false);
        if (config.landingGraceSeconds() > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,
                    config.landingGraceSeconds() * 20, 0, true, false, true));
        }
        if (emptied) {
            player.sendActionBar(messages.get("enchants.flight-empty"));
        }
    }

    public void join(Player player) {
        states.remove(player.getUniqueId());
    }

    /** Saves what a restart must not lose, hands back the permission to fly, and lets go of them. */
    public void leave(Player player) {
        AirState state = states.remove(player.getUniqueId());
        if (state != null) {
            store.save(player, state);
            release(player, state);
        }
    }

    /** The module stopping: nobody keeps flight that only this module gave them. */
    public void releaseAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AirState state = states.remove(player.getUniqueId());
            if (state != null) {
                store.save(player, state);
                release(player, state);
            }
        }
    }

    private static void release(Player player, AirState state) {
        if (state.granted) {
            state.granted = false;
            player.setAllowFlight(false);
        }
    }
}
