package de.raindancer.modules.wallsroads.util;

import de.raindancer.modules.wallsroads.WallsRoadsSettings;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game manual, as a written book — the same shape as {@code claims-module}'s.
 *
 * <p>Written against what this server actually does rather than against everything the module can:
 * the thresholds in the text are read from the live settings, so a page that says a sea tunnel starts
 * at twenty-four blocks is telling the truth on a server that changed it to sixty.
 *
 * <p>Commands are clickable, and only the harmless ones run outright. Nothing that builds or removes
 * anything is click-to-run — a manual that can flatten a hillside by being read carelessly is a trap.
 */
public final class ManualBook {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How wide a book page is, in characters, near enough for laying text out. */
    private static final int PAGE_WIDTH = 19;

    /** And how many lines fit on one before it spills onto a page nobody asked for. */
    private static final int PAGE_LINES = 14;

    private final WallsRoadsSettings settings;

    public ManualBook(WallsRoadsSettings settings) {
        this.settings = settings;
    }

    /** The book as an item, to keep in a chest or hand to somebody. */
    public ItemStack asItem() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta != null) {
            meta.title(mm("<dark_aqua>Walls and Roads"));
            meta.author(mm("<gray>Rain's Flexible Modules"));
            meta.addPages(pages().toArray(new Component[0]));
            book.setItemMeta(meta);
        }
        return book;
    }

    /** The same content for {@code Player#openBook}, which shows it without handing anything over. */
    public Book asBook() {
        return Book.book(mm("<dark_aqua>Walls and Roads"), mm("<gray>Rain's Flexible Modules"), pages());
    }

    /** The title the item carries, so a copy already in somebody's inventory can be recognised. */
    public static String title() {
        return "Walls and Roads";
    }

    public List<Component> pages() {
        List<Component> pages = new ArrayList<>();

        pages.add(join(
                mm("<dark_aqua><bold>Walls and Roads"),
                mm(""),
                mm("<black>You mark where a road or wall should go. It gets built — and it can be taken back down, putting back what was there."),
                mm(""),
                mm("<dark_gray>Nothing here is permanent.")));

        pages.add(join(
                mm("<dark_aqua><bold>Marking one out"),
                mm(""),
                run("/wallsroads road new"),
                run("/wallsroads wall new"),
                mm(""),
                mm("<black>Either hands you a stick.")));

        pages.add(join(
                mm("<dark_aqua><bold>The stick"),
                mm(""),
                mm("<dark_gray>Right-click"),
                mm("<black>add a corner"),
                mm("<dark_gray>Left-click"),
                mm("<black>undo the last one"),
                mm("<dark_gray>Shift + right-click"),
                mm("<black>finish and build"),
                mm("<dark_gray>Shift + left-click"),
                mm("<black>give up")));

        pages.add(join(
                mm("<dark_aqua><bold>What a road does"),
                mm(""),
                mm("<black>A road crosses what is in its way rather than lying over it."),
                mm(""),
                mm("<dark_gray>A gap becomes a bridge, with railings and piers."),
                mm(""),
                mm("<dark_gray>A hill becomes a tunnel: bored, lined and lit.")));

        pages.add(join(
                mm("<dark_aqua><bold>Crossing water"),
                mm(""),
                mm("<black>A short crossing is bridged."),
                mm(""),
                mm("<black>A long, deep one goes under, in a glass tunnel on the sea bed."),
                mm(""),
                mm("<dark_gray>Here, at least"),
                mm("<dark_gray>" + settings.seaTunnelMinLength() + " blocks across and"),
                mm("<dark_gray>" + settings.seaTunnelMinDepth() + " blocks deep.")));

        pages.add(join(
                mm("<dark_aqua><bold>Kinds of road"),
                mm(""),
                mm("<black>One button cycles what a road is made of:"),
                mm(""),
                mm("<dark_gray>Track: paving only"),
                mm("<dark_gray>Made road: kerbs and lanterns"),
                mm("<dark_gray>Highway: stone kerbs and sea lanterns"),
                mm(""),
                mm("<black>A built one is relaid.")));

        pages.add(join(
                mm("<dark_aqua><bold>Kinds of wall"),
                mm(""),
                mm("<dark_gray>Plain: a boundary"),
                mm("<dark_gray>Town: footings, battlements, a walkway inside"),
                mm("<dark_gray>Fortress: and corner towers"),
                mm(""),
                mm("<black>Corners can be rounded.")));

        pages.add(join(
                mm("<dark_aqua><bold>Gates"),
                mm(""),
                mm("<black>Where a road crosses a wall, an opening is cut for it."),
                mm(""),
                mm("<black>Right-click a gate to open or shut it."),
                mm(""),
                mm("<dark_gray>Sealing is different: it bricks the opening up.")));

        pages.add(join(
                mm("<dark_aqua><bold>Signs"),
                mm(""),
                mm(settings.autoPlaceSigns()
                        ? "<black>A road signs its own ends, gates and junctions."
                        : "<black>Automatic signs are off here, but a road's page can still put them up."),
                mm(""),
                mm("<dark_gray>Any sign can be reworded, or pointed at a place.")));

        pages.add(join(
                mm("<dark_aqua><bold>What it costs"),
                mm(""),
                mm(settings.chargeMaterials()
                        ? "<black>Building takes the blocks from your inventory, and stops where they run out."
                        : "<black>Building costs nothing here."),
                mm(""),
                mm("<dark_gray>A road's page says how many blocks it needs.")));

        pages.add(join(
                mm("<dark_aqua><bold>Taking it back"),
                mm(""),
                mm("<black>Every build has an exact opposite. Taking a wall down puts back what was under it."),
                mm(""),
                mm("<dark_gray>Which is why nobody else may mine it.")));

        pages.add(join(
                mm("<dark_aqua><bold>Everything else"),
                mm(""),
                run("/wallsroads"),
                mm("<dark_gray>your walls and roads"),
                mm(""),
                run("/wallsroads map"),
                mm("<dark_gray>gates and road ends onto your own map"),
                mm(""),
                type("/wallsroads config"),
                mm("<dark_gray>how this server builds them")));

        return pages;
    }

    /** A command safe to fire on a stray click: it opens or prints something and changes nothing. */
    private static Component run(String command) {
        return mm("<blue><underlined>" + command)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(mm("<gray>Click to run <white>" + command)));
    }

    /** One that changes something: clicking types it into the chat bar and leaves the reader to send it. */
    private static Component type(String command) {
        return mm("<blue>" + command)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(mm("<gray>Click to type <white>" + command)));
    }

    private static Component join(Component... lines) {
        Component page = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            page = page.append(lines[i]);
            if (i < lines.length - 1) {
                page = page.append(Component.newline());
            }
        }
        return page;
    }

    private static Component mm(String miniMessage) {
        return MINI.deserialize(miniMessage);
    }

    /** How many lines a page's worth of text wraps to — what a test asserts about the layout. */
    public static int wrappedLines(String plain) {
        int lines = 0;
        for (String paragraph : plain.split("\n", -1)) {
            lines += paragraph.isEmpty() ? 1
                    : (int) Math.ceil(paragraph.length() / (double) PAGE_WIDTH);
        }
        return lines;
    }

    public static int linesPerPage() {
        return PAGE_LINES;
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
