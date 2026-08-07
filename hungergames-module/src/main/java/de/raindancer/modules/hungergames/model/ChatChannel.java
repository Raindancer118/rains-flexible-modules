package de.raindancer.modules.hungergames.model;

/**
 * Where a tribute's typed message goes.
 *
 * <h2>Why {@link #SPECTATOR} is not a choice</h2>
 * {@link #TEAM} and {@link #ALL} are a preference {@link de.raindancer.modules.hungergames.service
 * .ChatChannelService} remembers and a player can switch between. {@code SPECTATOR} is never stored as
 * one — it is what {@code ChatChannelService.effectiveChannel} answers for anybody the round has already
 * eliminated, overriding whatever they last chose, and there is no command that asks for it back. A
 * spectator picking their own channel back into {@code TEAM} or {@code ALL} is exactly the thing being
 * eliminated is supposed to end: talking to tributes who are still playing.
 */
public enum ChatChannel {

    /** Only this tribute's own team hears it. */
    TEAM,

    /** Every living tribute hears it — the default, and what anybody with no team falls back to. */
    ALL,

    /** Every other eliminated tribute hears it, and nobody still playing does. */
    SPECTATOR
}
