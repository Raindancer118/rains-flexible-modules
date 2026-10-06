package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.bukkit.Material;

/**
 * Whether a kill was the Banhammer: a mace whose name reads "Banhammer" — any colour, any case — landing
 * the killing blow itself, swung by somebody holding {@code rains.moderation.banhammer}, with the
 * setting on. Anything short of all of that is an ordinary kill.
 *
 * <p>The name is compared as plain text, so a hammer painted with names-module's gradients is still the
 * hammer. "Landing the blow" excludes an arrow or a fall while holding it: the ban has to be the
 * swing somebody meant.
 */
public final class BanhammerRule implements IModerationRule {

    public static final String NAME = "Banhammer";

    public static final String OFF = "moderation.banhammer.switched-off";
    public static final String NOT_ALLOWED = "moderation.banhammer.not-allowed";
    public static final String NOT_THE_HAMMER = "moderation.banhammer.not-the-hammer";
    public static final String NOT_THE_BLOW = "moderation.banhammer.not-the-blow";
    public static final String SELF = "moderation.banhammer.self";
    public static final String IMMUNE = "moderation.banhammer.immune";

    /**
     * One kill, as the listener saw it.
     *
     * @param swungByKiller the killer's own melee hit dealt the death, not a projectile or the fall
     * @param victimImmune  the victim is a protected account ({@code store.ImmuneStaff} or an operator)
     */
    public record Strike(boolean enabled, boolean mayUse, Material weapon, String weaponName,
                         boolean swungByKiller, boolean self, boolean victimImmune) {
    }

    public boolean isBanhammer(Material weapon, String plainName) {
        return weapon == Material.MACE && plainName != null && plainName.strip().equalsIgnoreCase(NAME);
    }

    public Verdict judge(Strike strike) {
        if (!strike.enabled()) {
            return Verdict.refused(OFF);
        }
        if (!isBanhammer(strike.weapon(), strike.weaponName())) {
            return Verdict.refused(NOT_THE_HAMMER);
        }
        if (!strike.mayUse()) {
            return Verdict.refused(NOT_ALLOWED);
        }
        if (!strike.swungByKiller()) {
            return Verdict.refused(NOT_THE_BLOW);
        }
        if (strike.self()) {
            return Verdict.refused(SELF);
        }
        if (strike.victimImmune()) {
            return Verdict.refused(IMMUNE);
        }
        return Verdict.allowed();
    }

    /**
     * Only a refusal of somebody who meant it is worth a line: the immune victim. Every other refusal is
     * an ordinary kill, and an ordinary kill says nothing.
     */
    public boolean tellsTheSwinger(Verdict verdict) {
        return verdict.isRefused() && IMMUNE.equals(verdict.reason());
    }

    public static String reason(String swingerName) {
        return "YOU'VE BEEN HIT WITH THE BANHAMMER BY " + swingerName;
    }

    @Override
    public String describe() {
        return "whether a kill was the Banhammer";
    }
}
