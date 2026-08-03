package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.choose.PlayerEntry;
import de.raindancer.core.ui.choose.Presence;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

/**
 * Choosing who to look at.
 *
 * <h2>Why not "type their name"</h2>
 * Because typing a name fails exactly when it matters. Somebody being unbanned is, definitionally, not
 * here, and their name is the one thing nobody remembers correctly — a capital letter, an underscore, a
 * zero for an O. And a name the server has never seen resolves to a made-up profile, so the ban lands
 * on nobody and looks like it worked.
 *
 * <p>The ordering, the sectioning and the searching are all Core's {@link PlayerDirectory}: online
 * first, then most recently seen, because alphabetical is the order that looks tidy and puts the person
 * you want on page eleven. This page is the drawing of it and nothing else.
 */
public final class PlayerPickerMenu extends ModerationList<PlayerEntry> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private String searching = "";

    public PlayerPickerMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    @Override
    protected Component title() {
        return MINI.deserialize(searching.isBlank()
                ? "<dark_gray>Who?" : "<dark_gray>Who? <white>" + searching);
    }

    @Override
    public String breadcrumb() {
        return "Who";
    }

    @Override
    protected List<PlayerEntry> entries() {
        PlayerDirectory everybody = services().everybody();
        return searching.isBlank() ? everybody.everybody() : everybody.search(searching);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody matches",
                searching.isBlank()
                        ? "<gray>The server has never seen anybody."
                        : "<gray>Nothing here is called <white>" + searching + "</white>.");
    }

    @Override
    protected ItemStack icon(PlayerEntry who) {
        long now = System.currentTimeMillis();
        Presence where = services().everybody().presenceOf(who);
        return Icons.head(who.id(), "<yellow>" + who.name(),
                List.of("<gray>" + who.lastSeenDescribed(now),
                        "<dark_gray>" + where.title(),
                        "",
                        "<dark_gray>Click to open their page."));
    }

    @Override
    protected void onClick(PlayerEntry who, InventoryClickEvent event) {
        new PlayerMenu(services(), viewer, this, who.id(), who.name()).open();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.SPYGLASS, "<yellow>Search",
                        searching.isBlank() ? "<gray>Type part of a name."
                                : "<gray>Showing <white>" + searching + "</white>.",
                        "<dark_gray>You will be asked in chat."),
                click -> askWhatFor());
        if (!searching.isBlank()) {
            toolbar(6, Icons.of(Material.BARRIER, "<gray>Show everybody",
                            "<gray>Clears the search."),
                    click -> {
                        searching = "";
                        refresh();
                    });
        }
    }

    private void askWhatFor() {
        viewer.closeInventory();
        tell("moderation.type-a-name");
        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(60),
                typed -> {
                    searching = typed == null ? "" : typed.trim();
                    open();
                },
                () -> tell("moderation.nothing-typed"));
    }

    @Override
    public String describe() {
        return "everybody the server has seen, online first — for picking somebody who is not here";
    }
}
