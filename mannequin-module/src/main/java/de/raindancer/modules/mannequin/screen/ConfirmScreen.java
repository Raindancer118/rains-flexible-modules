package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.mannequin.MannequinServices;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "Are you sure?" — Core's dialog, under this module's name. See {@code homes-module}'s copy of
 * this same two-line wrapper for why it exists at all: the page is {@code ConfirmMenu}'s, always,
 * and this only gives call sites a name in this package and gives {@code ScreenGrammarTest}
 * something to look for when it checks that every {@code danger(} button confirms.
 */
public final class ConfirmScreen extends ConfirmMenu implements IMannequinScreen {

    public ConfirmScreen(MannequinServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(viewer, services.brand(), parent, question, consequences, onYes);
    }

    @Override
    public String describe() {
        return "asking before something that cannot be undone";
    }
}
