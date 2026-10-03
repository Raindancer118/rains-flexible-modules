package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The one goal page, for every game the lobby plays: the goals worth one click — from the full run
 * down to a quick sprint — any other advancement one more click away, or no goal at all.
 */
public final class SpeedrunGoalMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;

    public SpeedrunGoalMenu(SpeedrunLobby lobby, Player viewer, Menu parent) {
        super(viewer, SpeedrunScreens.brandOf(lobby), parent);
        this.lobby = lobby;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>What ends a run?");
    }

    @Override
    public String breadcrumb() {
        return "Goal";
    }

    @Override
    protected void render() {
        SpeedrunSettings config = lobby.config();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.TARGET, "<gold>Goal: " + SpeedrunScreens.text(
                        config.hasAdvancementGoal() ? SpeedrunAdvancementChooser.friendlyName(config.advancementKey()) : "none"),
                "<gray>The first to earn it ends the run.", "<gray>Read when a run starts."));
        int column = 1;
        for (SpeedrunGoals.Goal goal : SpeedrunGoals.all()) {
            boolean current = goal.key().equals(config.advancementKey());
            Material icon = Material.matchMaterial(goal.icon());
            band(MenuLayout.WHO, column, Icons.of(current ? Material.LIME_DYE : icon == null ? Material.PAPER : icon,
                            (current ? "<green>" : "<white>") + goal.label(), "<gray>" + goal.length(),
                            current ? "<dark_gray>This is the goal now." : "<dark_gray>Click to race for it."),
                    click -> pick(goal.key()));
            column = Math.min(7, column + 1);
        }
        band(MenuLayout.RULES, 3, Icons.of(Material.KNOWLEDGE_BOOK, "<white>Any other advancement",
                        "<gray>Every advancement on this server."),
                click -> new SpeedrunAdvancementChooser(lobby, lobby.toolkit().map(SpeedrunToolkit::messages).orElse(null),
                        brand(), viewer, this).open());
        band(MenuLayout.RULES, 5, Icons.of(Material.BARRIER, "<white>No goal",
                        "<gray>Only a death, or the game itself,", "<gray>ends the run."),
                click -> pick(""));
    }

    private void pick(String key) {
        lobby.settings().set("advancement-key", key);
        lobby.toolkit().map(SpeedrunToolkit::messages).ifPresent(messages -> messages.send(viewer,
                key.isEmpty() ? "speedrun.goal.cleared" : "speedrun.goal.set",
                "advancement", key.isEmpty() ? "" : SpeedrunAdvancementChooser.friendlyName(key)));
        refresh();
    }
}
