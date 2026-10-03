package de.raindancer.modules.manhunt.screen;

import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.setup.Preflight;

import java.util.ArrayList;
import java.util.List;

/**
 * What the hub offers, to whom, and what each click does — decided here, without a server, and
 * drawn by {@link ManhuntHubMenu}. Every action is the same {@code /manhunt} word a command line
 * would type, so the page and the chat can never disagree about what a button does.
 */
public final class HubModel {

    /** The rows a button can sit in: the three bands, then the toolbar. */
    public static final int WHO = 1;
    public static final int RULES = 2;
    public static final int LAND = 3;
    public static final int TOOLBAR = 4;

    public enum Side { RUNNER, HUNTER, NONE }

    /**
     * Everything the hub needs to know about the viewer and the lobby.
     *
     * @param blockers how many pre-flight checks would stop a start
     * @param goalLabel the lobby's goal as players say it
     */
    public record View(boolean admin, boolean huntOn, boolean switchingMidHunt, boolean runnerSelfJoin, Side mySide,
                       int runners, int hunters, int blockers, int warnings, String goalLabel,
                       boolean trailOn, boolean sidebarOn, boolean announcementsOn) {
    }

    /**
     * One button: where it sits, what it shows, and either a {@code /manhunt} command to run, a page
     * to open, or the settings at {@code target}.
     *
     * @param lockedBecause why it cannot be used right now, or null
     */
    public record Button(String id, int band, int column, String icon, String name, List<String> lore,
                         String target, ManhuntServices.Page page, boolean settings, String lockedBecause) {
    }

    /** One click: a command or a page. */
    public record Click(String command, ManhuntServices.Page page) {
    }

    private HubModel() {
    }

    public static List<Button> buttons(View view) {
        List<Button> buttons = new ArrayList<>();
        if (view.admin()) {
            String fixedSides = view.huntOn() ? "Sides do not move during a hunt" : null;
            buttons.add(page("sides", WHO, 2, "LIME_BANNER", "<green>Sides",
                    List.of("<gray>" + view.runners() + " running, " + view.hunters() + " hunting.",
                            "<dark_gray>Click players onto a side."), ManhuntServices.Page.SIDES, null));
            buttons.add(run("balance", WHO, 3, "COMPARATOR", "<yellow>Balance the sides",
                    List.of("<gray>Splits everybody here by rating,", "<gray>as close to even as it gets."),
                    "manhunt balance", fixedSides));
            buttons.add(run("random", WHO, 5, "ENDER_PEARL", "<aqua>A random Runner",
                    List.of("<gray>One Runner drawn by lot,", "<gray>everybody else hunts."), "manhunt random 1",
                    fixedSides));
            buttons.add(page("preflight", WHO, 6, "WRITABLE_BOOK", "<gold>Pre-flight check",
                    List.of(view.blockers() > 0 ? "<red>" + view.blockers() + " thing(s) stop the start."
                                    : "<green>Nothing stops the start.",
                            "<gray>" + view.warnings() + " thing(s) worth knowing."), ManhuntServices.Page.PREFLIGHT, null));

            boolean blocked = view.blockers() > 0;
            buttons.add(new Button("start", RULES, 2, "LIME_CONCRETE", "<green>Start the hunt",
                    List.of(blocked ? "<red>Fix the pre-flight first — click to see it."
                            : "<gray>The lobby counts down, in Manhunt."),
                    blocked ? null : "manhunt start", blocked ? ManhuntServices.Page.PREFLIGHT : null, false,
                    view.huntOn() ? "A hunt is already on" : null));
            buttons.add(page("resume", RULES, 3, "CLOCK", "<aqua>Resume a hunt",
                    List.of("<gray>After a restart: where everybody", "<gray>stands, nobody moved or cleared."),
                    ManhuntServices.Page.RESUME, view.huntOn() ? "A hunt is already on" : null));
            buttons.add(page("goal", RULES, 5, "TARGET", "<gold>Goal",
                    List.of("<gray>Now: <white>" + view.goalLabel(), "<dark_gray>Click to change it."),
                    ManhuntServices.Page.GOAL, null));
            buttons.add(run("give", RULES, 6, "COMPASS", "<yellow>Hand out lost compasses",
                    List.of("<gray>Everybody in the hunt gets", "<gray>whatever compass they are missing."),
                    "manhunt give all", view.huntOn() ? null : "Only during a hunt"));

            buttons.add(new Button("settings", LAND, 2, "REPEATER", "<white>Settings",
                    List.of("<gray>The compass, the sides, variants,", "<gray>what everybody sees, stats."),
                    "manhunt", null, true, null));
            buttons.add(page("leaderboard", LAND, 3, "GOLDEN_HELMET", "<gold>Leaderboard",
                    List.of("<gray>Ratings, wins, catches, survival."), ManhuntServices.Page.LEADERBOARD, null));
            buttons.add(page("history", LAND, 5, "BOOK", "<white>Past hunts",
                    List.of("<gray>Every hunt, moment by moment."), ManhuntServices.Page.HISTORY, null));
            buttons.add(page("setup", LAND, 6, "NAME_TAG", "<light_purple>Setup wizard",
                    List.of("<gray>Three questions to a good hunt."), ManhuntServices.Page.SETUP, null));
        } else {
            String fixed = view.huntOn() && !view.switchingMidHunt() ? "Sides are fixed until the hunt ends" : null;
            buttons.add(run("run", WHO, 3, "FEATHER", "<green>Run",
                    List.of(view.mySide() == Side.RUNNER ? "<yellow>You are running." : "<gray>Race the goal with the Hunters behind you."),
                    "manhunt join runner", fixed != null ? fixed
                            : !view.runnerSelfJoin() ? "An admin picks the Runners on this server" : null));
            buttons.add(run("hunt", WHO, 4, "IRON_SWORD", "<red>Hunt",
                    List.of(view.mySide() == Side.HUNTER ? "<yellow>You are hunting." : "<gray>Chase whoever is running."),
                    "manhunt join hunter", fixed));
            buttons.add(run("leave", WHO, 5, "OAK_DOOR", "<gray>Leave",
                    List.of(view.huntOn() ? "<gray>Out of the hunt being played." : "<gray>Off the Runners — you hunt."),
                    "manhunt leave", null));
            buttons.add(page("leaderboard", RULES, 3, "GOLDEN_HELMET", "<gold>Leaderboard",
                    List.of("<gray>Ratings, wins, catches, survival."), ManhuntServices.Page.LEADERBOARD, null));
            buttons.add(page("history", RULES, 5, "BOOK", "<white>Past hunts",
                    List.of("<gray>Every hunt, moment by moment."), ManhuntServices.Page.HISTORY, null));
        }
        buttons.add(run("stats", TOOLBAR, 1, "PLAYER_HEAD", "<aqua>Your stats",
                List.of("<gray>Rating, wins, catches and more."), "manhunt stats", null));
        buttons.add(run("trail", TOOLBAR, 3, view.trailOn() ? "GLOWSTONE_DUST" : "GUNPOWDER", "<gold>Particle trail",
                List.of(view.trailOn() ? "<green>On" : "<red>Off", "<dark_gray>Click to switch."), "manhunt trail", null));
        buttons.add(run("sidebar", TOOLBAR, 4, view.sidebarOn() ? "OAK_SIGN" : "BIRCH_SIGN", "<gold>Sidebar",
                List.of(view.sidebarOn() ? "<green>Shown" : "<red>Hidden", "<dark_gray>Click to switch."), "manhunt hud", null));
        buttons.add(run("announcements", TOOLBAR, 5, view.announcementsOn() ? "BELL" : "NOTE_BLOCK",
                "<gold>Milestone titles", List.of(view.announcementsOn() ? "<green>On" : "<red>Off",
                        "<dark_gray>Click to switch."), "manhunt announcements", null));
        buttons.add(run("here", TOOLBAR, 7, "RECOVERY_COMPASS", "<aqua>Share where you are",
                List.of("<gray>Everybody gets your coordinates", "<gray>as a button that guides them."), "manhunt here",
                null));
        return List.copyOf(buttons);
    }

