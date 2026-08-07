package de.raindancer.modules.hungergames.service;

import de.raindancer.core.ui.effect.Effects;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@link EventEndpoints.SoundEffects}, over Core's {@link Effects} — asking for a cue by meaning, the way
 * every sound and particle in this module is played, rather than a name only this module's own catalogue
 * would know.
 */
public final class SoundEffectsApiAdapter implements EventEndpoints.SoundEffects {

    private final Effects effects;

    public SoundEffectsApiAdapter(Effects effects) {
        this.effects = effects;
    }

    @Override
    public List<String> knownCues() {
        return List.copyOf(effects.all().keySet());
    }

    @Override
    public boolean test(Player player, String cueKey) {
        if (!effects.isDefined(cueKey)) {
            return false;
        }
        effects.play(player.getUniqueId(), cueKey);
        return true;
    }
}
