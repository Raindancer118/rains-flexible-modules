package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffNote;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the staff have written about somebody.
 *
 * <p>Never shown to the player it is about — which is the whole reason a note can be honest, and why
 * this whole page sits behind {@code ModerationPermission.NOTES}.
 *
 * <h2>Removing one</h2>
 * On a right click, said so on every note, and behind a confirmation. Audited with what it said before
 * it goes: a note that can be removed without trace is one a moderator can quietly delete about
 * themselves, and the trail is the only thing that makes that visible.
 */
public final class NotesMenu extends ModerationList<StaffNote> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public NotesMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                     String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Notes — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Notes";
    }

    @Override
    protected List<StaffNote> entries() {
        return services().noteService().about(subject);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing written yet",
                "<gray>Use the button below to write the first.");
    }

    @Override
    protected ItemStack icon(StaffNote note) {
        return Icons.of(Material.WRITABLE_BOOK, "<yellow>" + note.authorName(),
                List.of("<gray>" + note.text(),
                        "",
                        "<dark_gray>" + Times.describe(Duration.between(note.at(), Instant.now()))
                                + " ago",
                        "<dark_gray>Right click to remove it."));
    }

    @Override
    protected void onClick(StaffNote note, InventoryClickEvent event) {
        if (!event.isRightClick()) {
            return;     // a left click on a note does nothing; the lore says what the right one does
        }
        if (!may(ModerationPermission.NOTES)) {
            tell("moderation.no-permission");
            return;
        }
        new ConfirmScreen(services(), viewer, this, "<red>Remove this note?",
                List.of("<gray>" + note.text(),
                        "<gray>Written by <white>" + note.authorName() + "</white>.",
                        "<dark_gray>The removal is recorded, with what it said."),
                () -> {
                    services().noteService().remove(note.id(), viewer.getUniqueId(), viewer.getName());
                    tell("moderation.note-removed");
                    open();
                }).open();
    }

    /**
     * The write button, in the toolbar under the list.
     *
     * <p>Added in {@code render} after the entries, because {@code PaginatedMenu} fills the pages and
     * the toolbar row is what is left over — a paged screen that painted its own footer would sit on
     * top of the page arrows, which is the framework mistake {@code PaginatedMenu} exists to stop.
     */
    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.WRITABLE_BOOK, "<green>Write a note",
                        "<gray>About <white>" + subjectName + "</white>.",
                        "<dark_gray>You will be asked to type it in chat."),
                click -> askForOne());
    }

    private void askForOne() {
        if (!may(ModerationPermission.NOTES)) {
            tell("moderation.no-permission");
            return;
        }
        viewer.closeInventory();
        tell("moderation.type-a-note", "player", subjectName);
        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(120),
                typed -> {
                    services().noteService().add(subject, subjectName, viewer.getUniqueId(),
                            viewer.getName(), typed);
                    tell("moderation.note-written", "player", subjectName);
                },
                () -> tell("moderation.nothing-typed"));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Notes are never shown to the player they are about.",
                "<gray>Right click a note to remove it.",
                "<gray>Use <white>/mod note <player> <text></white> to write one.");
    }

    @Override
    public String describe() {
        return "the staff notes about somebody, which they never see";
    }
}
