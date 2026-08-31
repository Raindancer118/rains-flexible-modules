package de.raindancer.modules.manhunt.model;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.social.team.TeamPolicy;
import de.raindancer.core.social.team.Teams;

import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The two sides of a Manhunt, on top of {@link Teams} rather than a module-private pair of sets —
 * see {@code TeamPolicy.match}'s own javadoc for why a fixed two-team match is exactly what it is
 * for.
 *
 * <h2>Why exactly two teams, never created or deleted by a player</h2>
 * {@link TeamPolicy#match(int, int)} with {@code teams = 2} refuses a third team outright and
 * {@code playersMayCreate = false} means nobody creates one by accident either — Runners and Hunters
 * exist for as long as this module is enabled, made once in the constructor. {@code maxMembers = 0}
 * (unlimited) because a Manhunt is not always one Runner against a pack: a server may want two people
 * running together, or a single Hunter against three Runners.
 *
 * <h2>Colours and eligibility</h2>
 * Exclusive colours matter here for the same reason a tournament wants them — telling the two sides
 * apart at a glance is the entire point of a team colour in a chase. Runners are lime, Hunters are
 * red, chosen for the same "green means go, red means stop" reading most players already carry in
 * from a hundred other games. Eligibility is always {@code true}: who may be a Runner or a Hunter is a
 * decision this module's own commands and screens make before ever calling {@link #joinRunners} —
 * {@code isEligible} exists for a hard rule (a banned player, an offline account), and Manhunt has
 * none.
 */
public final class ManhuntTeams {

    public static final TeamId RUNNERS = TeamId.fromName("Runners");
    public static final TeamId HUNTERS = TeamId.fromName("Hunters");

    private final Teams teams;

    /**
     * @param frozen whether roles may be changed right now — false the whole time no run is going,
     *               true for the duration of one, the same "fact about the moment" {@link Teams}
     *               itself asks for rather than stores
     */
    public ManhuntTeams(BooleanSupplier frozen) {
        // Never frozen for the one moment that matters before anything else can ask: the two teams
        // being made, right below. A caller wiring `frozen` to "a hunt is currently running" cannot
        // possibly mean that to also refuse the constructor's own bootstrap — a hunt cannot be
        // running before its two sides exist to run it — but Teams.create refuses outright while
        // locked, and without this a frozen-from-the-start supplier would leave both teams missing
        // forever, with every join answering NO_SUCH_TEAM instead of the FROZEN a caller actually
        // asked for.
        boolean[] bootstrapping = {true};
        this.teams = new Teams(() -> TeamPolicy.match(0, 2),
                () -> !bootstrapping[0] && frozen.getAsBoolean(), uuid -> true);
        ensureBothTeamsExist();
        bootstrapping[0] = false;
    }

    private void ensureBothTeamsExist() {
        if (teams.team(RUNNERS).isEmpty()) {
            teams.create("Runners", TeamColour.LIME);
        }
        if (teams.team(HUNTERS).isEmpty()) {
            teams.create("Hunters", TeamColour.RED);
        }
    }

    /** Puts {@code player} on the Runner team, moving them off the Hunters if they were on it. */
    public Teams.MembershipChange joinRunners(UUID player) {
        return teams.join(player, RUNNERS);
    }

    /** Puts {@code player} on the Hunter team, moving them off the Runners if they were on it. */
    public Teams.MembershipChange joinHunters(UUID player) {
        return teams.join(player, HUNTERS);
    }

    /** Takes {@code player} off whichever side they were on. Empty if they were on neither. */
    public java.util.Optional<TeamId> leave(UUID player) {
        return teams.leave(player).oldTeam();
    }

    public Set<UUID> runners() {
        return teams.team(RUNNERS).map(Team::members).orElse(Set.of());
    }

    public Set<UUID> hunters() {
        return teams.team(HUNTERS).map(Team::members).orElse(Set.of());
    }

    public boolean isRunner(UUID player) {
        return runners().contains(player);
    }

    public boolean isHunter(UUID player) {
        return hunters().contains(player);
    }

    /** Everybody on either side. */
    public Set<UUID> everybody() {
        Set<UUID> both = new java.util.LinkedHashSet<>(runners());
        both.addAll(hunters());
        return Set.copyOf(both);
    }

    /** The registry underneath, for a screen that wants {@link Team} for its colour or its name. */
    public Teams raw() {
        return teams;
    }
}
