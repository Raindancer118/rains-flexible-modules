package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The compass's screen: the current end conditions while the lobby is
 * {@link SpeedrunLobbyState#READY}, and a status page otherwise.
 *
 * <h2>Who may change what, and when</h2>
 * The goal and the death policy only while {@link SpeedrunLobbyState#READY} — those are read once,
 * when {@link SpeedrunLobby#start} arms a session's end conditions, so changing them mid-run would
 * silently do nothing to the run already in progress and only confuse whoever clicked. There is
 * nothing to grey for those two: a page with no editable buttons on it is a stronger guarantee than
 * one whose buttons refuse a click.
 *
 * <p>The creeper hazard — {@link #renderHazardDoor()} — is the opposite: both listeners read
 * {@link SpeedrunSettings} fresh on every triggering event, so a change reaches a run already under
 * way immediately. Asked for explicitly, so an admin can turn the hazard down (or up) without waiting
 * for the race to end. Shown on every page regardless of {@link SpeedrunLobbyState} for that reason.
 *
 * <h2>The shape of the page</h2>
 * The race itself, and doors to whatever holds more than one setting — the claim screens' shape. The
 * hazard used to be four percentages laid across two bands, which was most of what this page showed,
 * so the two questions somebody actually opens the compass with were outnumbered by a feature most
 * servers never switch on. Numbers are picked with Core's {@code AmountChooser}, never nudged with a
 * ±pair; see {@link SpeedrunHazardMenu}.
 */
public final class SpeedrunLobbyMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    private final Messages messages;

    public SpeedrunLobbyMenu(SpeedrunLobby lobby, Messages messages, Brand brand, Player viewer,
                             Menu parent) {
        super(viewer, brand, parent);
        this.lobby = lobby;
        this.messages = messages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Speedrun");
    }

    @Override
    public String breadcrumb() {
        return "Speedrun";
    }

    @Override
    protected void render() {
        switch (lobby.state()) {
            case READY -> renderReady();
            case COUNTDOWN -> renderCountdown();
            case RUNNING -> renderInProgress("Running");
            case PAUSED -> renderInProgress("Paused — nobody is here");
            case FINISHED -> renderFinished();
        }
        renderHazardDoor();
        renderModeDoor();
        renderHub();
    }

    /**
     * Everything else, one click away — the hub. Seeds, splits and "am I racing" beside the hazard;
     * stats, leaderboards and history below; and for staff a toolbar that does everything a command
     * would: start (through the pre-flight check), resume, set the clock, the roster, all settings,
     * the setup assistant, and — on the danger slot, behind a confirmation — the reset.
     *
     * <p>A button somebody may not use is shown greyed with the reason rather than left out, so the
     * page is the same page for everybody and says who to ask.
     */
    private void renderHub() {
        SpeedrunToolkit kit = lobby.toolkit().orElse(null);
        SpeedrunActions actions = new SpeedrunActions(lobby);
        SpeedrunLobbyState state = lobby.state();

        band(MenuLayout.RULES, 3, SpeedrunAccess.SEEDS.allows(lobby, viewer), Icons.of(Material.WHEAT_SEEDS,
                        "<white>Seeds", "<gray>" + SpeedrunScreens.text(seedLine()), "<dark_gray>Click to choose."),
                "Staff choose the seed",
                SpeedrunAccess.SEEDS.guard(lobby, viewer, click -> new SpeedrunSeedMenu(lobby, viewer, this).open()));
        if (kit != null && kit.hud() != null) {
            SpeedrunHudMode mode = kit.hud().modeOf(viewer.getUniqueId());
            band(MenuLayout.RULES, 5, Icons.of(Material.CLOCK, "<white>My splits: " + mode.label(),
                            "<gray>Where you see the run's splits.", "<dark_gray>Click for the next place."),
                    click -> {
                        actions.cycleHud(viewer);
                        refresh();
                    });
        }
        boolean spectating = lobby.isSpectator(viewer.getUniqueId());
        band(MenuLayout.RULES, 7, SpeedrunAccess.SPECTATE.allows(lobby, viewer),
                Icons.of(spectating ? Material.ENDER_EYE : Material.LEATHER_BOOTS,
                        spectating ? "<gray>You are not racing" : "<green>You are racing",
                        "<gray>Pressing start sweeps up everybody", "<gray>racing in the lobby world.",
                        "<dark_gray>Click to switch."),
                "Not offered on this server",
                SpeedrunAccess.SPECTATE.guard(lobby, viewer, click -> {
                    lobby.toggleSpectator(viewer.getUniqueId());
                    refresh();
                }));

        if (kit != null && kit.history() != null) {
            band(MenuLayout.LAND, 2, Icons.head(viewer.getUniqueId(), "<white>My stats",
                            "<gray>Personal bests, runs, deaths.", "<dark_gray>Click to open."),
                    click -> new SpeedrunStatsMenu(lobby, viewer, this, viewer.getUniqueId(), viewer.getName()).open());
            band(MenuLayout.LAND, 4, Icons.of(Material.GOLDEN_HELMET, "<gold>Leaderboards",
                            "<gray>Per goal, seed, game and player count.", "<dark_gray>Click to open."),
                    click -> new SpeedrunLeaderboardMenu(lobby, viewer, this, null).open());
            band(MenuLayout.LAND, 6, Icons.of(Material.BOOK, "<white>Past runs",
                            "<gray>" + kit.history().size() + " kept, every split of each.", "<dark_gray>Click to open."),
                    click -> new SpeedrunHistoryMenu(lobby, viewer, this, null, "").open());
        }

        boolean mayStart = SpeedrunAccess.START.allows(lobby, viewer);
        toolbar(1, mayStart && state == SpeedrunLobbyState.READY,
                Icons.of(Material.LIME_CONCRETE, "<green>Start", "<gray>Checks everything first,",
                        "<gray>with a one-click fix for each problem."),
                !mayStart ? "Only staff can start a run here" : "A run is already under way",
                SpeedrunAccess.START.guard(lobby, viewer, click -> new SpeedrunPreflightMenu(lobby, viewer, this).open()));
        toolbar(2, SpeedrunAccess.RESUME.allows(lobby, viewer) && (state == SpeedrunLobbyState.READY || state == SpeedrunLobbyState.FINISHED),
                Icons.of(Material.RECOVERY_COMPASS, "<white>Resume a run", "<gray>Over the world as it stands,",
                        "<gray>at a time you type — after a restart."),
                SpeedrunAccess.RESUME.allows(lobby, viewer) ? "Only from a ready or finished lobby" : "Staff resume runs",
                SpeedrunAccess.RESUME.guard(lobby, viewer, click -> actions.askForTime(viewer, "speedrun.resume.ask",
                        time -> {
                            if (SpeedrunAccess.RESUME.allows(lobby, viewer)) {
                                actions.resume(viewer, time);
                            }
                        })));
        boolean running = state == SpeedrunLobbyState.RUNNING || state == SpeedrunLobbyState.PAUSED;
        toolbar(3, SpeedrunAccess.SET_CLOCK.allows(lobby, viewer) && running, Icons.of(Material.COMPARATOR, "<white>Set the clock",
                        "<gray>Type the time it should read.", "<gray>Kept on the run's record."),
                SpeedrunAccess.SET_CLOCK.allows(lobby, viewer) ? "No run is being played" : "Staff set the clock",
                SpeedrunAccess.SET_CLOCK.guard(lobby, viewer, click -> actions.askForTime(viewer, "speedrun.time.ask",
                        time -> {
                            if (SpeedrunAccess.SET_CLOCK.allows(lobby, viewer)) {
                                actions.setClock(viewer, time);
                            }
                        })));
        toolbar(5, Icons.of(Material.PLAYER_HEAD, "<white>Who is here", "<gray>Racing, not racing, released."),
                click -> new SpeedrunRosterMenu(lobby, viewer, this).open());
        toolbar(6, SpeedrunAccess.SETTINGS.allows(lobby, viewer) && kit != null && kit.navigation() != null && kit.chat() != null,
                Icons.of(Material.COMPARATOR, "<white>All settings", "<gray>Every speedrun setting, explained."),
                "Staff change the settings",
                SpeedrunAccess.SETTINGS.guard(lobby, viewer,
                        click -> new SettingsMenu(viewer, brand(), kit.chat(), kit.navigation(), "speedrun", this).open()));
        toolbar(7, SpeedrunAccess.SETUP.allows(lobby, viewer), Icons.of(Material.WRITABLE_BOOK, lobby.config().setupDone() ? "<white>Setup assistant"
                                : "<gold>Setup assistant", "<gray>One question a page."),
                "Staff set the lobby up",
                SpeedrunAccess.SETUP.guard(lobby, viewer, click -> new SpeedrunSetupMenu(lobby, viewer, this, 0).open()));
        if (SpeedrunAccess.RESET.allows(lobby, viewer)) {
            danger(Icons.of(Material.TNT, "<red>Reset the world", "<gray>Ends any run and remakes all",
                            "<gray>three worlds. Asks first."),
                    SpeedrunAccess.RESET.guard(lobby, viewer, click -> actions.confirmReset(viewer, this)));
        }
    }

    private String seedLine() {
        SpeedrunSettings config = lobby.config();
        return switch (config.seedMode() == null ? SpeedrunSeedMode.RANDOM : config.seedMode()) {
            case RANDOM -> lobby.replayingSeed() ? "This map again, then random" : "A new seed every run";
            case FIXED -> "Always " + (config.seed().isBlank() ? "(not set)" : config.seed());
            case POOL -> "One of " + SpeedrunSeeds.pool(config.seedPool()).size() + " seeds";
        };
    }

    /**
     * The chosen game mode's own page — Manhunt's sides — as one door on the right of the top band.
     *
     * <p>Drawn on every {@link SpeedrunLobbyState}, like {@link #renderHazardDoor()} and for the same
     * reason: somebody opening the compass mid-hunt to see who is still running should not have to
     * end it first. Nothing is drawn for a plain race, or for a mode with no page of its own.
     */
    private void renderModeDoor() {
        Optional<SpeedrunMode.Setup> setup = lobby.mode().flatMap(SpeedrunMode::setup);
        if (setup.isEmpty()) {
            return;
        }
        SpeedrunMode mode = lobby.mode().orElseThrow();
        List<String> lore = new ArrayList<>(mode.description());
        lore.add("<dark_gray>Click to open.");
        band(MenuLayout.WHO, 7, Icons.of(mode.icon(), "<white>" + SpeedrunScreens.text(mode.label()), lore),
                click -> setup.get().open(viewer, this));
    }

    /**
     * Which game this lobby plays, cycled through the plain race and every installed mode — see
     * {@link SpeedrunModes#next}.
     *
     * <p>Only while {@link SpeedrunLobbyState#READY}, for the same reason the goal is: the mode is
     * read once, when {@link SpeedrunLobby#start} builds the run, so switching it mid-race would
     * change nothing about the race and only confuse whoever clicked. On a server with no mode
     * installed the button is not drawn at all — a cycle with one position is not a choice.
     */
    private void renderModeButton() {
        if (SpeedrunModes.offered().isEmpty()) {
            return;
        }
        String current = lobby.config().gameMode();
        Optional<SpeedrunMode> chosen = lobby.mode();
        String label = chosen.map(SpeedrunMode::label)
                .orElse(current.isBlank() ? "Speedrun" : current + " (not installed)");
        List<String> lore = new ArrayList<>(chosen.map(SpeedrunMode::description)
                .orElse(List.of(current.isBlank()
                        ? "<gray>A plain race against the clock."
                        : "<red>That mode's plugin is not installed.")));
        lore.add("");
        lore.add("<gray>Click to cycle.");
        band(MenuLayout.WHO, 1,
                Icons.of(chosen.map(SpeedrunMode::icon).orElse(Material.NETHER_STAR),
                        "<white>Game: " + SpeedrunScreens.text(label), lore),
                click -> {
                    lobby.settings().set("game-mode", SpeedrunModes.next(current));
                    refresh();
                });
    }

    private void renderCountdown() {
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>Starting…",
                "<gray>Everybody is frozen until it begins."));
    }

    private void renderReady() {
        SpeedrunSettings config = lobby.config();
        renderModeButton();
        band(MenuLayout.WHO, 3,
                Icons.of(Material.WRITABLE_BOOK, "<white>Goal: " + SpeedrunScreens.text(goalLabel(config)), advancementLore(config)),
                click -> new SpeedrunGoalMenu(lobby, viewer, this).open());
        // Not drawn at all for a mode with its own rules about dying — Manhunt eliminates a Runner
        // where a race would end. A button that silently does nothing to the game being played is
        // worse than a page that does not offer it; see the class javadoc on why nothing is greyed.
        if (lobby.mode().map(SpeedrunMode::usesDeathPolicy).orElse(true)) {
            band(MenuLayout.WHO, 5,
                    Icons.of(deathIcon(config.deathPolicy()), "<white>Death policy: " + config.deathPolicy(),
                            deathLore(config)),
                    click -> {
                        lobby.settings().cycle("death-policy");
                        refresh();
                    });
        }
    }

    /**
     * The hazard, as one door rather than four percentages spread across two bands — the shape the
     * claim screens use, where the page is the thing itself and anything holding several settings is
     * a button that opens them. See {@link SpeedrunHazardMenu}.
     *
     * <p>Drawn on every page regardless of {@link SpeedrunLobbyState}, which is the one thing about it
     * that has not changed: both creeper listeners read {@link SpeedrunSettings} fresh on every
     * triggering event, so a change reaches a run already under way, and a host turning the hazard
     * down mid-race should not have to wait for the race to end. The goal and the death policy are the
     * opposite — read once, when {@link SpeedrunLobby#start} arms the session — which is why they are
     * only offered while the lobby is {@link SpeedrunLobbyState#READY}.
     */
    private void renderHazardDoor() {
        SpeedrunSettings config = lobby.config();
        boolean on = config.creeperSpawnChanceOnBreakPercent() > 0
                || config.creeperSpawnChanceOnContainerPercent() > 0;
        band(MenuLayout.RULES, 1,
                Icons.of(on ? Material.CREEPER_HEAD : Material.BARRIER,
                        on ? "<gold>Creeper hazard" : "<gray>Creeper hazard",
                        "<gray>Creepers where a racer mines or loots.",
                        on
                                ? "<dark_gray>mining " + config.creeperSpawnChanceOnBreakPercent()
                                        + "%, looting " + config.creeperSpawnChanceOnContainerPercent() + "%"
                                : "<dark_gray>off — a plain race",
                        "<dark_gray>Click to open."),
                click -> new SpeedrunHazardMenu(lobby, brand(), viewer, this).open());
    }

    private void renderInProgress(String label) {
        SpeedrunSession session = lobby.session().orElse(null);
        if (session == null) {
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + session.participants().size() + " racing.");
        lore.add("<gray>" + SpeedrunTimerDisplay.plain(session.elapsed()));
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>" + label, lore));
    }

    private void renderFinished() {
        SpeedrunSession session = lobby.session().orElse(null);
        if (session == null) {
            return;
        }
        SpeedrunOutcome outcome = session.outcome().orElse(null);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Ended by: " + SpeedrunScreens.text(outcome == null ? "?" : SpeedrunLobby.friendlyReason(outcome.reason())));
        lore.add("<gray>Time: " + SpeedrunTimerDisplay.plain(session.elapsed()));
        lore.add("");
        lore.add(lobby.config().restartWhenRunEnds()
                ? "<dark_gray>The world resets for the next run in a moment."
                : "<dark_gray>Resets once everybody here has left.");
        band(MenuLayout.WHO, 4, Icons.of(Material.NETHER_STAR, "<white>Finished!", lore));
        lobby.lastRun().ifPresent(run -> band(MenuLayout.WHO, 6, Icons.of(Material.FILLED_MAP,
                        "<white>This run's summary", "<gray>Every split, death and pause.", "<dark_gray>Click to open."),
                click -> new SpeedrunRunSummaryMenu(lobby, run, viewer, this).open()));
    }

    private static Material deathIcon(SpeedrunDeathPolicy policy) {
        return policy == SpeedrunDeathPolicy.OFF ? Material.TOTEM_OF_UNDYING : Material.SKELETON_SKULL;
    }

    /** What the button's own name says — the whole point being that this is visible without a click. */
    private static String goalLabel(SpeedrunSettings config) {
        return config.hasAdvancementGoal()
                ? SpeedrunAdvancementChooser.friendlyName(config.advancementKey())
                : "<gray>None";
    }

    private static List<String> advancementLore(SpeedrunSettings config) {
        if (!config.hasAdvancementGoal()) {
            return List.of("<gray>None set.", "<gray>Click to pick one.");
        }
        return List.of("<gray>" + SpeedrunScreens.text(config.advancementKey()), "", "<gray>Click to change it.");
    }

    private static List<String> deathLore(SpeedrunDeathPolicy policy) {
        return switch (policy) {
            case OFF -> List.of("<gray>A death does not end the run.", "<gray>Click to cycle.");
            case ANY -> List.of("<gray>The first death ends it for everybody.", "<gray>Click to cycle.");
            case ALL -> List.of("<gray>Ends once every racer has died.", "<gray>Click to cycle.");
        };
    }

    private static List<String> deathLore(SpeedrunSettings config) {
        return deathLore(config.deathPolicy());
    }
}
