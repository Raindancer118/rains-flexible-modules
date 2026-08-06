package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.MannequinSimService;
import de.raindancer.modules.hungergames.store.GameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * A rehearsal round, run with mannequins that are real tributes on real, auto-created test teams — so
 * eliminating one runs the same elimination and winner flow a real death would.
 *
 * <h2>Why "eliminate one" and "remove every mannequin" are both confirmed</h2>
 * Neither is one of the module's four listed irreversible public actions on its own terms — a mannequin is
 * not a person who turned up for the evening — but both call the exact same {@code GameSession} methods a
 * real elimination and a real whitelist removal would, on a page an admin may have open while a real round
 * is also running elsewhere on the server. A misclick that eliminates the wrong roster costs a rehearsal at
 * worst; it should still cost a click to take back rather than none.
 */
public final class SimulationMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MannequinSimService simulation;
    private final GameSession session;

    public SimulationMenu(Player viewer, Brand brand, Menu parent, MannequinSimService simulation,
                          GameSession session) {
        super(viewer, brand, parent, 4);
        this.simulation = simulation;
        this.session = session;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gold>Test Simulation");
    }

    @Override
    public String breadcrumb() {
        return "Simulation";
    }

    @Override
    protected void render() {
        GamePhase phase = session.phase();
        set(4, Icons.of(Material.ARMOR_STAND, "<gold>Mannequin simulation",
                "<gray>Mannequins: " + simulation.mannequinCount() + " (alive " + simulation.aliveCount()
                        + ")",
                "<gray>Test teams: " + simulation.teamCount(),
                "<gray>Phase: " + phase,
                "",
                "<dark_gray>Spawn before start-up, then /startup and /start,",
                "<dark_gray>then eliminate them here or kill them for real."));

        boolean canSpawn = simulation.canSpawn();
        spawnButton(10, 1, canSpawn);
        spawnButton(11, 5, canSpawn);
        spawnButton(12, 10, canSpawn);
        if (!canSpawn) {
            set(13, Icons.of(Material.BARRIER, "<red>Spawning locked",
                    "<gray>Only possible before the start-up sequence."));
        }

        boolean canEliminate = phase == GamePhase.RUNNING && simulation.aliveCount() > 0;
        var eliminate = Icons.of(Material.IRON_SWORD, "<red>Eliminate one mannequin",
                canEliminate
                        ? "<gray>Runs the real elimination and winner check."
                        : "<gray>Only possible while RUNNING, with a living mannequin.");
        set(15, canEliminate ? eliminate : Icons.locked(eliminate, "Nothing eligible right now"),
                click -> {
                    if (!canEliminate) {
                        return;
                    }
                    new ConfirmScreen(viewer, brand(), this, "<red>Eliminate one mannequin?",
                            List.of("<gray>Runs the same elimination and winner flow a real death would.",
                                    "<gray>Picks whichever mannequin is still alive."),
                            () -> {
                                simulation.eliminateOne(viewer);
                                refresh();
                            }).open();
                });

        boolean anyMannequins = simulation.mannequinCount() > 0;
        var clear = Icons.of(Material.LAVA_BUCKET, "<red>Remove every mannequin",
                "<gray>Removes the entities, the roster entries and the test teams.");
        set(16, anyMannequins ? clear : Icons.locked(clear, "There is nothing to remove"),
                click -> {
                    if (!anyMannequins) {
                        return;
                    }
                    new ConfirmScreen(viewer, brand(), this, "<red>Remove every mannequin?",
                            List.of("<gray>Every mannequin tribute and test team is deleted.",
                                    "<gray>Real tributes and real teams are untouched."),
                            () -> {
                                simulation.clear();
                                refresh();
                            }).open();
                });
    }

    private void spawnButton(int slot, int amount, boolean canSpawn) {
        var button = Icons.of(Material.PLAYER_HEAD, "<green>Spawn +" + amount,
                amount + " mannequin tribute(s), on a test team.");
        set(slot, canSpawn ? button : Icons.locked(button, "Only before the start-up sequence"),
                click -> {
                    if (!canSpawn) {
                        return;
                    }
                    simulation.spawn(viewer, amount);
                    refresh();
                });
    }

    @Override
    public String describe() {
        return "a rehearsal round through the real elimination and winner flow";
    }
}