    private static Button run(String id, int band, int column, String icon, String name, List<String> lore,
                              String command, String locked) {
        return new Button(id, band, column, icon, name, lore, command, null, false, locked);
    }

    private static Button page(String id, int band, int column, String icon, String name, List<String> lore,
                               ManhuntServices.Page page, String locked) {
        return new Button(id, band, column, icon, name, lore, null, page, false, locked);
    }

    /** The one click that fixes a pre-flight check; null where only waiting helps. */
    public static Click fix(Preflight.Fix fix) {
        return switch (fix) {
            case NONE -> null;
            case PICK_RANDOM_RUNNER -> new Click("manhunt random 1", null);
            case AUTO_BALANCE -> new Click("manhunt balance", null);
            case OPEN_SIDES -> new Click(null, ManhuntServices.Page.SIDES);
            case REMOVE_GOAL -> new Click("manhunt goal remove", null);
            case CHOOSE_GOAL -> new Click(null, ManhuntServices.Page.GOAL);
            case KEEP_DOOR_OPEN -> new Click("manhunt door keep-open", null);
        };
    }

    /** What a click on a player on the sides page does. */
    public static String sideClick(String name, boolean right, boolean shift) {
        if (shift) {
            return "manhunt unassign " + name;
        }
        return "manhunt assign " + name + (right ? " hunter" : " runner");
    }

    /** The setup wizard's pages, in order. */
    public enum WizardStep {
        PRESET, DOOR, GOAL, DONE;

        public WizardStep next() {
            return values()[Math.min(values().length - 1, ordinal() + 1)];
        }

        public WizardStep previous() {
            return values()[Math.max(0, ordinal() - 1)];
        }
    }

    public static List<WizardStep> wizardSteps() {
        return List.of(WizardStep.values());
    }
}
