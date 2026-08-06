package de.raindancer.modules.hungergames.rules;

import de.raindancer.core.social.team.TeamPolicy;
import de.raindancer.modules.hungergames.model.GamePhase;

/**
 * The configurable rules a team is judged against.
 *
 * <h2>Why this exists next to {@link TeamPolicy}, and does not simply become one</h2>
 * {@link de.raindancer.core.social.team.Teams} knows nothing about a round having phases, and rightly so —
 * a clan and a party have no such notion. But a tournament's teams do stop being editable at a fixed point
 * in the round, and something has to say when: {@link #lockFromPhase} and {@link #isLocked} are that
 * something, and they are Hunger-Games-specific in a way {@code Teams} must never be told. So this record
 * stays the shape the module's settings map onto — the thing a config file edits — and {@link #toPolicy()}
 * is the one place it is translated into what Core actually asks for.
 *
 * <h2>Why {@code store.GameSession} takes a supplier of these rather than holding one</h2>
 * Because the rules can change while a lobby is open — an owner turning off team switching after seeing
 * people abuse it, or raising the team-size cap because fewer people showed up than expected — and every
 * team operation has to see the current value, not whichever one was current when the registry was built.
 * A rule itself never changes; it is an immutable value read fresh on every check.
 *
 * @param maxTeamSize           the largest a team may grow; {@code 0} means unlimited
 * @param maxTeams              the most teams that may exist at once; {@code 0} means unlimited
 * @param allowSwitching        whether a player already on a team may move to another, up to the lock
 * @param captainEnabled        whether the captain system is active
 * @param playersCanCreateTeams whether players may make their own teams through the menu
 * @param playersCanChooseColor whether players or captains may pick a team's colour
 * @param lockFromPhase         teams are frozen from this phase onward — see {@link #isLocked}
 */
public record TeamRules(
        int maxTeamSize,
        int maxTeams,
        boolean allowSwitching,
        boolean captainEnabled,
        boolean playersCanCreateTeams,
        boolean playersCanChooseColor,
        GamePhase lockFromPhase) implements IHungerGamesRule {

    /** Two-member teams, switching allowed, locked from {@code STARTUP} onward. */
    public static TeamRules defaults() {
        return new TeamRules(2, 0, true, false, true, true, GamePhase.STARTUP);
    }

    /**
     * The rules as this server has configured them.
     *
     * <p>This is what makes {@code teams.*} in {@code config.yml} mean anything. Before it existed, every
     * caller used {@link #defaults()} — so a server that had set {@code teams.max-size: 10} still had
     * two-person teams, silently, and the setting was a decoration.
     */
    public static TeamRules from(de.raindancer.modules.hungergames.HungerGamesSettings settings) {
        return new TeamRules(
                settings.teamMaxSize(),
                settings.teamMaxTeams(),
                settings.teamAllowSwitching(),
                settings.teamCaptainEnabled(),
                settings.teamPlayersCanCreate(),
                settings.teamPlayersChooseColour(),
                settings.teamsFreezeFrom());
    }

    /** Whether teams are frozen in the given phase. */
    public boolean isLocked(GamePhase phase) {
        return phase.ordinal() >= lockFromPhase.ordinal();
    }

    /**
     * The same rules, as the policy Core's {@link de.raindancer.core.social.team.Teams} is judged against.
     *
     * <p>Colours are always exclusive here — a tournament with two teams wearing the same colour has failed
     * at the one thing a team colour is for — which is not a field on this record because no Hunger Games
     * owner has ever had a reason to turn it off. If one day they do, it becomes a field here; it does not
     * become a reason for {@link TeamPolicy} to grow a knob that only ever gets set one way.
     */
    public TeamPolicy toPolicy() {
        return new TeamPolicy(maxTeamSize, maxTeams, true, allowSwitching, captainEnabled,
                playersCanCreateTeams, playersCanChooseColor);
    }

    @Override
    public String describe() {
        return "whether a team mutation is allowed right now";
    }
}
