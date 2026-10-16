package de.raindancer.modules.ctf.rules;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.CtfMap;
import de.raindancer.modules.ctf.model.Fighter;
import de.raindancer.modules.ctf.model.Flag;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Mutator;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.Role;
import de.raindancer.modules.ctf.model.Round;
import de.raindancer.modules.ctf.model.Side;

import java.util.List;
import java.util.UUID;

/** A small map and a round on it, for the rule tests: red's flag at x=0, blue's at x=100, the middle at x=50. */
final class Rounds {

    static final Point RED_FLAG = Point.of(0, 10, 0);
    static final Point BLUE_FLAG = Point.of(100, 10, 0);
    static final Point MIDDLE = Point.of(50, 10, 0);

    private Rounds() {
    }

    static CtfMap map() {
        return CtfMap.fresh("test", "test.schem", 120, 40, 20)
                .withBase(new Base(TeamColour.RED, RED_FLAG, List.of(Point.of(2, 10, 0))))
                .withBase(new Base(TeamColour.BLUE, BLUE_FLAG, List.of(Point.of(98, 10, 0))))
                .withNeutral(MIDDLE)
                .withFloor(0);
    }

    static Round round(GameType type) {
        Round round = new Round(map(), type, Mutator.NONE, "ctf", 0, 0, 0, List.of(
                new Side(0, TeamColour.RED, "Red", 0, ""),
                new Side(1, TeamColour.BLUE, "Blue", 1, "")));
        if (type == GameType.NEUTRAL) {
            round.addFlag(new Flag(Flag.NEUTRAL, MIDDLE, 0));
        } else if (type == GameType.ONE_FLAG) {
            round.defenders(1);
            round.addFlag(new Flag(1, BLUE_FLAG, 0));
        } else {
            round.addFlag(new Flag(0, RED_FLAG, 0));
            round.addFlag(new Flag(1, BLUE_FLAG, 0));
        }
        return round;
    }

    static Fighter join(Round round, String name, int side, Role role) {
        Fighter fighter = new Fighter(UUID.nameUUIDFromBytes(name.getBytes()), name, side, role, 0);
        round.add(fighter);
        return fighter;
    }
}
