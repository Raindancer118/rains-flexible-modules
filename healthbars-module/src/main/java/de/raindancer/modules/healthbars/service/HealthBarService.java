package de.raindancer.modules.healthbars.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.healthbars.HealthBarsSettings;
import de.raindancer.modules.healthbars.rules.HealthStyle;
import de.raindancer.modules.healthbars.rules.HealthTextRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A line of health under the name of every player, and over every mob that is hurt or was lately hit — a text
 * display following each, hidden from everybody and shown only to the players who want health bars. Displays
 * are not persistent, so a crash leaves none behind.
 */
public final class HealthBarService {

    /** Every this many ticks the bars follow and are redrawn; the display glides between, so this is smooth. */
    public static final long FOLLOW_TICKS = 2;
    /** Every this many follows, who sees which bar is checked again. */
    private static final int SIGHT_EVERY = 5;
    /** Just above the head, under where the name is drawn. */
    private static final double ABOVE_HEAD = 0.15;
    /** A player's name tag starts where a mob's bar would be: theirs sits right on the head, under the tag. */
    private static final double ABOVE_A_PLAYERS_HEAD = 0.0;
    private static final NamespacedKey STYLE = new NamespacedKey(PlayerSwitch.NAMESPACE, "rainshealthbars-style");

    private final Plugin plugin;
    private final Supplier<HealthBarsSettings> settings;
    private final Vanish vanish;
    private final HealthTextRule rule = new HealthTextRule();
    /** Per wearer, one display per style somebody looks at: each viewer is shown the one in their style. */
    private final Map<UUID, Map<HealthStyle, TextDisplay>> bars = new ConcurrentHashMap<>();
    private final Map<UUID, Map<HealthStyle, String>> drawn = new ConcurrentHashMap<>();
    /** The styles of everybody online who wants bars — only those are drawn. */
    private volatile Set<HealthStyle> inUse = EnumSet.noneOf(HealthStyle.class);
    private final Map<UUID, Long> lastHit = new ConcurrentHashMap<>();
    private long follows;

    public HealthBarService(Plugin plugin, Supplier<HealthBarsSettings> settings, Vanish vanish) {
        this.plugin = plugin;
        this.settings = settings;
        this.vanish = vanish;
    }

    public PlayerSwitch wants() {
        return new PlayerSwitch("rainshealthbars", "shown", settings.get().onByDefault());
    }

    /** Which style a player sees bars in. Kept beside their switch, so it follows them across the network. */
    public HealthStyle style(Player player) {
        String stored = player.getPersistentDataContainer().get(STYLE, PersistentDataType.STRING);
        return stored == null ? settings.get().defaultStyle() : HealthStyle.of(stored);
    }

    public void style(Player player, HealthStyle style) {
        player.getPersistentDataContainer().set(STYLE, PersistentDataType.STRING, style.key());
    }

    public void hit(LivingEntity mob) {
        if (!(mob instanceof Player) && settings.get().mobs()) {
            lastHit.put(mob.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** On the global thread: every bar follows its wearer on the wearer's own thread. */
    public void tick() {
        HealthBarsSettings live = settings.get();
        if (live.players()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Scheduling.entity(plugin, player, () -> follow(player));
            }
        }
        for (UUID id : java.util.List.copyOf(lastHit.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity mob) {
                Scheduling.entity(plugin, mob, () -> follow(mob), () -> forget(id));
            } else {
                forget(id);
            }
        }
        if (follows++ % SIGHT_EVERY == 0) {
            Set<HealthStyle> styles = EnumSet.noneOf(HealthStyle.class);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (wants().isOn(viewer)) {
                    styles.add(style(viewer));
                }
            }
            inUse = styles;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                Scheduling.entity(plugin, viewer, () -> sight(viewer));
            }
        }
    }

    private boolean wanted(LivingEntity wearer) {
        if (!wearer.isValid() || wearer.isDead()) {
            return false;
        }
        if (wearer instanceof Player player) {
            return settings.get().players() && player.getGameMode() != GameMode.SPECTATOR
                    && !vanish.isVanished(player.getUniqueId());
        }
        AttributeInstance most = wearer.getAttribute(Attribute.MAX_HEALTH);
        return settings.get().mobs() && most != null && rule.mobShown(wearer.getHealth(), most.getValue(),
                lastHit.getOrDefault(wearer.getUniqueId(), 0L), System.currentTimeMillis(), settings.get().mobSeconds());
    }

