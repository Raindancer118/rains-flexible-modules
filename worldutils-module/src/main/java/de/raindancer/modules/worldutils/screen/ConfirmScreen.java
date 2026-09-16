package de.raindancer.modules.worldutils.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.modules.worldutils.WorldUtilsServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name. Everything about the page is
 * {@code ConfirmMenu}'s, deliberately: No on the left and Yes on the right everywhere is the feature.
 */
public final class ConfirmScreen extends ConfirmMenu {

    public ConfirmScreen(WorldUtilsServices services, Player viewer, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), null, question, consequences, onYes);
    }
}
