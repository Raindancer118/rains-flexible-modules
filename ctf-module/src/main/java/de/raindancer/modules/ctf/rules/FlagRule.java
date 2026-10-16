package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.Fighter;
import de.raindancer.modules.ctf.model.Flag;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Round;

import java.util.Optional;

/** What touching a flag does, and where one is brought home. Asked holding the round. */
public final class FlagRule implements ICtfRule {

    /** What a touch comes to. */
    public enum Touch { TAKE, RETURN, MAY_NOT_CARRY, NOTHING }

    /** How hot a flag held a long time is. */
    public enum Heat { COOL, GLOWING, SLOWING }

    /** A capture may be made this far above or below the spot — standing on the pedestal still counts. */
    public static final double REACH_UP = 2.5;

    public Touch touch(Round round, Fighter who, Flag flag) {
        if (!who.isAlive() || flag.state() == Flag.State.CARRIED) {
            return Touch.NOTHING;
        }
        boolean own = !flag.isNeutral() && flag.side() == who.side();
        if (own) {
            return flag.state() == Flag.State.DROPPED ? Touch.RETURN : Touch.NOTHING;
        }
        if (round.carriedBy(who.id()).isPresent()) {
            return Touch.NOTHING;
        }
        return who.role().mayCarry() ? Touch.TAKE : Touch.MAY_NOT_CARRY;
    }

    /**
     * Whether the flag {@code who} carries counts as captured where they stand.
     *
     * @param ownHomeToScore whether a side's own flag must be at home for it to score (classic rules)
     */
    public boolean mayCapture(Round round, Fighter who, Point at, double radius, boolean ownHomeToScore) {
        Optional<Flag> carried = round.carriedBy(who.id());
        if (carried.isEmpty() || !who.isAlive()) {
            return false;
        }
        Optional<Point> goal = goal(round, who.side(), carried.get());
        if (goal.isEmpty()) {
            return false;
        }
        Point target = goal.get();
        if (at.flatDistanceSquared(target.x(), target.z()) > radius * radius
                || Math.abs(at.y() - target.y()) > REACH_UP) {
            return false;
        }
        if (round.type().everySideHasAFlag() && ownHomeToScore && !round.isSuddenDeath()) {
            return round.flagOf(who.side()).map(own -> own.state() == Flag.State.HOME).orElse(true);
        }
        return true;
    }

    /**
     * Where a side scores the flag it carries: its own flag's spot, for the neutral flag the other side's base, in
     * one-flag rounds the attackers' own base.
     */
    public Optional<Point> goal(Round round, int side, Flag carried) {
        if (carried.isNeutral()) {
            for (int other = 0; other < round.sides().size(); other++) {
                if (other != side) {
                    return Optional.of(round.abs(round.baseOf(other).flag()));
                }
            }
            return Optional.empty();
        }
        if (carried.side() == side) {
            return Optional.empty();
        }
        Base home = round.baseOf(side);
        if (round.isSuddenDeath()) {
            return Optional.of(round.abs(home.flag()));
        }
        return Optional.of(round.flagOf(side).filter(own -> round.type().everySideHasAFlag())
                .map(Flag::home).orElse(round.abs(home.flag())));
    }

    public boolean returnDue(Flag flag, long now, int returnSeconds) {
        return flag.state() == Flag.State.DROPPED && now - flag.since() >= returnSeconds * 1000L;
    }

    public Heat heat(long carriedMillis, int glowAfterSeconds, int slowAfterSeconds) {
        if (slowAfterSeconds > 0 && carriedMillis >= slowAfterSeconds * 1000L) {
            return Heat.SLOWING;
        }
        if (glowAfterSeconds > 0 && carriedMillis >= glowAfterSeconds * 1000L) {
            return Heat.GLOWING;
        }
        return Heat.COOL;
    }

    /** How long somebody has to stay at a flag to take it. A dropped flag is grabbed; a scout grabs any. */
    public long pickupMillis(Role role, double pickupSeconds, boolean atHome) {
        if (!atHome || role == Role.SCOUT) {
            return 0;
        }
        return Math.round(Math.max(0, pickupSeconds) * 1000);
    }

    /**
     * Whether stopping a carrier here was a clutch: they were within {@code radius} of where they would have scored.
     *
     * @param carrierSide the side of the carrier who fell
     */
    public boolean isClutch(Round round, int carrierSide, Point fellAt, double radius) {
        Point home = round.abs(round.baseOf(carrierSide).flag());
        return fellAt.flatDistanceSquared(home.x(), home.z()) <= radius * radius;
    }
}
