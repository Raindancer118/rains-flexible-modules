package de.raindancer.modules.speedrun;

import org.bukkit.World;

/**
 * The four things a <em>player</em> can set off during a run, and the rule for whether each one is
 * allowed to go off in a given dimension.
 *
 * <h2>Why per dimension, and why each one separately</h2>
 * Because that is how speedrun rulesets actually read. "No bed bombing in the Nether" is a rule; "no
 * explosives" is not the same rule, and a host who wants the first and gets the second has lost the
 * dragon fight's crystals and every TNT trick along with it. A bed and an anchor are not even the
 * same question: a bed explodes in the Nether and the End, an anchor in the Overworld and the End,
 * and in the dimension each one <em>works</em> in there is nothing to refuse at all.
 *
 * <h2>What is deliberately not here</h2>
 * Creepers — the lobby has a whole hazard feature built around them
 * ({@link SpeedrunCreeperOnBreakListener}), they are not something a player chooses to set off, and
 * refusing them here would quietly switch that feature off from the other side. Ghast fireballs, the
 * wither, and anything else a mob does, for the same reason: this is about what a racer can do.
 *
 * <h2>Why a dimension the rule cannot apply to answers "allowed"</h2>
 * A bed in the Overworld does not explode in vanilla either. Answering anything but "allowed" would
 * mean a listener could refuse a click that was never going to blow anybody up — somebody setting
 * their spawn.
 */
public enum SpeedrunExplosive {

    /** A bed clicked in a dimension it cannot be slept in. */
    BED,
    /** A charged respawn anchor used outside the Nether. */
    RESPAWN_ANCHOR,
    /** TNT, and a minecart with TNT in it — the same switch, since they are the same trick. */
    TNT,
    /** An end crystal, whether it is hit or used to respawn the dragon. */
    END_CRYSTAL;

    /**
     * Whether this explosive may go off in {@code where}, under {@code config}.
     *
     * @param where the dimension, or {@code null} — a custom dimension and an unknown one are both
     *              left to vanilla, since these settings describe a run's own three worlds
     */
    public boolean allowedIn(SpeedrunSettings config, World.Environment where) {
        if (config == null || where == null) {
            return true;
        }
        return switch (this) {
            case BED -> switch (where) {
                case NETHER -> config.bedExplosionsInNether();
                case THE_END -> config.bedExplosionsInTheEnd();
                default -> true;   // a bed in the Overworld is a bed
            };
            case RESPAWN_ANCHOR -> switch (where) {
                case NORMAL -> config.anchorExplosionsInOverworld();
                case THE_END -> config.anchorExplosionsInTheEnd();
                default -> true;   // an anchor in the Nether is an anchor
            };
            case TNT -> switch (where) {
                case NORMAL -> config.tntInOverworld();
                case NETHER -> config.tntInNether();
                case THE_END -> config.tntInTheEnd();
                default -> true;
            };
            case END_CRYSTAL -> switch (where) {
                case NORMAL -> config.endCrystalsInOverworld();
                case NETHER -> config.endCrystalsInNether();
                case THE_END -> config.endCrystalsInTheEnd();
                default -> true;
            };
        };
    }
}
