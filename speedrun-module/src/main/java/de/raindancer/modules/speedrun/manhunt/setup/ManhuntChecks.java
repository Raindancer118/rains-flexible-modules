package de.raindancer.modules.speedrun.manhunt.setup;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.checklist.Checklist;
import de.raindancer.modules.speedrun.SpeedrunMode;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * What Manhunt adds to the lobby's one pre-flight check and to its one setup assistant: the sides
 * (somebody running, somebody hunting, everybody on a side present, the odds even enough) and the
 * door, each with its one-click fix; and the preset and the door as setup questions. The lobby's own
 * checks — the world, the goal, a run already under way — are never repeated here.
 */
public final class ManhuntChecks {

    /** The heading Manhunt's checks are shown under. */
    static final String HEADING = "Manhunt";

    /** What the fixes do — the same as {@code /manhunt random 1}, {@code balance} and {@code door}. */
    public interface Desk {
        Preflight.Situation situation();

        void randomRunner();

        void balance();

        void keepDoorOpen();

        void closeDoorOnStart();
    }

    private final Desk desk;
    private final SettingsStore<ManhuntSettings> settings;
    private final Consumer<Player> openSides;

    /** @param openSides opens the sides page for a player — the fix for a Runner who is not here */
    public ManhuntChecks(Desk desk, SettingsStore<ManhuntSettings> settings, Consumer<Player> openSides) {
        this.desk = desk;
        this.settings = settings;
        this.openSides = openSides;
    }

    /** The real desk. */
    public static Desk of(HuntDesk desk) {
        return new Desk() {
            public Preflight.Situation situation() {
                return desk.situation();
            }

            public void randomRunner() {
                desk.randomRunners(1);
            }

            public void balance() {
                desk.balance();
            }

            public void keepDoorOpen() {
                desk.keepDoorOpen();
            }

            public void closeDoorOnStart() {
                desk.closeDoorOnStart();
            }
        };
    }

    /** Manhunt's checks for a start with the lobby as it stands, under Manhunt's own heading. */
    public Checklist checks() {
        Preflight.Situation s = desk.situation();
        Checklist checks = Checklist.titled(HEADING);
        if (s.huntRunning()) {
            return checks;   // the lobby's own "a run is under way" says it
        }
        checks = checks.check(Checklist.Check.of("manhunt-two", "Two players or more", s.present() >= 2)
                .because(s.present() >= 2 ? s.present() + " here." : "Only " + s.present()
                        + " here — a hunt needs somebody to run and somebody to chase.").in(HEADING));
        if (s.present() < 2) {
            return checks;
        }
        boolean runners = s.runnersPresent() > 0;
        boolean hunters = s.runnersPresent() < s.present();
        checks = checks.check(fixed(Checklist.Check.of("manhunt-runner", "Somebody runs", runners)
                .because(runners ? s.runnersPresent() + " running." : "Nobody is on the Runner side."),
                "Pick a Runner at random", player -> desk.randomRunner()));
        checks = checks.check(fixed(Checklist.Check.of("manhunt-hunter", "Somebody hunts", hunters)
                .because(hunters ? (s.present() - s.runnersPresent()) + " hunting." : "Everybody here is a Runner."),
                "Balance the sides", player -> desk.balance()));
        checks = checks.check(fixed(Checklist.Check.warning("manhunt-away", "Every Runner is here", s.runnersAway().isEmpty())
                .because(s.runnersAway().isEmpty() ? "Nobody on the Runner side is missing."
                        : "On the Runner side but not here: " + String.join(", ", s.runnersAway())
                        + " — they will not be in this hunt."), "Open the sides", openSides));
        boolean closes = s.closesWhitelist() && !s.whitelistClosed();
        checks = checks.check(fixed(Checklist.Check.warning("manhunt-door", "The door stays open", !closes)
                .because(closes ? "The server will close to everybody not here once the hunt starts."
                        : "Nobody is shut out by the start."), "Keep it open", player -> desk.keepDoorOpen()));
        boolean even = !runners || !hunters || (s.runnersExpected() >= 0.2 && s.runnersExpected() <= 0.8);
        checks = checks.check(fixed(Checklist.Check.warning("manhunt-even", "Even sides", even)
                .because("By the ratings the Runners have a " + Math.round(s.runnersExpected() * 100) + "% chance."),
                "Balance the sides", player -> desk.balance()));
        return checks;
    }

    /** The preset and the door, for the lobby's setup assistant. */
    public List<SpeedrunMode.SetupQuestion> questions() {
        List<SpeedrunMode.SetupAnswer> presets = List.of(
                new SpeedrunMode.SetupAnswer(Material.IRON_SWORD, "Classic", "One life, the real thing. Recommended.",
                        false, () -> Preset.CLASSIC.applyTo(settings)),
                new SpeedrunMode.SetupAnswer(Material.CAKE, "Casual",
                        "Two lives, a team compass, a long head start.", false, () -> Preset.CASUAL.applyTo(settings)),
                new SpeedrunMode.SetupAnswer(Material.BLAZE_POWDER, "Sweaty",
                        "No head start, fists only, a wait after dying.", false, () -> Preset.SWEATY.applyTo(settings)));
        boolean closes = settings.current().closeWhitelistOnStart();
        List<SpeedrunMode.SetupAnswer> door = List.of(
                new SpeedrunMode.SetupAnswer(Material.OAK_DOOR, "Keep the server open",
                        "Anybody can join mid-hunt and watch. Recommended.", !closes, desk::keepDoorOpen),
                new SpeedrunMode.SetupAnswer(Material.IRON_DOOR, "Close it when a hunt starts",
                        "Only who is here can join until it ends.", closes, desk::closeDoorOnStart));
        return List.of(new SpeedrunMode.SetupQuestion("Which kind of hunt?", presets),
                new SpeedrunMode.SetupQuestion("What does a start do to the door?", door));
    }

    /** Under Manhunt's heading, fixed by {@code what} — for somebody with /manhunt's own admin node. */
    private static Checklist.Check fixed(Checklist.Check check, String label, Consumer<Player> what) {
        return check.in(HEADING).fixedBy(label, what, PermissionNodes.ADMIN);
    }
}
