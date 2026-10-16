package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.TieBreak;

import java.util.Optional;

/** Who has won, and when. */
public final class WinRule implements ICtfRule {

    /** How a round's time running out ends. {@code side} is the winner for {@link Kind#WIN}, else -1. */
    public record Ending(Kind kind, int side) {
        public enum Kind { WIN, DRAW, OVERTIME, SUDDEN_DEATH }

        static Ending draw() {
            return new Ending(Kind.DRAW, -1);
        }
    }

    /** After a capture: the side that has won, if any. In overtime the next capture is enough. */
    public Optional<Integer> afterCapture(int[] scores, int target, boolean overtime) {
        int leader = leader(scores);
        if (leader < 0) {
            return Optional.empty();
        }
        return overtime || scores[leader] >= target ? Optional.of(leader) : Optional.empty();
    }

    public Ending atTimeUp(int[] scores, TieBreak tieBreak, boolean alreadyOvertime) {
        int leader = leader(scores);
        if (leader >= 0) {
            return new Ending(Ending.Kind.WIN, leader);
        }
        if (alreadyOvertime) {
            return Ending.draw();
        }
        return switch (tieBreak) {
            case SUDDEN_DEATH -> new Ending(Ending.Kind.SUDDEN_DEATH, -1);
            case NEXT_CAPTURE -> new Ending(Ending.Kind.OVERTIME, -1);
            case DRAW -> Ending.draw();
        };
    }

    /** With every other side empty, the one side still playing wins. */
    public Optional<Integer> lastSideStanding(int[] playersPerSide) {
        int standing = -1;
        for (int side = 0; side < playersPerSide.length; side++) {
            if (playersPerSide[side] > 0) {
                if (standing >= 0) {
                    return Optional.empty();
                }
                standing = side;
            }
        }
        return standing < 0 ? Optional.empty() : Optional.of(standing);
    }

    /** The side alone at the top, or -1 for a tie. */
    static int leader(int[] scores) {
        int best = -1;
        boolean tie = false;
        for (int side = 0; side < scores.length; side++) {
            if (best < 0 || scores[side] > scores[best]) {
                best = side;
                tie = false;
            } else if (scores[side] == scores[best]) {
                tie = true;
            }
        }
        return tie ? -1 : best;
    }
}
