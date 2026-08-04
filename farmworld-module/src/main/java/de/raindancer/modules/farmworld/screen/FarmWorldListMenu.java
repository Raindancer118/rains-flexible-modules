package de.raindancer.modules.farmworld.screen;

import de.raindancer.core.world.time.Times;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.farmworld.FarmWorldServices;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import de.raindancer.modules.farmworld.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The farm worlds on this server: what {@code /farm} opens.
 *
 * <h2>Why the front door is a menu and not a list in chat</h2>
 * Because the one question somebody has before they go is "how long has it got left", and that is a number
 * next to a name rather than a sentence. On a button it is in front of them at the moment they decide; in
 * chat it has scrolled away by the time they finish reading the rest of the list.
 *
 * <p>The chat listing is still there behind {@code /farm list}, for the console, which has no inventory to
 * open, and for pasting to somebody else.
 *
 * <h2>What is shown, and why nothing is hidden</h2>
 * Every farm world, including the ones this player cannot enter — greyed, with the reason. That is the
 * opposite of the warps module, deliberately: a staff warp's name is worth keeping quiet, and a farm world's
 * is not. It is one of two or three places the whole server talks about, so somebody who hears about the
 * donor world every day and cannot see it on their own list learns nothing except that their list is wrong.
 */
public final class FarmWorldListMenu extends PaginatedMenu<FarmWorldView> implements IFarmWorldScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final FarmWorldServices services;

    public FarmWorldListMenu(FarmWorldServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        // Never the brand: it is already prefixed, so "Farm worlds" here would render as
        // "Farm worlds » Farm worlds".
        return MINI.deserialize("<dark_gray>" + breadcrumb());
    }

    @Override
    public String breadcrumb() {
        return "Somewhere to dig";
    }

    @Override
    protected List<FarmWorldView> entries() {
        return services.catalogue().visibleTo(viewer::hasPermission, services.access());
    }

    /**
     * An empty list is the ordinary state on a new server, and it says what to do about it.
     *
     * <p>Different words for an admin, because they are the one who can act on it — and the button acts,
     * rather than only describing what to type.
     */
    @Override
    protected ItemStack emptyIcon() {
        if (services.access().mayManage(viewer::hasPermission)) {
            return Icons.of(Material.COBWEB, "<gray>There are no farm worlds yet",
                    "<gray>Click here to be told how to make one.",
                    "<dark_gray>It is one command, and the server pauses",
                    "<dark_gray>for a moment while the world is generated.");
        }
        return Icons.of(Material.COBWEB, "<gray>There are no farm worlds yet",
                "<gray>Nobody has made one on this server.");
    }

    /**
     * The way out of an empty page, for the person who can take it.
     *
     * <p>A list that names the way out must be able to act on it, or the sentence is decoration.
     */
    @Override
    protected void emptyAction(InventoryClickEvent event) {
        if (services.access().mayManage(viewer::hasPermission)) {
            viewer.closeInventory();
            services.messages().send(viewer, "farmworlds.how-to-make-one");
        } else {
            services.messages().send(viewer, "farmworlds.none-yet");
        }
    }

    @Override
    protected ItemStack icon(FarmWorldView farm) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + describeParts(farm));
        farm.border().ifPresent(radius ->
                lore.add("<dark_gray>" + radius + " blocks from the middle"));
        lore.add("");
        lore.addAll(lifespanOf(farm));
        lore.add("");

        String refusal = services.access().refusalKey(farm.name(), viewer::hasPermission);
        if (refusal != null) {
            // Greyed rather than hidden — and the reason is the module's own wording rather than a
            // sentence written here, so the greyed lore and the refusal in chat cannot come to say
            // different things about the same node.
            lore.add("<red>" + plain(refusal, farm));
            return Icons.of(Material.GRAY_DYE, "<white>" + farm.name(), lore);
        }
        if (!farm.loaded()) {
            lore.add("<red>Its world is not loaded right now.");
            return Icons.of(Material.GRAY_DYE, "<white>" + farm.name(), lore);
        }
        lore.add("<gray>Click to look at it, or to go.");
        return Icons.of(Material.GRASS_BLOCK, "<white>" + farm.name(), lore);
    }

    /**
     * How long it has left, as the two or three lines that answer the only question people have.
     *
     * <p>A farm world with no schedule says so rather than saying nothing: "kept until somebody says
     * otherwise" is the difference between building a base there and not.
     */
    private List<String> lifespanOf(FarmWorldView farm) {
        List<String> lines = new ArrayList<>();
        if (!farm.isScheduled()) {
            lines.add("<gray>Kept until somebody regenerates it.");
            lines.add("<dark_gray>Which they can, at any time — so what you");
            lines.add("<dark_gray>leave here is still not safe.");
            return lines;
        }
        farm.every().ifPresent(every ->
                lines.add("<dark_gray>Made again every " + Times.describe(every)));
        farm.untilRegenerated().ifPresentOrElse(
                left -> lines.add("<yellow>" + Times.describe(left) + " left"),
                () -> lines.add("<red>Due to be made again"));
        lines.add("<dark_gray>Everything in it goes when it is.");
        return lines;
    }

    private static String describeParts(FarmWorldView farm) {
        StringBuilder said = new StringBuilder("Overworld");
        if (farm.hasNether()) {
            said.append(", its own nether");
        }
        if (farm.hasEnd()) {
            said.append(", its own end");
        }
        return said.toString();
    }

    /**
     * One of the module's own lines, as the words alone.
     *
     * <p>Through {@code Messages} rather than written here so that an owner who has reworded the refusal
     * sees their wording on the greyed button too — the two saying different things about the same
     * permission is exactly the sort of small wrongness nobody reports and everybody notices.
     *
     * <p>Flattened to plain text rather than serialised back to MiniMessage: the line already carries its
     * own colours, and putting those inside the {@code <red>} this lore line adds would nest one colour
     * inside another and draw whichever won in a lore line meant to read as a refusal.
     */
    private String plain(String key, FarmWorldView farm) {
        return PlainTextComponentSerializer.plainText()
                .serialize(services.messages().get(key, "name", farm.name()));
    }

    @Override
    protected void onClick(FarmWorldView farm, InventoryClickEvent event) {
        // Its own page rather than straight in. A farm world is not a warp: going costs a warm-up and a
        // wait, and the page is where the "how long has it got" and "what is in it" that decide whether to
        // bother are readable rather than crammed into a lore line.
        services.screens().farm(viewer, farm.name());
    }

    /**
     * The admin door.
     *
     * <p>Alone in the toolbar, and only for somebody who can use it: there is nothing else on this page
     * worth a tool, and a second button two columns along would be a button invented to fill the row.
     */
    @Override
    protected void decorate() {
        super.decorate();
        if (services.access().mayManage(viewer::hasPermission)) {
            toolbar(6, Icons.of(Material.COMPARATOR, "<white>How farm worlds work here",
                            "<gray>What a trip costs, where people land,",
                            "<gray>and how much notice they get.",
                            "<dark_gray>Only you and the other admins see this."),
                    click -> services.screens().config(viewer));
        }
    }

    @Override
    protected List<String> helpLines() {
        // Core draws these as a book. Generated from the settings that are loaded, so the page cannot come
        // to describe a warm-up or a wait this server does not have.
        return services.messages().lines("farmworlds.manual.using",
                        "warmup", services.config().warmup(),
                        "cooldown", services.config().cooldown())
                .stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "the farm worlds on this server";
    }

    /** What opens this page from a command. */
    public static String permission() {
        return PermissionNodes.USE;
    }
}
