package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import org.bukkit.Material;

/**
 * Whether a hit was the Banhammer: a mace whose name reads "Banhammer" — any colour, any case — landing
 * the blow itself (one hit is enough, nobody has to die), swung by somebody holding {@code rains.moderation.banhammer}, with the
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
    public static final String ALREADY_BANNED = "moderation.banhammer.already-banned";

    /**
     * One hit, as the listener saw it.
     *
     * @param swungByKiller the attacker's own melee swing, not a projectile, a fall or thorns
     * @param alreadyBanned a mace that hits twice in one swing must not ban twice
     * @param victimImmune  the victim is a protected account ({@code store.ImmuneStaff} or an operator)
     */
    public record Strike(boolean enabled, boolean mayUse, Material weapon, String weaponName,
                         boolean swungByKiller, boolean self, boolean victimImmune, boolean alreadyBanned) {

        public Strike(boolean enabled, boolean mayUse, Material weapon, String weaponName,
                      boolean swungByKiller, boolean self, boolean victimImmune) {
            this(enabled, mayUse, weapon, weaponName, swungByKiller, self, victimImmune, false);
        }
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
        if (strike.alreadyBanned()) {
            return Verdict.refused(ALREADY_BANNED);
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

    /**
     * Whether a right click puts the hammer away into its owner's vault: sneaking, with the main hand,
     * holding the Banhammer, and having a vault at all. Only the main hand, because Paper asks once per
     * hand and the hammer must not be put away by the hand that is not holding it.
     */
    public boolean stashes(boolean sneaking, boolean mainHand, Material held, String heldName,
                           boolean hasVault) {
        return sneaking && mainHand && hasVault && isBanhammer(held, heldName);
    }

    /**
     * Whether a hit of this damage type is somebody swinging what they hold. Thorns, for one, is booked
     * to the player wearing the armour — so without this, hitting an op who held the Banhammer while
     * wearing Thorns got the attacker banned, by an op who never swung.
     */
    public static boolean isSwing(String damageType) {
        return "minecraft:player_attack".equals(damageType) || "minecraft:mace_smash".equals(damageType);
    }

    public static String reason(String swingerName) {
        return "YOU'VE BEEN HIT WITH THE BANHAMMER BY " + swingerName;
    }

    @Override
    public String describe() {
        return "whether a kill was the Banhammer";
    }
}
