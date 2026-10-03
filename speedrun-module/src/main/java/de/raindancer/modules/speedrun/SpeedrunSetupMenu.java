package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The first-run assistant: one question a page, each answer a click that is written at once, the
 * sensible answer marked. Nothing here needs a command, a file or the settings tree; everything it
 * sets can be changed again later from the hub.
 */
public final class SpeedrunSetupMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The questions, in the order they are asked. */
    public enum Step {
        GAME("Which game?"),
        GOAL("What ends a run?"),
        DEATHS("What does a death do?"),
        SEEDS("Which seeds?"),
        AFTER("When a run ends…"),
        STARTING("Who may start a run?"),
        SPLITS("Where are splits shown?"),
        DONE("All set");

        private final String question;

        Step(String question) {
            this.question = question;
        }

        public String question() {
            return question;
        }
    }

    /** The steps this lobby asks — no game question without a mode installed, no death question for a game with its own rules. */
    public static List<Step> steps(SpeedrunLobby lobby) {
        List<Step> steps = new ArrayList<>();
        for (Step step : Step.values()) {
            if (step == Step.GAME && SpeedrunModes.offered().isEmpty()) {
                continue;
            }
            if (step == Step.DEATHS && !lobby.mode().map(SpeedrunMode::usesDeathPolicy).orElse(true)) {
                continue;
            }
            steps.add(step);
        }
        return steps;
    }

    private final SpeedrunLobby lobby;
    private final int at;

    public SpeedrunSetupMenu(SpeedrunLobby lobby, Player viewer, Menu parent, int at) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
        this.at = at;
    }

    private Step step() {
        List<Step> steps = steps(lobby);
        return steps.get(Math.max(0, Math.min(at, steps.size() - 1)));
    }

    @Override
    protected Component title() {
        List<Step> steps = steps(lobby);
        return MINI.deserialize("<dark_gray>Setup " + (Math.min(at, steps.size() - 1) + 1) + "/" + steps.size());
    }

    @Override
    public String breadcrumb() {
        return "Setup";
    }

    @Override
    protected void render() {
        Step step = step();
        SpeedrunSettings config = lobby.config();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.WRITABLE_BOOK, "<gold>" + step.question(),
                "<gray>Click an answer; it is saved at once.", "<gray>Everything can be changed later in the menu."));
        switch (step) {
            case GAME -> {
                choice(1, Material.NETHER_STAR, "A plain race", "<gray>Everybody against the clock.",
                        !config.hasGameMode(), () -> lobby.settings().set("game-mode", ""));
                int column = 3;
                for (SpeedrunMode mode : SpeedrunModes.offered()) {
                    choice(column, mode.icon(), mode.label(), "<gray>Another game in the same lobby.",
                            mode.id().equalsIgnoreCase(config.gameMode()),
                            () -> lobby.settings().set("game-mode", mode.id()));
                    column = Math.min(7, column + 2);
                }
            }
            case GOAL -> {
                choice(2, Material.DRAGON_HEAD, "Kill the dragon", "<gray>The classic. Recommended.",
                        config.isDragonKillGoal(),
                        () -> lobby.settings().set("advancement-key", SpeedrunSettings.DRAGON_KILL_ADVANCEMENT));
                band(MenuLayout.WHO, 4, Icons.of(Material.KNOWLEDGE_BOOK, "<white>Another advancement",
                                "<gray>Pick any advancement from the list."),
                        click -> new SpeedrunAdvancementChooser(lobby,
                                lobby.toolkit().map(SpeedrunToolkit::messages).orElse(null), brand(), viewer,
                                new SpeedrunSetupMenu(lobby, viewer, parent(), at + 1)).open());
                choice(6, Material.BARRIER, "No goal", "<gray>Only a death or the game ends it.",
                        !config.hasAdvancementGoal(), () -> lobby.settings().set("advancement-key", ""));
            }
            case DEATHS -> {
                choice(2, Material.TOTEM_OF_UNDYING, "Nothing", "<gray>Respawn and keep going. Recommended.",
                        config.deathPolicy() == SpeedrunDeathPolicy.OFF,
                        () -> lobby.settings().set("death-policy", "OFF"));
                choice(4, Material.SKELETON_SKULL, "The first death ends it", "<gray>Hardcore rules.",
                        config.deathPolicy() == SpeedrunDeathPolicy.ANY,
                        () -> lobby.settings().set("death-policy", "ANY"));
                choice(6, Material.WITHER_SKELETON_SKULL, "Ends when everybody died", "<gray>Co-op hardcore.",
                        config.deathPolicy() == SpeedrunDeathPolicy.ALL,
                        () -> lobby.settings().set("death-policy", "ALL"));
            }
            case SEEDS -> {
                choice(2, Material.ENDER_EYE, "A new seed every run", "<gray>Random seed. Recommended.",
                        config.seedMode() == SpeedrunSeedMode.RANDOM,
                        () -> lobby.settings().set("seed-mode", SpeedrunSeedMode.RANDOM.name()));
                band(MenuLayout.WHO, 5, Icons.of(Material.FILLED_MAP, "<white>A seed of my choice",
                                "<gray>Type it in chat next."),
                        click -> new SpeedrunActions(lobby).ask(viewer, "speedrun.seed.ask", typed -> {
                            lobby.settings().set("seed", typed);
                            lobby.settings().set("seed-mode", SpeedrunSeedMode.FIXED.name());
                            new SpeedrunSetupMenu(lobby, viewer, parent(), at + 1).open();
                        }));
            }
            case AFTER -> {
                choice(1, Material.CLOCK, "Remake the world after 10 s", "<gray>Straight into the next run. Recommended.",
                        config.restartWhenRunEnds() && config.restartAfterSeconds() == 10, () -> {
                            lobby.settings().set("restart-when-run-ends", "true");
                            lobby.settings().set("restart-after-seconds", "10");
                        });
                choice(3, Material.CLOCK, "…after a minute", "<gray>Time to look around the finish.",
                        config.restartWhenRunEnds() && config.restartAfterSeconds() == 60, () -> {
                            lobby.settings().set("restart-when-run-ends", "true");
                            lobby.settings().set("restart-after-seconds", "60");
                        });
                choice(5, Material.OAK_DOOR, "Once everybody left", "<gray>Or an admin resets it.",
                        !config.restartWhenRunEnds(), () -> lobby.settings().set("restart-when-run-ends", "false"));
            }
            case STARTING -> {
                choice(2, Material.GOLDEN_HELMET, "Only staff", "<gray>The start block goes to staff. Recommended.",
                        config.startBlockStaffOnly(), () -> lobby.settings().set("start-block-staff-only", "true"));
                choice(6, Material.LIME_CONCRETE, "Everybody", "<gray>Anybody in the lobby can start.",
                        !config.startBlockStaffOnly(), () -> lobby.settings().set("start-block-staff-only", "false"));
            }
            case SPLITS -> {
                int column = 1;
                for (SpeedrunHudMode mode : SpeedrunHudMode.values()) {
                    choice(column, mode == SpeedrunHudMode.OFF ? Material.BARRIER : Material.CLOCK, mode.label(),
                            mode == SpeedrunHudMode.SIDEBAR ? "<gray>Recommended. Each player can change theirs."
                                    : "<gray>Each player can change theirs.",
                            config.hudDefaultOrSidebar() == mode,
                            () -> lobby.settings().set("hud-default", mode.name()));
                    column += 2;
                }
            }
            case DONE -> {
                if (!config.setupDone()) {
                    lobby.settings().set("setup-done", "true");
                }
                band(MenuLayout.WHO, 3, Icons.of(Material.LIME_CONCRETE, "<green>Run the pre-flight check",
                                "<gray>Everything a start needs, with one-click fixes."),
                        click -> new SpeedrunPreflightMenu(lobby, viewer, parent()).open());
                band(MenuLayout.WHO, 5, Icons.of(Material.COMPASS, "<white>Back to the menu",
                                "<gray>The setup is saved."),
                        click -> backToWhoeverOpenedThis());
            }
        }
        if (at > 0) {
            toolbar(2, Icons.of(Material.ARROW, "<white>Previous question"),
                    click -> new SpeedrunSetupMenu(lobby, viewer, parent(), at - 1).open());
        }
        if (step != Step.DONE) {
            toolbar(6, Icons.of(Material.SPECTRAL_ARROW, "<white>Keep it as it is", "<gray>Next question."),
                    click -> new SpeedrunSetupMenu(lobby, viewer, parent(), at + 1).open());
        }
    }

    private void choice(int column, Material icon, String name, String detail, boolean current, Runnable pick) {
        band(MenuLayout.WHO, column, Icons.of(current ? Material.LIME_DYE : icon,
                        (current ? "<green>" : "<white>") + name, detail,
                        current ? "<dark_gray>This is how it is now." : "<dark_gray>Click to choose."),
                click -> {
                    pick.run();
                    new SpeedrunSetupMenu(lobby, viewer, parent(), at + 1).open();
                });
    }
}
