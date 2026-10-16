package de.raindancer.modules.ctf.model;

/** What happens when time runs out with the sides level. */
public enum TieBreak {
    /** Both flags move to the middle of the map; the next capture wins. */
    SUDDEN_DEATH,
    /** Play on where you are; the next capture wins. */
    NEXT_CAPTURE,
    /** It is a draw. */
    DRAW
}
