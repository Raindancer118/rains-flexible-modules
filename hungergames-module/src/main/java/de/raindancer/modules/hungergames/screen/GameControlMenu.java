package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.MannequinSimService;
import de.raindancer.modules.hungergames.service.MonsterWaveService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * The phase progression a round moves through: initialise, start-up, start, end, and — for an admin — reset
 * for a rematch.
 *
 * <h2>Why "start the round" is the one button behind {@code danger()}, and "end the round" is not</h2>
 * Both are on the module's list of four irreversible public actions, but a screen has exactly one danger
 * slot. Starting releases everybody from their platforms at once — the moment with no way back at all, taken
 * cold rather than from whatever a gamemaster was just watching happen. Ending is guarded the same way, by a
 * plain button that also opens {@link ConfirmScreen}, just not in the chrome's loudest slot; a page is not
 * less safe for putting the second-worst outcome one row up.
 *
 * <h2>Why {@code /init}'s player count is Core's {@link AmountChooser}</h2>
 * The source engine asked for the count in chat, with no bounds shown until the server refused it.
 * {@link AmountChooser} shows {@link GameControlService#MIN_PLAYERS} and {@link GameControlService#MAX_PLAYERS}
 * as the slider's own ends, so a gamemaster cannot type a number the button was never going to accept.
 */
public final class GameControlMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;
    private final ChatPrompts prompts;
    private final de.raindancer.modules.hungergames.store.TributeRoster roster;
    private final GameControlService gameControl;
    private final PreflightCheckService preflight;
    private final Supplier<List<BorderPhaseConfig>> borderPhases;
    private final MannequinSimService simulation;
    private final MonsterWaveService monsterWaves;
    private final Supplier<Duration> elapsedNow;

    public GameControlMenu(Player viewer, Brand brand, Menu parent, GameSession session,
                           GameControlService gameControl, PreflightCheckService preflight,
                           Supplier<List<BorderPhaseConfig>> borderPhases, MannequinSimService simulation,
                           MonsterWaveService monsterWaves,
                     de.raindancer.modules.hungergames.store.TributeRoster roster, ChatPrompts prompts,
                     Supplier<Duration> elapsedNow) {
        super(viewer, brand, parent, 5);
        this.session = session;
        this.gameControl = gameControl;
        this.preflight = preflight;
        this.borderPhases = borderPhases;
        this.simulation = simulation;
        this.monsterWaves = monsterWaves;
            this.roster = roster;
            this.prompts = prompts;
        this.elapsedNow = elapsedNow;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gold>Game Control");
    }

    @Override
    public String breadcrumb() {
        return "Game Control";
    }

    @Override
    protected void render() {
        GamePhase phase = session.phase();
        boolean admin = PermissionNodes.isAdmin(viewer);

        set(4, Icons.of(Material.NETHER_STAR, "<gold>Round status",
                "<gray>Phase: " + phase,
                "<gray>Tributes: " + session.participants().aliveCount() + " alive / "
                        + session.participants().all().size() + " registered"));

        action(19, Material.LIGHTNING_ROD, "Initialise the arena",
                "Builds the arena at your position.", "Phase: NOT_INITIALIZED/FINISHED -> LOBBY",
                gameControl.canInit(), this::askPlayerCount, phase);

        action(20, Material.REDSTONE_LAMP, "Start-up sequence",
                "Tributes into the tubes, then up to their platforms.", "Phase: LOBBY -> STARTUP -> READY",
                gameControl.canStartup(), () -> {
                    gameControl.startup(viewer.getUniqueId());
                    // Closed rather than refreshed: tributes are about to be moved through the tubes and
                    // onto their platforms, and a menu still open over that is a menu that looks stuck.
                    viewer.closeInventory();
                }, phase);

        boolean canStart = gameControl.canStart(viewer.getUniqueId());
        var startButton = Icons.of(canStart ? Material.LIME_CONCRETE : Material.GRAY_DYE,
                (canStart ? "<green>" : "<dark_gray>") + "Start the round",
                canStart ? "<gray>Runs the countdown, then releases every tribute." + "\n"
                        + "<gray>Phase: READY -> RUNNING"
                        : "<gray>Not possible in phase " + phase + ".");
        danger(canStart ? startButton : Icons.locked(startButton, "Not possible right now"), click -> {
            if (!canStart) {
                return;
            }
            new ConfirmScreen(viewer, brand(), this, "<green>Start the round?",
                    List.of("<gray>Every tribute is released from their platform at once.",
                            "<gray>They move, take loot and find each other — there is no putting that "
                                    + "back."),
                    () -> {
                        gameControl.start(viewer.getUniqueId());
                        // Closed, not refreshed: every tribute is released from their platform the moment
                        // this runs, and a gamemaster watching that happen should not be looking at a menu.
                        viewer.closeInventory();
                    }).open();
        });

        action(23, Material.RED_CONCRETE, "End the round",
                "Scores the round now, as if time had run out.", "Phase: RUNNING -> FINISHED",
                gameControl.canEndRound(), () -> new ConfirmScreen(viewer, brand(), this,
                        "<red>End the round now?",
                        List.of("<gray>Whatever is happening is over, in front of everybody.",
                                "<gray>A winner is decided the same way a time-out would decide one."),
                        () -> {
                            gameControl.endRound();
                            viewer.closeInventory();
                        }).open(), phase);

        if (admin) {
            action(24, Material.TNT, "Reset for a rematch", "Keeps tributes and teams registered.",
                    "Eliminations, kills and the winner are discarded.",
                    phase != GamePhase.NOT_INITIALIZED, () -> new ConfirmScreen(viewer, brand(), this,
                            "<red>Reset for the next round?", List.of(
                                    "<gray>Eliminations, kills and the winner are discarded.",
                                    "<gray>A round currently RUNNING is abandoned, not finished.",
                                    "<gray>Tributes and teams stay registered."),
                            () -> {
                                gameControl.prepareNextRound();
                                viewer.closeInventory();
                            }).open(), phase);
        }

        set(29, Icons.of(Material.OBSERVER, "<aqua>Preflight check",
                        "<gray>Whether the round can start right now."),
                click -> new PreflightMenu(viewer, brand(), this, session, preflight, borderPhases,
                        gameControl).open());

        set(31, Icons.of(Material.PLAYER_HEAD, "<aqua>Tributes",
                        "<gray>" + session.participants().aliveCount() + " alive / "
                                + session.participants().all().size() + " registered"),
                click -> new TributesMenu(viewer, brand(), this, session, prompts, roster).open());

        set(32, Icons.of(Material.ARMOR_STAND, "<aqua>Test simulation",
                        "<gray>" + simulation.mannequinCount() + " mannequin(s) active"),
                click -> new SimulationMenu(viewer, brand(), this, simulation, session).open());

        set(33, Icons.of(Material.ZOMBIE_HEAD, "<aqua>Monster waves",
                        "<gray>" + monsterWaves.activeSeries() + " running series"),
                click -> new MonsterWaveMenu(viewer, brand(), this, monsterWaves, elapsedNow).open());
    }

    private void askPlayerCount() {
        new AmountChooser(viewer, brand(), this, "How many tributes?", GameControlService.MIN_PLAYERS,
                GameControlService.MAX_PLAYERS, GameControlService.MIN_PLAYERS,
                count -> {
                    gameControl.init(viewer.getUniqueId(), count);
                    // Closed rather than reopened: the arena is about to be built at the gamemaster's own
                    // position, and a menu still on their screen is a menu between them and watching it.
                    viewer.closeInventory();
                }).open();
    }

    /** One phase-gated action button: greyed with the current phase named when it may not be pressed. */
    private void action(int slot, Material icon, String label, String line1, String line2,
                        boolean available, Runnable onClick, GamePhase phase) {
        var button = Icons.of(available ? icon : Material.GRAY_DYE,
                (available ? "<yellow>" : "<dark_gray>") + label,
                "<gray>" + line1, "<gray>" + line2,
                available ? "" : "<red>Not possible in phase " + phase + ".");
        if (available) {
            set(slot, button, click -> onClick.run());
        } else {
            set(slot, Icons.locked(button, "Not possible in phase " + phase));
        }
    }

    @Override
    public String describe() {
        return "the round's phase progression: init, start-up, start, end, and reset for a rematch";
    }
}
