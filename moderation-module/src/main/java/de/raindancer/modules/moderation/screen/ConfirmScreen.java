package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * "Are you sure?", as a page of its own.
 *
 * <p>Three rows rather than six, because a question with two answers on a full page reads as an empty
 * page with two buttons lost in it. And a page rather than a chat prompt because the thing being
 * confirmed is on screen: somebody about to ban the wrong person sees the wrong person's name here.
 *
 * <p>Yes is on the right and No is on the left, always, on every confirmation in the module — the same
 * arrangement the claims screens use, so a server that has both has one habit rather than two. A dialog
 * that swaps them is a dialog people learn to click through and then get wrong once.
 */
public final class ConfirmScreen extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String question;
    private final List<String> consequences;
    private final Runnable onYes;

    public ConfirmScreen(ModerationServices services, Player viewer, Menu parent, String question,
                         List<String> consequences, Runnable onYes) {
        super(services, viewer, parent, 3);
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
        lore.add("<dark_gray>It goes on their record either way.");

        band(MenuLayout.WHO, 2, Icons.of(Material.RED_CONCRETE, "<red>No, leave it",
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

    @Override
    public String describe() {
        return "a confirmation, so a misclick costs a page rather than the thing";
    }
}