    /** On the wearer's thread. */
    private void follow(LivingEntity wearer) {
        UUID id = wearer.getUniqueId();
        if (!wanted(wearer)) {
            remove(id);
            if (!(wearer instanceof Player)) {
                lastHit.remove(id);
            }
            return;
        }
        Location at = wearer.getLocation().add(0, wearer.getHeight()
                + (wearer instanceof Player ? ABOVE_A_PLAYERS_HEAD : ABOVE_HEAD), 0);
        Map<HealthStyle, TextDisplay> mine = bars.computeIfAbsent(id, key -> new ConcurrentHashMap<>());
        Map<HealthStyle, String> written = drawn.computeIfAbsent(id, key -> new ConcurrentHashMap<>());
        AttributeInstance most = wearer.getAttribute(Attribute.MAX_HEALTH);
        double top = most == null ? wearer.getHealth() : most.getValue();
        for (HealthStyle style : HealthStyle.values()) {
            if (!inUse.contains(style)) {
                remove(mine.remove(style));
                written.remove(style);
                continue;
            }
            TextDisplay bar = mine.get(style);
            if (bar == null || !bar.isValid() || bar.getWorld() != at.getWorld()) {
                remove(bar);
                written.remove(style);
                bar = wearer.getWorld().spawn(at, TextDisplay.class, made -> {
                    made.setPersistent(false);
                    made.setVisibleByDefault(false);
                    made.setBillboard(Display.Billboard.CENTER);
                    made.setShadowed(true);
                    made.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    made.setTeleportDuration((int) FOLLOW_TICKS);
                });
                mine.put(style, bar);
            } else {
                bar.teleport(at);
            }
            String text = rule.text(style, wearer.getHealth(), top);
            if (!text.equals(written.get(style))) {
                written.put(style, text);
                bar.text(MiniMessage.miniMessage().deserialize(text));
            }
        }
    }

    /** On the viewer's thread: shows the bars near them in their style if they want them, hides every other. */
    private void sight(Player viewer) {
        boolean on = wants().isOn(viewer);
        HealthStyle mine = style(viewer);
        double range = settings.get().range();
        bars.forEach((wearer, styles) -> styles.forEach((style, bar) -> {
            if (!bar.isValid()) {
                return;
            }
            boolean should = on && style == mine && !wearer.equals(viewer.getUniqueId())
                    && bar.getWorld() == viewer.getWorld()
                    && bar.getLocation().distanceSquared(viewer.getLocation()) <= range * range
                    && seenBy(viewer, wearer);
            if (should && !viewer.canSee(bar)) {
                viewer.showEntity(plugin, bar);
            } else if (!should && viewer.canSee(bar)) {
                viewer.hideEntity(plugin, bar);
            }
        }));
    }

    /**
     * A bar must never tell more than the wearer does: only somebody who can see the wearer itself — not hidden
     * from them by vanish, by the anti-cheat's ESP shield or any other plugin — sees its bar, and nobody sees the
     * bar of an invisible wearer.
     */
    private static boolean seenBy(Player viewer, UUID wearer) {
        Entity entity = Bukkit.getEntity(wearer);
        if (!(entity instanceof LivingEntity living) || living.isInvisible()
                || living.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) {
            return false;
        }
        return viewer.canSee(living);
    }

    /** A wearer gone for good: its bar too. */
    public void forget(UUID wearer) {
        lastHit.remove(wearer);
        remove(wearer);
    }

    private void remove(UUID wearer) {
        drawn.remove(wearer);
        Map<HealthStyle, TextDisplay> gone = bars.remove(wearer);
        if (gone != null) {
            gone.values().forEach(this::remove);
        }
    }

    private void remove(TextDisplay bar) {
        if (bar != null && bar.isValid()) {
            Scheduling.entity(plugin, bar, bar::remove);
        }
    }

    /** At shutdown, on the server thread: every bar gone. */
    public void removeAll() {
        bars.values().forEach(styles -> styles.values().forEach(bar -> {
            if (bar.isValid()) {
                bar.remove();
            }
        }));
        bars.clear();
        drawn.clear();
        lastHit.clear();
    }

    /** A health line as this player would see it — what the style command shows as its example. */
    public Component preview(Player viewer, LivingEntity wearer) {
        AttributeInstance most = wearer.getAttribute(Attribute.MAX_HEALTH);
        return MiniMessage.miniMessage().deserialize(rule.text(style(viewer), wearer.getHealth(),
                most == null ? wearer.getHealth() : most.getValue()));
    }
}
