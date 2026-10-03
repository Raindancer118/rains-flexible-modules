package de.raindancer.modules.speedrun.manhunt.screen;

import de.raindancer.modules.speedrun.manhunt.ManhuntServices;

import java.util.ArrayList;
import java.util.List;

/**
 * What Manhunt's own page offers, to whom, and what each click does — decided here, without a server,
 * and drawn by {@link ManhuntHubMenu}. This is the one part of the lobby's menu that is Manhunt's
 * alone: the sides, the compasses, a player's own side and switches. Starting, resuming, the goal,
 * the pre-flight check, stats, leaderboards and history are the lobby's own pages, the same for every
 * game. Every action is the same {@code /manhunt} word a command line would type, so the page and the
 * chat can never disagree about what a button does — and the command asks for its node at the click.
 */
public final class HubModel {

    /** The rows a button can sit in: the three bands, then the toolbar. */
    public static final int WHO = 1;
    public static final int RULES = 2;
    public static final int LAND = 3;
    public static final int TOOLBAR = 4;

    public enum Side { RUNNER, HUNTER, NONE }

    /** Everything the page needs to know about the viewer and the lobby. */
    public record View(boolean admin, boolean huntOn, boolean switchingMidHunt, boolean runnerSelfJoin, Side mySide,
                       int runners, int hunters, boolean trailOn, boolean titlesOn) {
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
            buttons.add(run("give", WHO, 6, "COMPASS", "<yellow>Hand out lost compasses",
                    List.of("<gray>Everybody in the hunt gets", "<gray>whatever compass they are missing."),
                    "manhunt give all", view.huntOn() ? null : "Only during a hunt"));
            buttons.add(new Button("settings", RULES, 4, "REPEATER", "<white>Manhunt settings",
                    List.of("<gray>The compass, the sides, the door,", "<gray>variants, ratings."),
                    "speedrun/manhunt", null, true, null));
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
        }
        buttons.add(run("trail", TOOLBAR, 3, view.trailOn() ? "GLOWSTONE_DUST" : "GUNPOWDER", "<gold>Particle trail",
                List.of(view.trailOn() ? "<green>On" : "<red>Off", "<dark_gray>Click to switch."), "manhunt trail", null));
        buttons.add(run("announcements", TOOLBAR, 5, view.titlesOn() ? "BELL" : "NOTE_BLOCK",
                "<gold>Split titles", List.of(view.titlesOn() ? "<green>On" : "<red>Off",
                        "<gray>A title on every split, for you.", "<dark_gray>Click to switch."),
                "manhunt announcements", null));
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

    /** A click on the sides page: left makes a Runner, right a Hunter, shift takes them off their side. */
    public static String sideClick(String name, boolean right, boolean shift) {
        if (shift) {
            return "manhunt unassign " + name;
        }
        return "manhunt assign " + name + (right ? " hunter" : " runner");
    }
}
