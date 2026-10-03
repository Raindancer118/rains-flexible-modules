package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.setup.Goals;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Three questions to a good hunt, for a server that has never run one: how it plays (a preset), what
 * happens to the server's door, and what the Runners race for. Each answer is the same
 * {@code /manhunt} word a command line would type; the last page leads to the pre-flight check.
 */
public final class SetupWizardMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;
    private HubModel.WizardStep step = HubModel.WizardStep.PRESET;

    public SetupWizardMenu(Pages pages, Player viewer, Menu parent) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Manhunt setup — " + (step.ordinal() + 1) + " of 4");
    }

    @Override
    public String breadcrumb() {
        return "Setup";
    }

    @Override
    protected void render() {
        switch (step) {
            case PRESET -> {
                cell(0, 4, Icons.of(Material.NAME_TAG, "<gold>How should a hunt play?",
                        "<gray>Everything stays changeable in the settings."), null);
                band(2, 2, Icons.of(Material.COMPASS, "<white>Classic",
                        "<gray>One life, a compass that follows", "<gray>through portals. The real thing."),
                        click -> answer("manhunt setup classic"));
                band(2, 4, Icons.of(Material.CAKE, "<green>Casual",
                        "<gray>Two lives, a team compass, a long", "<gray>head start, glowing Runners."),
                        click -> answer("manhunt setup casual"));
                band(2, 6, Icons.of(Material.NETHERITE_SWORD, "<red>Sweaty",
                        "<gray>No head start, fists only between", "<gray>Hunters, a wait after dying."),
                        click -> answer("manhunt setup sweaty"));
            }
            case DOOR -> {
                cell(0, 4, Icons.of(Material.IRON_DOOR, "<gold>Close the server during a hunt?",
                        "<gray>Nobody new gets in once it starts;", "<gray>it opens again at the end."), null);
                band(2, 3, Icons.of(Material.OAK_DOOR, "<green>Keep it open", "<gray>Anybody can join any time."),
                        click -> answer("manhunt door keep-open"));
                band(2, 5, Icons.of(Material.IRON_DOOR, "<yellow>Close it during hunts",
                        "<gray>Whoever is online is let in;", "<gray>so is every VIP."),
                        click -> answer("manhunt door close-on-start"));
            }
            case GOAL -> {
                cell(0, 4, Icons.of(Material.TARGET, "<gold>What do the Runners race for?",
                        "<gray>Or nothing: then the Hunters", "<gray>have to catch them all."), null);
                int column = 1;
                for (Goals.Goal goal : Goals.all()) {
                    band(2, column++, Icons.of(ManhuntHubMenu.material(goal.icon()), "<gold>" + goal.label(),
                            "<gray>" + goal.length() + "."), click -> answer("manhunt goal set " + goal.key()));
                }
                band(3, 4, Icons.of(Material.BARRIER, "<gray>No goal", "<gray>Catch them all, or reset."),
                        click -> answer("manhunt goal remove"));
            }
            case DONE -> {
                cell(0, 4, Icons.of(Material.LIME_CONCRETE, "<green>Manhunt is set up",
                        "<gray>Everything is on the hub: /manhunt."), null);
                band(2, 3, Icons.of(Material.WRITABLE_BOOK, "<gold>Pre-flight check",
                        "<gray>What the lobby still needs,", "<gray>each with its fix."),
                        click -> pages.open(viewer, ManhuntServices.Page.PREFLIGHT, this));
                band(2, 5, Icons.of(Material.TARGET, "<white>The hub", "<gray>Everything else."),
                        click -> pages.open(viewer, ManhuntServices.Page.HUB, null));
            }
        }
        if (step != HubModel.WizardStep.PRESET) {
            toolbar(1, Icons.of(Material.ARROW, "<gray>Back a step"), click -> {
                step = step.previous();
                reopen();
            });
        }
        if (step != HubModel.WizardStep.DONE) {
            toolbar(7, Icons.of(Material.SPECTRAL_ARROW, "<gray>Skip this one"), click -> next());
        }
    }

    private void answer(String command) {
        pages.run(viewer, command);
        next();
    }

    private void next() {
        step = step.next();
        if (step == HubModel.WizardStep.DONE) {
            pages.services().setup().markDone();
        }
        reopen();
    }
}
