package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Effect;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.SoundCue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Collection;

/**
 * What a fall and a win look and sound like in a pit. Only ever shown: the lightning is an effect (no damage, no fire),
 * the particles touch nothing.
 */
public final class Drama {

    private static final Title.Times QUICK = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1600),
            Duration.ofMillis(500));

    public static final String FALL = "arena:fall";
    public static final String FINAL_FALL = "arena:final-fall";
    public static final String DEFEATED = "arena:defeated";
    public static final String TRIUMPH = "arena:triumph";

    private final Plugin plugin;
    private final Effects effects;

    public Drama(Plugin plugin, Effects effects) {
        this.plugin = plugin;
        this.effects = effects;
        // Only where nothing is bound yet: an owner's own choice of sound stays.
        defineIfMissing(FALL, new SoundCue("entity.lightning_bolt.thunder", 0.5f, 1.1f));
        defineIfMissing(FINAL_FALL, new SoundCue("entity.lightning_bolt.thunder", 0.9f, 0.7f));
        defineIfMissing(DEFEATED, new SoundCue("entity.wither.hurt", 0.6f, 0.6f));
        defineIfMissing(TRIUMPH, new SoundCue("ui.toast.challenge_complete", 0.8f, 1.0f));
    }

    private void defineIfMissing(String cue, SoundCue sound) {
        if (!effects.isDefined(cue)) {
            effects.define(cue, Effect.of(sound));
        }
    }

    /** Somebody went down: a flash of lightning where they stood, soul fire, thunder for everybody watching. */
    public void fall(Player victim, Component title, Component subtitle, Collection<Player> audience, boolean decisive) {
        Location at = victim.getLocation();
        Scheduling.region(plugin, at, () -> {
            at.getWorld().strikeLightningEffect(at);
            at.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), decisive ? 120 : 60,
                    0.4, 0.8, 0.4, 0.08);
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.5, 0), 30, 0.5, 0.3, 0.5, 0.02);
        });
        effects.playForAll(audience.stream().map(Player::getUniqueId).toList(), decisive ? FINAL_FALL : FALL);
        effects.play(victim.getUniqueId(), DEFEATED);
        Scheduling.entity(plugin, victim, () -> victim.showTitle(Title.title(title, subtitle, QUICK)));
    }

    /** The fight is won: a title, a burst of the totem's colours, and a fanfare for each winner. */
    public void triumph(Player winner, Component title, Component subtitle) {
        Scheduling.entity(plugin, winner, () -> {
            winner.showTitle(Title.title(title, subtitle, Title.Times.times(Duration.ofMillis(150),
                    Duration.ofMillis(2500), Duration.ofMillis(800))));
            Location at = winner.getLocation().add(0, 1, 0);
            winner.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 160, 0.6, 1.0, 0.6, 0.5);
            winner.getWorld().spawnParticle(Particle.FIREWORK, at.clone().add(0, 1.5, 0), 80, 0.8, 0.8, 0.8, 0.15);
        });
        effects.play(winner.getUniqueId(), TRIUMPH);
    }
}
