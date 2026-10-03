package de.raindancer.modules.manhunt.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything worth knowing before the start whistle, each with the one click that fixes it.
 *
 * <p>Pure: the lobby is described as a {@link Situation}, so every rule here is a test rather than an
 * evening of pressing the start block. Blockers are what the lobby itself would refuse, or a hunt that
 * could never end the way it is meant to; warnings are things somebody may well want, said once.
 */
public final class Preflight {

    public enum Severity { BLOCKER, WARNING, INFO }

    /** The click that fixes a check — {@code NONE} where only waiting or talking helps. */
    public enum Fix { NONE, PICK_RANDOM_RUNNER, AUTO_BALANCE, OPEN_SIDES, REMOVE_GOAL, CHOOSE_GOAL, KEEP_DOOR_OPEN }

    /**
     * @param key        the wording key under {@code manhunt.preflight}
     * @param placeholder one value for the line's {@code <value>}, or empty
     */
    public record Check(Severity severity, String key, Fix fix, String placeholder) {
    }

    /**
     * The lobby, as the check needs it.
     *
     * @param present         everybody who would be swept into the hunt
     * @param runnersPresent  how many of them are on the Runner side
     * @param runnersAway     names on the Runner side who are not here
     * @param goalKey         the speedrun goal, empty for none
     * @param goalKnown       whether this server has that advancement
     * @param runnersExpected the Runners' chance by the ratings, 0..1
     */
    public record Situation(boolean lobbyRunning, boolean huntRunning, int present, int runnersPresent,
                            List<String> runnersAway, String goalKey, boolean goalKnown,
                            boolean closesWhitelist, boolean whitelistClosed, double runnersExpected) {
    }

    private Preflight() {
    }

    public static List<Check> check(Situation s) {
        if (!s.lobbyRunning()) {
            return List.of(new Check(Severity.BLOCKER, "no-lobby", Fix.NONE, ""));
        }
        if (s.huntRunning()) {
            return List.of(new Check(Severity.BLOCKER, "hunt-running", Fix.NONE, ""));
        }
        if (s.present() < 2) {
            return List.of(new Check(Severity.BLOCKER, "too-few", Fix.NONE, String.valueOf(s.present())));
        }
        List<Check> checks = new ArrayList<>();
        boolean runners = s.runnersPresent() > 0;
        boolean hunters = s.runnersPresent() < s.present();
        if (!runners) {
            checks.add(new Check(Severity.BLOCKER, "no-runner", Fix.PICK_RANDOM_RUNNER, ""));
        }
        if (!hunters) {
            checks.add(new Check(Severity.BLOCKER, "no-hunter", Fix.AUTO_BALANCE, ""));
        }
        boolean goal = s.goalKey() != null && !s.goalKey().isBlank();
        if (goal && !s.goalKnown()) {
            checks.add(new Check(Severity.BLOCKER, "goal-unknown", Fix.REMOVE_GOAL, s.goalKey()));
        }
        if (!goal) {
            checks.add(new Check(Severity.INFO, "no-goal", Fix.CHOOSE_GOAL, ""));
        }
        if (!s.runnersAway().isEmpty()) {
            checks.add(new Check(Severity.WARNING, "runner-away", Fix.OPEN_SIDES, String.join(", ", s.runnersAway())));
        }
        if (s.closesWhitelist() && !s.whitelistClosed()) {
            checks.add(new Check(Severity.WARNING, "whitelist-will-close", Fix.KEEP_DOOR_OPEN, ""));
        }
        if (runners && hunters && (s.runnersExpected() < 0.2 || s.runnersExpected() > 0.8)) {
            checks.add(new Check(Severity.WARNING, "lopsided", Fix.AUTO_BALANCE,
                    String.valueOf(Math.round(s.runnersExpected() * 100))));
        }
        return List.copyOf(checks);
    }

    /** Whether nothing stands in the way — warnings are the admin's call. */
    public static boolean ready(List<Check> checks) {
        return checks.stream().noneMatch(check -> check.severity() == Severity.BLOCKER);
    }
}
