package de.raindancer.modules.sends.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.sends.SendsServices;
import de.raindancer.modules.sends.model.Send;
import de.raindancer.modules.sends.util.ItemSummary;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code /sends} opens: everything waiting for the viewer to accept, everything of theirs that has come back
 * and is waiting for room, and everything they have out.
 *
 * <p>A click does the obvious thing for each kind and the lore of each says what — accept, collect, or (after
 * asking) cancel. Right click declines what is waiting for you, and shift-click opens it up to look inside first.
 */
public final class SendsMenu extends PaginatedMenu<SendsMenu.Row> implements ISendsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int LINES_SHOWN = 5;
    /** After the service has had its write and its hand-over, so what is drawn is what is true. */
    private static final long REDRAW_TICKS = 8L;

    public enum Kind { INCOMING, RETURNING, OUTGOING }

    public record Row(Send send, Kind kind) {
    }

    private final SendsServices services;

    public SendsMenu(SendsServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Sends");
    }

    @Override
    public String breadcrumb() {
        return "Sends";
    }

    @Override
    protected List<Row> entries() {
        List<Row> rows = new ArrayList<>();
        for (Send send : services.book().incoming(viewer.getUniqueId())) {
            rows.add(new Row(send, Kind.INCOMING));
        }
        for (Send send : services.book().waitingReturn(viewer.getUniqueId())) {
            rows.add(new Row(send, Kind.RETURNING));
        }
        for (Send send : services.book().outgoing(viewer.getUniqueId())) {
            rows.add(new Row(send, Kind.OUTGOING));
        }
        return rows;
    }

    @Override
    protected ItemStack icon(Row row) {
        Send send = row.send();
        List<String> lore = new ArrayList<>();
        List<ItemSummary.Line> lines = ItemSummary.lines(send.items().stream().map(ItemSummary::lineOf).toList());
        for (int at = 0; at < Math.min(LINES_SHOWN, lines.size()); at++) {
            lore.add("<gray>" + lines.get(at).amount() + "× <white>" + escape(lines.get(at).name()));
        }
        if (lines.size() > LINES_SHOWN) {
            lore.add("<dark_gray>…and " + (lines.size() - LINES_SHOWN) + " more");
        }
        lore.add("");
        Material material = send.items().getFirst().getType();
        switch (row.kind()) {
            case INCOMING -> {
                lore.add("<gray>Runs out in <white>" + services.sends().left(send));
                lore.add("");
                lore.add("<green>Click to accept");
                lore.add("<red>Right click to decline");
                lore.add("<yellow>Shift click to look inside");
                return Icons.of(material, "<white>From " + send.senderName(), lore);
            }
            case RETURNING -> {
                lore.add("<gray>Your items, back from the send to " + send.recipientName());
                lore.add("");
                lore.add("<green>Click to take them back");
                return Icons.of(material, "<yellow>Coming back to you", lore);
            }
            default -> {
                lore.add("<gray>Runs out in <white>" + services.sends().left(send));
                lore.add("");
                lore.add("<red>Click to cancel — your items come back");
                lore.add("<yellow>Shift click to look inside");
                return Icons.of(material, "<white>To " + send.recipientName(), lore);
            }
        }
    }

    @Override
    protected void onClick(Row row, InventoryClickEvent event) {
        Send send = row.send();
        if (event.isShiftClick() && row.kind() != Kind.RETURNING) {
            new SendDetailMenu(services, viewer, this, send, row.kind()).open();
            return;
        }
        switch (row.kind()) {
            case INCOMING -> {
                if (event.isRightClick()) {
                    services.sends().release(viewer, send.id());
                } else {
                    services.sends().accept(viewer, send.id());
                }
                redrawSoon();
            }
            case RETURNING -> {
                services.sends().collect(viewer, send.id(), true);
                redrawSoon();
            }
            default -> askToCancel(send);
        }
    }

    private void askToCancel(Send send) {
        new ConfirmMenu(viewer, services.brand(), this, "<red>Cancel the send to " + send.recipientName() + "?",
                List.of("<gray>They will not be able to accept it.", "<gray>Your items come back to you."),
                "<dark_gray>You can send them again afterwards.",
                () -> services.sends().release(viewer, send.id())).open();
    }

    private void redrawSoon() {
        Scheduling.entityLater(services.plugin(), viewer, REDRAW_TICKS, () -> {
            var open = viewer.getOpenInventory().getTopInventory();
            if (open.getHolder(false) == this) {
                refresh();
            }
        });
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.CHEST, "<gray>Nothing waiting",
                "<dark_gray>/send create sends something to somebody.");
    }

    private static String escape(String text) {
        return MINI.escapeTags(text);
    }

    @Override
    public String describe() {
        return "the list of sends";
    }
}
