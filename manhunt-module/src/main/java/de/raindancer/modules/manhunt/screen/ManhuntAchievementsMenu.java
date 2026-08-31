package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.content.achievement.Achievement;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.service.ManhuntAchievements;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The seven curated Manhunt achievements, one per column — earned or not, with a plain "not yet
 * earned" lock rather than hiding the button. See {@link ManhuntAchievements}'s own class javadoc on
 * why only seven of the nine live here at all, and {@code /manhunt achievements} for the rest.
 *
 * <p>Clicking does nothing: an achievement is earned by doing the thing it describes, not by
 * pressing a button about it — see {@code ManhuntAchievements}, which is the only place any of them
 * are actually awarded.
 */
public final class ManhuntAchievementsMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ManhuntServices services;

    public ManhuntAchievementsMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_purple>Achievements");
    }

    @Override
    public String breadcrumb() {
        return "Achievements";
    }

    @Override
    protected void render() {
        ManhuntAchievements achievements = services.achievements();
        List<Achievement> curated = achievements.guiAchievements();

        int column = 1;
        for (Achievement achievement : curated) {
            band(MenuLayout.WHO, column++, iconFor(achievements, achievement));
        }
    }

    private ItemStack iconFor(ManhuntAchievements achievements, Achievement achievement) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + achievement.description());
        if (achievement.hasGoal()) {
            lore.add("<dark_gray>Progress: " + achievements.progressOf(viewer.getUniqueId(), achievement)
                    + "/" + achievement.goal().orElseThrow());
        }
        boolean earned = achievements.hasEarned(viewer.getUniqueId(), achievement);
        if (earned) {
            lore.add("<green>Earned.");
            return Icons.of(achievement.icon(), achievement.title(), lore);
        }
        return Icons.locked(Icons.of(achievement.icon(), achievement.title(), lore), "Not yet earned");
    }

    public String describe() {
        return "the seven curated Manhunt achievements, earned or not";
    }
}
