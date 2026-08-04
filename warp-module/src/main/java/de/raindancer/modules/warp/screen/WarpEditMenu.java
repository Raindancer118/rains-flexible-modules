package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.warp.Warp;
import de.raindancer.modules.warp.WarpServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One warp, and everything that can be changed about it.
 *
 * <h2>Why the warp is held by name rather than as a value</h2>
 * Because everything on this page changes it, and a page holding the warp it was opened with would
 * redraw the version from before the click. Looked up on every draw instead, so the page is always
 * showing what is actually stored — and a warp deleted from underneath it says so rather than
 * offering buttons for something that has gone.
 *
 * <h2>The layout</h2>
 * Two columns apart, so a pane falls between each pair: who it is for, what it is filed under, what
 * it is called, and what it looks like. Moving it is on the row below, with the delete in the danger
 * slot — which is flanked by navigation, so a misclick costs a page rather than the warp.
 */
public final class WarpEditMenu extends Menu implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How long an admin has to answer before the question is dropped. */
    private static final Duration TO_ANSWER = Duration.ofSeconds(60);

    private final WarpServices services;
    private final String name;

    public WarpEditMenu(WarpServices services, Player viewer, Menu parent, String name) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.name = name;
    }

    private Warp warp() {
        return services.catalogue().byName(name).orElse(null);
    }

    @Override
    protected Component title() {
        Warp warp = warp();
        return MINI.deserialize("<dark_gray>" + (warp == null ? name : warp.label()));
    }

    @Override
    public String breadcrumb() {
        Warp warp = warp();
        return warp == null ? name : warp.label();
    }

    @Override
    protected void render() {
        Warp warp = warp();
        if (warp == null) {
            // Deleted from under this page — by another admin, or by this one from a command in
            // between. Offering buttons for it would be offering buttons for nothing.
            band(MenuLayout.RULES, 4, Icons.of(Material.BARRIER, "<red>This warp is gone",
                    "<gray>Somebody deleted it while this page was open."));
            return;
        }

        band(MenuLayout.WHO, 1, Icons.of(Material.SHIELD, "<white>Who it is for",
                        "<gray>" + services.catalogue().accessOf(warp).describe(),
                        "",
                        "<gray>Click to change who may use it."),
                click -> new WarpAccessMenu(services, viewer, this, name).open());

        band(MenuLayout.WHO, 3, Icons.of(Material.BOOKSHELF, "<white>Filed under",
                        "<gray>" + warp.category().orElse("nothing"),
                        "",
                        "<gray>Click to type a category.",
                        "<gray>Right click to take it out of every category."),
                click -> {
                    if (click.isRightClick()) {
                        services.admin().setCategory(viewer, name, null);
                        refresh();
                        return;
                    }
                    ask("warps.ask-category",
                            answer -> services.admin().setCategory(viewer, name, answer));
                });

        band(MenuLayout.WHO, 5, Icons.of(Material.NAME_TAG, "<white>What it is called",
                        "<gray>Shown as " + warp.label(),
                        "<dark_gray>Typed as " + warp.name(),
                        "",
                        "<gray>Click to type what the menu should call it.",
                        "<gray>Right click to call it by its name again.",
                        "<dark_gray>What people type never changes: a permission",
                        "<dark_gray>was written against it."),
                click -> {
                    if (click.isRightClick()) {
                        services.admin().setLabel(viewer, name, null);
                        refresh();
                        return;
                    }
                    ask("warps.ask-label",
                            answer -> services.admin().setLabel(viewer, name, answer));
                });

        Material icon = warp.poi().icon() == null || !warp.poi().icon().isItem()
                ? Material.LODESTONE : warp.poi().icon();
        band(MenuLayout.WHO, 7, Icons.of(icon, "<white>What it looks like",
                        "<gray>The block shown for it in the menu.",
                        "",
                        "<gray>Click to pick one."),
                click -> new ItemChooser(viewer, services.brand(), this, "Pick an icon",
                        chosen -> {
                            services.admin().setIcon(viewer, name, chosen);
                            open();
                        }).open());

        band(MenuLayout.LAND, 2, Icons.of(Material.ENDER_PEARL, "<white>Move it here",
                        "<gray>" + warp.world() + " " + warp.coordinates(),
                        "",
                        "<gray>Puts it where you are standing, facing",
                        "<gray>the way you are. Keeps everything else",
                        "<gray>about it — who it is for included."),
                click -> {
                    services.admin().move(viewer, name);
                    refresh();
                });

        band(MenuLayout.LAND, 6, Icons.of(Material.COMPASS, "<white>Go to it",
                        "<gray>To see where it actually puts somebody.",
                        "",
                        "<gray>Click to go. This closes the menu."),
                click -> {
                    viewer.closeInventory();
                    services.travelling().go(viewer, warp);
                });

        // The danger slot, and the only irreversible thing on the page — so it asks first.
        danger(Icons.of(Material.BARRIER, "<red>Delete this warp",
                        "<gray>Everything about it goes: who it is for,",
                        "<gray>what it is filed under, what it looks like.",
                        "",
                        "<gray>You will be asked first."),
                click -> new ConfirmScreen(services, viewer, this,
                        "<red>Delete " + warp.label() + "?",
                        List.of("<gray>The warp goes, with its category,",
                                "<gray>its icon and who it was for.",
                                "<gray>Anything pointing at it stops working."),
                        () -> {
                            services.admin().delete(viewer, name);
                            // Back two pages: the page this came from is about a warp that is gone.
                            if (parent() != null && parent().parent() != null) {
                                parent().parent().open();
                            } else if (parent() != null) {
                                parent().open();
                            } else {
                                viewer.closeInventory();
                            }
                        }).open());
    }

    /**
     * Asks in chat, and reopens this page with the answer applied.
     *
     * <p>A category and a label have nothing to enumerate, so there is no chooser for them — which
     * is the whole of the module's exception to "never ask for something in chat".
     */
    private void ask(String question, java.util.function.Consumer<String> withTheAnswer) {
        viewer.closeInventory();
        boolean asking = services.core().prompts().ask(viewer.getUniqueId(), "warps", TO_ANSWER,
                answer -> {
                    withTheAnswer.accept(answer);
                    open();
                },
                this::open);
        if (!asking) {
            services.messages().send(viewer, "warps.busy");
            open();
            return;
        }
        services.messages().send(viewer, question);
    }

    @Override
    protected List<String> helpLines() {
        List<String> lines = new ArrayList<>(services.messages().lines("warps.manual.editing")
                .stream().map(MINI::serialize).toList());
        return lines;
    }

    @Override
    public String describe() {
        return "one warp, and everything that can be changed about it";
    }
}
