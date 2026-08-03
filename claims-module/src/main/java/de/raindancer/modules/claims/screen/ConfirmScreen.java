package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.Claim;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * "Are you sure?", as a page of its own.
 *
 * <p>Three rows rather than six, because a question with two answers on a full page reads as an empty page with
 * two buttons lost in it. And a page rather than a chat prompt because the thing being confirmed is on screen:
 * somebody who opened the wrong claim's menu sees the wrong claim's name here.
 *
 * <p>Yes is on the right and No is on the left, always, on every confirmation in the module. A dialog that swaps
 * them is a dialog people learn to click through and then get wrong once.
 */
public final class ConfirmScreen extends ClaimScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String question;
    private final List<String> consequences;
    private final Runnable onYes;

    public ConfirmScreen(ClaimServices services, Player viewer, Claim claim, Menu parent,
                         String question, List<String> consequences, Runnable onYes) {
        super(services, viewer, claim, parent, 3);
        this.question = question;
        this.consequences = List.copyOf(consequences);
        this.onYes = onYes;
    }

    @Override
    protected Component title() {
        return MINI.deserialize(question);
    }

    @Override
    protected void render() {
        List<String> lore = new ArrayList<>(consequences);
        lore.add("");
        lore.add("<dark_gray>This cannot be undone.");

        band(MenuLayout.WHO, 2, Icons.of(Material.RED_CONCRETE, "<red>No, leave it alone",
                        "<gray>Nothing happens."),
                click -> {
                    if (parent() != null) {
                        parent().open();
                    } else {
                        viewer.closeInventory();
                    }
                });

        band(MenuLayout.WHO, 4, Icons.of(Material.BOOK, "<gray>What this does", lore));

        band(MenuLayout.WHO, 6, Icons.of(Material.LIME_CONCRETE, "<green>Yes, do it",
                        "<gray>Go ahead."),
                click -> onYes.run());
    }
}
