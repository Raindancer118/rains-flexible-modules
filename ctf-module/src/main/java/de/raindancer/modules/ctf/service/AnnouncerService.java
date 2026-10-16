package de.raindancer.modules.ctf.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.combat.Streaks;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.CaptureShow;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * The voice of a round: "Flag taken!", "First blood!", "Denied!" — a title, a sound and a chat line, to everybody or to
 * one side. And the show of a capture: fireworks in the scoring side's colour, everybody near slowed for a breath.
 *
 * <p>Every sound is a Core cue ({@code ctf:*}) an owner can change in Core's cue menu; this only defines them where
 * nobody has.
 */
public final class AnnouncerService implements ICtfService {

    public static final String TAKEN = "ctf:flag-taken";
    public static final String DROPPED = "ctf:flag-dropped";
    public static final String RETURNED = "ctf:flag-returned";
    public static final String CAPTURED = "ctf:capture";
    public static final String STREAK = "ctf:streak";
    public static final String FIRST_BLOOD = "ctf:first-blood";
    public static final String DENIED = "ctf:denied";
    public static final String OVERTIME = "ctf:overtime";
    public static final String WIN = "ctf:win";
    public static final String LOSE = "ctf:lose";
    public static final String TICK = "ctf:tick";
    public static final String GO = "ctf:go";

    private static final Title.Times QUICK = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1400),
            Duration.ofMillis(400));
    private static final Title.Times LONG = Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000),
            Duration.ofMillis(800));

    private final java.util.Set<UUID> rockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Plugin plugin;
    private final Messages messages;
    private final Effects effects;
    private volatile CtfSettings settings;

    public AnnouncerService(Plugin plugin, Messages messages, Effects effects, CtfSettings settings) {
        this.plugin = plugin;
        this.messages = messages;
        this.effects = effects;
        this.settings = settings;
        define(TAKEN, new SoundCue("block.note_block.bell", 1.0f, 1.4f));
        define(DROPPED, new SoundCue("entity.item.pickup", 1.0f, 0.6f));
        define(RETURNED, new SoundCue("block.beacon.activate", 0.8f, 1.6f));
        define(CAPTURED, new SoundCue("ui.toast.challenge_complete", 0.9f, 1.1f));
        define(STREAK, new SoundCue("entity.ender_dragon.growl", 0.4f, 1.6f));
        define(FIRST_BLOOD, new SoundCue("entity.lightning_bolt.thunder", 0.4f, 1.4f));
        define(DENIED, new SoundCue("block.anvil.land", 0.7f, 1.2f));
        define(OVERTIME, new SoundCue("event.raid.horn", 1.0f, 1.0f));
        define(WIN, new SoundCue("ui.toast.challenge_complete", 1.0f, 1.0f));
        define(LOSE, new SoundCue("entity.wither.hurt", 0.5f, 0.7f));
        define(TICK, new SoundCue("block.note_block.hat", 1.0f, 1.2f));
        define(GO, new SoundCue("block.note_block.pling", 1.0f, 1.8f));
    }

    private void define(String cue, SoundCue sound) {
        if (!effects.isDefined(cue)) {
            effects.define(cue, Effect.of(sound));
        }
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    /** A line in chat to everybody listed, a sound with it. */
    public void tell(Collection<UUID> audience, String key, String cue, Object... values) {
        for (UUID id : audience) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                messages.send(player, key, values);
            }
        }
        if (cue != null) {
            effects.playForAll(audience, cue);
        }
    }

    /** A title, as an announcer would say it — only when the announcer is on. */
    public void call(Collection<UUID> audience, String titleKey, String subtitleKey, String cue, Object... values) {
        if (!settings.announcer()) {
            return;
        }
        Component title = messages.get(titleKey, values);
        Component subtitle = subtitleKey == null ? Component.empty() : messages.get(subtitleKey, values);
        for (UUID id : audience) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Scheduling.entity(plugin, player, () -> player.showTitle(Title.title(title, subtitle, QUICK)));
            }
        }
        if (cue != null) {
            effects.playForAll(audience, cue);
        }
    }

    /** The big one: a round won or lost. Always shown, announcer or not. */
    public void finale(Player player, Component title, Component subtitle, boolean won) {
        Scheduling.entity(plugin, player, () -> player.showTitle(Title.title(title, subtitle, LONG)));
        effects.play(player.getUniqueId(), won ? WIN : LOSE);
    }

    /** What a kill is called, to everybody in the round. */
    public void callouts(Collection<UUID> audience, Streaks.Kill kill, String killerName) {
        if (!settings.announcer()) {
            return;
        }
        for (Streaks.Callout callout : kill.callouts()) {
            String key = "ctf.announce." + callout.name().toLowerCase(Locale.ROOT).replace('_', '-');
            String cue = callout == Streaks.Callout.FIRST_BLOOD ? FIRST_BLOOD : STREAK;
            if (callout == Streaks.Callout.DOUBLE_KILL || callout == Streaks.Callout.TRIPLE_KILL
                    || callout == Streaks.Callout.MULTI_KILL || callout == Streaks.Callout.FIRST_BLOOD
                    || callout.streak() > 0 || callout == Streaks.Callout.SHUTDOWN) {
                call(audience, key, "ctf.announce.by", cue, "player", killerName);
            }
        }
    }

    /** Whether this is one of the capture show's rockets — which hurt nobody. */
    public boolean isShowRocket(org.bukkit.entity.Entity entity) {
        return rockets.contains(entity.getUniqueId());
    }

    /** Fireworks for a capture, in the side's colour and the scorer's chosen burst; everybody near slowed a moment. */
    public void captureShow(Location at, Color colour, CaptureShow show) {
        if (!settings.captureShow()) {
            return;
        }
        Scheduling.region(plugin, at, () -> {
            for (int burst = 0; burst < 3; burst++) {
                Location spot = at.clone().add((burst - 1) * 1.5, 2 + burst, 0);
                Firework firework = at.getWorld().spawn(spot, Firework.class, rocket -> {
                    var meta = rocket.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder().with(show.type()).withColor(colour)
                            .withFade(Color.WHITE).flicker(show.flickers()).trail(true).build());
                    meta.setPower(0);
                    rocket.setFireworkMeta(meta);
                    rocket.setPersistent(false);
                });
                rockets.add(firework.getUniqueId());
                Scheduling.entityLater(plugin, firework, 2L + burst * 3L, firework::detonate);
                // The rocket is gone by then, so the forgetting cannot hang on its own scheduler.
                Scheduling.globalLater(plugin, 40L, () -> rockets.remove(firework.getUniqueId()));
            }
            for (Player near : at.getNearbyPlayers(12)) {
                Scheduling.entity(plugin, near, () -> near.addPotionEffect(
                        new PotionEffect(PotionEffectType.SLOWNESS, 25, 2, false, false, false)));
            }
        });
    }
}
