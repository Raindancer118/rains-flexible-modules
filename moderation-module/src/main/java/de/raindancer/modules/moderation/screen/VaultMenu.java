package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.core.data.stash.ArmourPiece;
import de.raindancer.modules.moderation.command.VaultCommand;
import de.raindancer.modules.moderation.service.VaultService;
import de.raindancer.core.data.stash.Stash;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * {@code /vault} — an operator's own stash, a few pages of it, with four armour stands.
 *
 * <p>Nothing in the window is a real item: the slots show copies of what the vault holds, and every click
 * asks the vault to move something. That is why shift-clicking in from below, dropping the cursor's item
 * onto the page and clicking an entry are each handled here rather than left to vanilla — vanilla would
 * move the copies, and a copy that can be picked up is a duplicated item.
 */
public final class VaultMenu extends ModerationScreen {

    /** The clicks that mean "take this". Everything else on an entry — number keys, Q, middle — is ignored. */
    private static final Set<ClickType> TAKING =
            Set.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT);

    private static final List<ArmourPiece> STANDS = List.of(ArmourPiece.values());

    private int page;

    public VaultMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    private Stash vault() {
        return services().vaults().of(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return Component.text("Vault");
    }

    @Override
    public String breadcrumb() {
        return "your vault";
    }

    private int pages() {
        return Math.max(1, Math.min(VaultService.PAGES, MenuLayout.pageCount(vault().size() + 1, VaultService.PER_PAGE)));
    }

    @Override
    protected void render() {
        List<ItemStack> items = vault().items();
        page = MenuLayout.clampPage(page, pages());
        int from = page * VaultService.PER_PAGE;
        for (int slot = 0; slot < VaultService.PER_PAGE && from + slot < items.size(); slot++) {
            int index = from + slot;
            ItemStack shown = items.get(index);
            set(slot, shown, event -> {
                if (!stillTheirs() || !TAKING.contains(event.getClick())) {
                    return;
                }
                services().vaults().take(viewer, index, shown);
                changed();
            });
        }
    }

    @Override
    protected void decorate() {
        Stash vault = vault();
        int from = page * VaultService.PER_PAGE;

        toolbar(1, vault.size() > 0,
                Icons.of(Material.HOPPER, "<green>Take all",
                        "<gray>Click: everything on this page",
                        "<gray>Shift-click: the whole vault",
                        "<dark_gray>What does not fit stays in here."),
                "The vault is empty",
                event -> {
                    if (!stillTheirs()) {
                        return;
                    }
                    if (event.isShiftClick()) {
                        services().vaults().takeAll(viewer, 0, VaultService.CAPACITY);
                    } else {
                        services().vaults().takeAll(viewer, from, from + VaultService.PER_PAGE);
                    }
                    changed();
                });
        toolbar(2, services().vaults().wearsAnything(viewer),
                Icons.of(Material.ARMOR_STAND, "<green>Put away what you are wearing",
                        "<gray>Onto its stand, or into the vault",
                        "<gray>when the stand is taken."),
                "You are not wearing anything",
                event -> {
                    if (stillTheirs()) {
                        services().vaults().storeWorn(viewer);
                        changed();
                    }
                });
        for (ArmourPiece piece : STANDS) {
            toolbar(3 + piece.ordinal(), stand(vault, piece), event -> {
                if (!stillTheirs()) {
                    return;
                }
                ItemStack cursor = viewer.getItemOnCursor();
                if (cursor.isEmpty()) {
                    services().vaults().takeArmour(viewer, piece);
                } else {
                    viewer.setItemOnCursor(services().vaults().swapOnStand(viewer, piece, cursor));
                }
                changed();
            });
        }
        toolbar(7, vault.hasArmour(),
                Icons.of(Material.DIAMOND_CHESTPLATE, "<green>Equip all",
                        "<gray>Puts on everything from the stands.",
                        "<gray>What you are wearing now goes",
                        "<gray>onto the stands instead."),
                "No armour on the stands",
                event -> {
                    if (stillTheirs()) {
                        services().vaults().equipAll(viewer);
                        changed();
                    }
                });
        fillRow(MenuLayout.TOOLBAR_ROW, Material.BLACK_STAINED_GLASS_PANE);
    }

    private static ItemStack stand(Stash vault, ArmourPiece piece) {
        return vault.armour(piece).orElseGet(() -> Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                "<gray>" + piece.label() + " stand <dark_gray>— empty",
                "<dark_gray>Click with a " + piece.label().toLowerCase() + " on the cursor,",
                "<dark_gray>or shift-click one in from below."));
    }

    @Override
    protected void paintPagingChrome(int chromeRow) {
        int pages = pages();
        if (page > 0) {
            set(chromeRow + MenuLayout.CHROME_PREVIOUS, Icons.previousPage(page, pages), event -> turnTo(page - 1));
        }
        if (page < pages - 1) {
            set(chromeRow + MenuLayout.CHROME_NEXT, Icons.nextPage(page + 2, pages), event -> turnTo(page + 1));
        }
        set(chromeRow + MenuLayout.CHROME_PAGE, Icons.pageCounter(page + 1, pages));
    }

    private void turnTo(int newPage) {
        page = newPage;
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>Shift-click anything in your inventory",
                "<gray>to put it in, or drop it onto a page.",
                "<gray>Click a stack to take it out.",
                "<gray>Armour goes onto its stand first.",
                "<gray>Sneak and right-click with the Banhammer",
                "<gray>to put it in here; with an empty hand",
                "<gray>to take it back out.",
                "<dark_gray>" + VaultService.PAGES + " pages, " + VaultService.CAPACITY + " stacks.");
    }

    /** A click with something on the cursor, anywhere on the pages, puts it in. */
    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        ItemStack cursor = event.getCursor();
        if (slot >= 0 && slot < VaultService.PER_PAGE && !cursor.isEmpty()) {
            event.setCancelled(true);
            if (!stillTheirs()) {
                return;
            }
            int accepted = services().vaults().deposit(viewer, cursor);
            if (accepted < cursor.getAmount()) {
                tell("moderation.vault.full");
            }
            cursor.setAmount(cursor.getAmount() - accepted);
            viewer.setItemOnCursor(cursor.isEmpty() ? null : cursor);
            changed();
            return;
        }
        super.handleClick(event);
    }

    @Override
    public boolean allowBottomInventoryInteraction() {
        return true;
    }

    /**
     * Their own inventory: shift-click puts the stack in, everything else works as it always does — except
     * a double click, which would gather matching copies off the page onto the cursor.
     */
    @Override
    public void handleBottomClick(InventoryClickEvent event) {
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (!event.isShiftClick()) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.isEmpty() || !stillTheirs()) {
            return;
        }
        int accepted = services().vaults().deposit(viewer, clicked);
        if (accepted < clicked.getAmount()) {
            tell("moderation.vault.full");
        }
        clicked.setAmount(clicked.getAmount() - accepted);
        event.setCurrentItem(clicked.isEmpty() ? null : clicked);
        changed();
    }

    /** Asked again on every click: the window can stay open after the node is taken away. */
    private boolean stillTheirs() {
        if (viewer.hasPermission(VaultCommand.USE)) {
            return true;
        }
        tell("moderation.vault.not-yours");
        viewer.closeInventory();
        return false;
    }
}
