package de.raindancer.modules.xpbottle.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.rules.FillAmountRule;
import de.raindancer.modules.xpbottle.rules.SiphonReachRule;
import de.raindancer.modules.xpbottle.store.BottleTags;
import de.raindancer.modules.xpbottle.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holding a siphon bottle down, and what that pulls in.
 *
 * <h2>How "held down" is known</h2>
 * Two signals, either of which is enough, because neither alone is reliable. The first is Paper's
 * {@code hasActiveItem}: a siphon bottle carries a {@code consumable} component, so pressing right
 * click puts the client into the drink animation and the server knows the hand is raised for exactly
 * as long as the button is. The second is the click itself, good for {@link #GRACE_TICKS} — it
 * covers the tick or two between the click and the client entering that state, and it means a
 * server or a client that never enters it at all still draws a short pull rather than nothing, which
 * is the difference between a feature that degrades and one that is silently dead.
 *
 * <h2>Why what is drawn is held in memory until the draw stops</h2>
 * Writing it into the bottle every tick would end the draw on the same tick. The client stops using
 * an item the moment its components change, and the bottle's stored count <em>is</em> a component —
 * so a siphon that updated its own item as it filled would draw for one tick per click. What is
 * drawn is therefore accumulated here and written on release, when the bottle is full, when the
 * player quits, and when the module stops. The one thing that loses it is the server going down
 * without unloading, which loses the last few seconds of everything else too.
 *
 * <h2>Where the orbs come from</h2>
 * The ground first, the holder's own bar second. Orbs are what somebody aimed a siphon at; falling
 * back to their own experience when there are none is what makes the same item still useful indoors,
 * and it is the behaviour written on the item.
 */
public final class SiphonService implements IXpBottleService {

    /** How long a click alone keeps a draw alive, in ticks, without the hand being seen raised. */
    private static final long GRACE_TICKS = 12L;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Messages messages;
    private final Effects effects;
    private final ActionBars actionBars;
    private final FillAmountRule fill;
    private final SiphonReachRule reach;
    private final BottleForge forge;
    private final BottlingService bottling;

    /** What each drawing player has pulled in but not yet had written into their bottle. */
    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();

    /** Which hand each drawing player is holding it in, so a flush writes to the right stack. */
    private final Map<UUID, EquipmentSlot> hands = new ConcurrentHashMap<>();

    /** The tick each player last pressed the button, for {@link #GRACE_TICKS}. */
    private final Map<UUID, Long> clicked = new ConcurrentHashMap<>();

    private volatile XpBottleSettings settings;
    private volatile long ticks;

    public SiphonService(Plugin plugin, Messages messages, Effects effects, ActionBars actionBars,
                         FillAmountRule fill, SiphonReachRule reach, BottleForge forge,
                         BottlingService bottling, XpBottleSettings settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.effects = effects;
        this.actionBars = actionBars;
        this.fill = fill;
        this.reach = reach;
        this.forge = forge;
        this.bottling = bottling;
        settings(settings);
    }

    @Override
    public void settings(XpBottleSettings updated) {
        this.settings = updated == null ? XpBottleSettings.DEFAULTS : updated;
    }

    /** Somebody pressed right click while holding a siphon bottle. */
    public void began(Player player, EquipmentSlot hand) {
        if (player == null) {
            return;
        }
        clicked.put(player.getUniqueId(), ticks);
        hands.put(player.getUniqueId(), hand == null ? EquipmentSlot.HAND : hand);
    }

    /**
     * One run of the timer.
     *
     * <p>Enumerating the online players is safe from wherever the timer runs; touching one of them,
     * and the orbs around them, is not — on Folia each player belongs to a region thread of their
     * own. So the work per player is handed to that player's own region and nothing here reaches
     * into a world it does not own.
     *
     * @param periodTicks how often this runs, which is what the per-second draw rate is spread over
     */
    public void tick(Collection<? extends Player> online, long periodTicks) {
        ticks += Math.max(1, periodTicks);
        if (online == null || online.isEmpty()) {
            return;
        }
        for (Player player : online) {
            if (!isDrawing(player)) {
                continue;
            }
            Scheduling.entity(plugin, player, () -> draw(player, periodTicks));
        }
    }

    /**
     * Whether this player is holding a siphon down right now.
     *
     * <p>Cheap on purpose: asked for every online player on every run of the timer, and the answer
     * for almost all of them is no.
     */
    public boolean isDrawing(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (player.hasActiveItem() && isSiphon(player.getActiveItem())) {
            return true;
        }
        Long when = clicked.get(id);
        return when != null && ticks - when <= GRACE_TICKS;
    }

    /** One player's share of one run: at most a tick's worth of points into their bottle. */
    private void draw(Player player, long periodTicks) {
        XpBottleSettings live = settings;
        UUID id = player.getUniqueId();
        EquipmentSlot hand = handOf(player);
        ItemStack held = player.getInventory().getItem(hand);

        Optional<Bottle> read = BottleTags.read(held, live);
        if (read.isEmpty() || !read.get().mayVacuum()) {
            stop(player);
            return;
        }
        if (!player.hasPermission(PermissionNodes.SIPHON)) {
            stop(player);
            return;
        }

        Bottle onTheItem = read.get();
        int alreadyPending = pending.getOrDefault(id, 0);
        Bottle asItStands = onTheItem.holding(onTheItem.stored() + alreadyPending);
        if (asItStands.isFull()) {
            finish(player, "xpbottle.siphon.full");
            return;
        }

        int budget = live.pointsPerTimerRun(periodTicks);
        int drawn = fromOrbs(player, asItStands, budget, live);
        if (drawn == 0) {
            // Nothing loose within reach: fall back to what the holder is carrying, at the same rate.
            int room = fill.movedAtMost(bottling.experienceOf(player), asItStands, budget).moved();
            drawn = bottling.takeFrom(player, room);
        }
        if (drawn <= 0) {
            show(player, asItStands);
            return;
        }

        pending.put(id, alreadyPending + drawn);
        hands.put(id, hand);
        Bottle now = asItStands.plus(drawn);
        show(player, now);
        effects.play(id, Cues.EARNED);
        if (now.isFull()) {
            finish(player, "xpbottle.siphon.full");
        }
    }

    /**
     * Takes what it can from the loose orbs in reach, nearest first.
     *
     * <p>An orb larger than what is left to take is <em>reduced</em> rather than swallowed whole. A
     * version that took the orb and capped what it gave would destroy the difference, and a 100-point
     * orb going into a bottle with room for 3 would be 97 points that stopped existing.
     */
    private int fromOrbs(Player player, Bottle bottle, int budget, XpBottleSettings live) {
        int blocks = live.reachFor(bottle.level());
        if (blocks <= 0) {
            return 0;
        }
        Location at = player.getLocation();
        List<ExperienceOrb> orbs = new ArrayList<>();
        for (org.bukkit.entity.Entity nearby : player.getNearbyEntities(blocks, blocks, blocks)) {
            if (nearby instanceof ExperienceOrb orb && orb.isValid()
                    && reach.reaches(orb.getLocation().distanceSquared(at), blocks)) {
                orbs.add(orb);
            }
        }
        orbs.sort(Comparator.comparingDouble(orb -> orb.getLocation().distanceSquared(at)));

        int taken = 0;
        Bottle running = bottle;
        for (ExperienceOrb orb : orbs) {
            int left = budget - taken;
            if (left <= 0 || running.room() <= 0) {
                break;
            }
            int moving = fill.movedAtMost(orb.getExperience(), running, left).moved();
            if (moving <= 0) {
                continue;
            }
            int remainder = orb.getExperience() - moving;
            trail(player, orb.getLocation());
            if (remainder > 0) {
                orb.setExperience(remainder);
            } else {
                orb.remove();
            }
            running = running.plus(moving);
            taken += moving;
        }
        return taken;
    }

    /** The bottle is full, or the draw ended on purpose: write it in and say so. */
    private void finish(Player player, String messageKey) {
        int written = flush(player);
        player.clearActiveItem();
        clicked.remove(player.getUniqueId());
        actionBars.clear(player.getUniqueId(), "xpbottle");
        if (written > 0 || messageKey != null) {
            messages.send(player, messageKey);
            effects.play(player.getUniqueId(), Cues.OK);
        }
    }

    /** The draw ended because the bottle is no longer one: write in whatever was drawn, quietly. */
    private void stop(Player player) {
        flush(player);
        clicked.remove(player.getUniqueId());
        actionBars.clear(player.getUniqueId(), "xpbottle");
    }

    /**
     * Writes whatever this player has drawn into the bottle they drew it with.
     *
     * <p>The one place experience drawn out of the world becomes experience somebody has. If the
     * bottle is no longer in the hand it was drawn with — dropped, traded, swapped — the points go
     * back into the player instead. Losing them was never an option: they came out of orbs that no
     * longer exist.
     *
     * @return how many points were written in
     */
    public int flush(Player player) {
        if (player == null) {
            return 0;
        }
        UUID id = player.getUniqueId();
        Integer drawn = pending.remove(id);
        EquipmentSlot hand = hands.remove(id);
        if (drawn == null || drawn <= 0) {
            return 0;
        }
        ItemStack held = player.getInventory().getItem(hand == null ? EquipmentSlot.HAND : hand);
        Optional<Bottle> bottle = BottleTags.read(held, settings);
        if (bottle.isEmpty() || !bottle.get().mayVacuum()) {
            player.giveExp(drawn);
            return drawn;
        }
        forge.dress(held, bottle.get().plus(drawn));
        return drawn;
    }

    /** Everybody mid-draw, written in — for a module stopping or a server shutting down. */
    public int flushAll(Collection<? extends Player> online) {
        if (online == null) {
            return 0;
        }
        int written = 0;
        for (Player player : online) {
            written += flush(player);
        }
        return written;
    }

    /** A player who has left is not drawing; what they had drawn goes into their bottle first. */
    public void forget(UUID player) {
        if (player == null) {
            return;
        }
        pending.remove(player);
        hands.remove(player);
        clicked.remove(player);
    }

    /** How many players are mid-draw — for the diagnostic, and for a test to see the map emptied. */
    public int drawing() {
        return pending.size();
    }

    private EquipmentSlot handOf(Player player) {
        if (player.hasActiveItem() && isSiphon(player.getActiveItem())) {
            return player.getActiveItemHand();
        }
        return hands.getOrDefault(player.getUniqueId(), EquipmentSlot.HAND);
    }

    private boolean isSiphon(ItemStack stack) {
        return BottleTags.read(stack, settings).map(Bottle::mayVacuum).orElse(false);
    }

    private void show(Player player, Bottle bottle) {
        Component bar = MINI.deserialize("<gray>Siphon <white>" + bottle.stored()
                + "</white><gray>/</gray><white>" + bottle.capacity() + "</white> <gray>points");
        actionBars.show(player.getUniqueId(), "xpbottle", bar, Duration.ofMillis(1200),
                ActionBarPriority.NORMAL);
    }

    private void trail(Player player, Location from) {
        player.getWorld().spawnParticle(Particle.ENCHANT, from.clone().add(0, 0.2, 0), 6,
                0.15, 0.15, 0.15, 0.02);
    }
}
