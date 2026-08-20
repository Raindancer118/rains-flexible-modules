package de.raindancer.modules.xaeromap.model;

/**
 * Whose claims a player is shown on their own map.
 *
 * <p>Not a boolean, because the two answers are about different things and a server picks one on
 * purpose: a survival server where claims are public knowledge — their names are already written on
 * every border — wants {@link #EVERYBODY}, and a server where knowing where people live is worth
 * hiding wants {@link #MINE_AND_SHARED}. Anything in between is a per-claim decision, which is the
 * claim's own business rather than this module's.
 */
public enum MapAudience {

    /** Every claim in every world. What a player already learns by walking. */
    EVERYBODY,

    /** Only claims the viewer owns or has been trusted on. */
    MINE_AND_SHARED
}
