package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.Brand;
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
        band(MenuLayout.WHO, 7, Icons.of(mode.icon(), "<white>" + mode.label(), lore),
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
                        "<white>Game: " + label, lore),
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
                Icons.of(Material.WRITABLE_BOOK, "<white>Goal: " + goalLabel(config), advancementLore(config)),
                click -> new SpeedrunAdvancementChooser(lobby, messages, brand(), viewer, this).open());
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
        band(MenuLayout.RULES, 4,
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
        lore.add("<gray>" + formatted(session.elapsed()));
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>" + label, lore));
    }

    private void renderFinished() {
        SpeedrunSession session = lobby.session().orElse(null);
        if (session == null) {
            return;
        }
        SpeedrunOutcome outcome = session.outcome().orElse(null);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Ended by: " + (outcome == null ? "?" : outcome.reason()));
        lore.add("<gray>Time: " + formatted(session.elapsed()));
        lore.add("");
        lore.add("<dark_gray>Resets once everybody here has left.");
        band(MenuLayout.WHO, 4, Icons.of(Material.NETHER_STAR, "<white>Finished!", lore));
    }

    private static String formatted(java.time.Duration elapsed) {
        long seconds = elapsed.getSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
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
        return List.of("<gray>" + config.advancementKey(), "", "<gray>Click to change it.");
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
