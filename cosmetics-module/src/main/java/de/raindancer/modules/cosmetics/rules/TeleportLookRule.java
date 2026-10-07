package de.raindancer.modules.cosmetics.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.cosmetics.model.TeleportLookChoice;

import java.util.List;

/**
 * Whether somebody may pick a teleport sound. The waiting particle is judged like a worn one, by
 * {@link ParticleRule}, after the first two checks here.
 *
 * <p>Sounds come from a list the owner keeps, because everybody near a teleport hears it: the full
 * catalogue has the dragon dying and the wither spawning in it. Going back to the server's, or to
 * nothing, is always allowed — taking something off is never a privilege.
 */
public final class TeleportLookRule implements ICosmeticsRule {

    /** The checks every part shares. Null value means "back to the server's". */
    public Verdict judgeAccess(boolean enabled, boolean mayUse, String value) {
        if (value == null || TeleportLookChoice.NONE.equals(value)) {
            return Verdict.allowed();
        }
        if (!enabled) {
            return Verdict.refused("cosmetics.teleport.switched-off");
        }
        if (!mayUse) {
            return Verdict.refused("cosmetics.teleport.no-permission");
        }
        return Verdict.allowed();
    }

    /**
     * @param known whether this server has a sound by that key, asked of the registry by the caller
     */
    public Verdict judgeSound(boolean enabled, boolean mayUse, String sound, List<String> offered,
                              boolean mayAnySound, boolean known) {
        Verdict access = judgeAccess(enabled, mayUse, sound);
        if (access.isRefused() || sound == null || TeleportLookChoice.NONE.equals(sound)) {
            return access;
        }
        if (!known) {
            return Verdict.refused("cosmetics.teleport.sound-unknown", sound);
        }
        if (!mayAnySound && !offered.contains(sound)) {
            return Verdict.refused("cosmetics.teleport.sound-not-offered", sound);
        }
        return Verdict.allowed();
    }

    /** Ultra only for somebody given it — the same answer the worn particle gets. */
    public de.raindancer.modules.cosmetics.model.ParticleDensity allowedDensity(
            de.raindancer.modules.cosmetics.model.ParticleDensity chosen, boolean mayUltra) {
        return new ParticleRule().allowed(chosen, mayUltra);
    }
}
