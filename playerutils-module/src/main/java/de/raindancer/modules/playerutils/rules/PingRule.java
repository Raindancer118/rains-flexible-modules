package de.raindancer.modules.playerutils.rules;

/** How good a ping is, in words, a colour and five bars. */
public final class PingRule implements IPlayerUtilsRule {

    public record Grade(int milliseconds, int bars, String label, String colour) {

        public String drawn() {
            return "<" + colour + ">" + "▮".repeat(bars) + "<dark_gray>" + "▯".repeat(5 - bars);
        }
    }

    public Grade grade(int milliseconds) {
        if (milliseconds < 0) {
            return new Grade(milliseconds, 0, "not measured yet", "gray");
        }
        if (milliseconds < 50) {
            return new Grade(milliseconds, 5, "excellent", "green");
        }
        if (milliseconds < 100) {
            return new Grade(milliseconds, 4, "good", "green");
        }
        if (milliseconds < 150) {
            return new Grade(milliseconds, 3, "okay", "yellow");
        }
        if (milliseconds < 250) {
            return new Grade(milliseconds, 2, "laggy", "gold");
        }
        if (milliseconds < 400) {
            return new Grade(milliseconds, 1, "bad", "red");
        }
        return new Grade(milliseconds, 0, "dreadful", "dark_red");
    }

    @Override
    public String describe() {
        return "grading a ping";
    }
}
