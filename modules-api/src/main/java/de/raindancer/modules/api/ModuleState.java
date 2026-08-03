package de.raindancer.modules.api;

/**
 * Where a module got to.
 *
 * <p>Five outcomes rather than a boolean, because "not running" has four different causes and a player
 * typing the command deserves to be told which. {@code SKIPPED} means nothing went wrong with this
 * module at all — something it needed was not there.
 */
public enum ModuleState {

    /** Declared, not started yet. What every module is during the bootstrap phase. */
    NEW,

    /** Running. */
    ENABLED,

    /** It threw on the way up, or the host could not build it a context. */
    FAILED,

    /** Never attempted: something it required is missing, was skipped, or failed. */
    SKIPPED,

    /** It ran and has been stopped. */
    DISABLED,

    /** This host has never heard of it. */
    ABSENT
}
