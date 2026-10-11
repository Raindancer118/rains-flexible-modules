package de.raindancer.modules.sends.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.sends.SendsServices;
import de.raindancer.modules.sends.model.Send;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Looking inside a send, exactly as it is, with the buttons that apply to the viewer. Nothing here can be taken. */
public final class SendDetailMenu extends Menu implements ISendsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SendsServices services;
    private final Send send;
    private final SendsMenu.Kind kind;

    public SendDetailMenu(SendsServices services, Player viewer, Menu parent, Send send, SendsMenu.Kind kind) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.send = send;
        this.kind = kind;
    }

    @Override
    protected Component title() {
        return MINI.deserialize(kind == SendsMenu.Kind.INCOMING
                ? "<dark_gray>From <white>" + MINI.escapeTags(send.senderName())
                : "<dark_gray>To <white>" + MINI.escapeTags(send.recipientName()));
    }

    @Override
    public String breadcrumb() {
        return "the send";
    }

    @Override
    protected void render() {
        int slot = 0;
        for (ItemStack item : send.items()) {
            set(slot++, item.clone());
        }
        if (kind == SendsMenu.Kind.INCOMING) {
            toolbar(2, Icons.of(Material.LIME_CONCRETE, "<green>Accept",
                    "<gray>Take everything shown here.",
                    "<gray>Runs out in <white>" + services.sends().left(send)), click -> {
                services.sends().accept(viewer, send.id());
                viewer.closeInventory();
            });
            toolbar(6, Icons.of(Material.RED_CONCRETE, "<red>Decline",
                    "<gray>It goes back to " + send.senderName() + "."), click -> {
                services.sends().release(viewer, send.id());
                viewer.closeInventory();
            });
        } else {
            toolbar(4, Icons.of(Material.RED_CONCRETE, "<red>Cancel this send",
                    "<gray>Your items come back to you."), click -> new ConfirmMenu(viewer, services.brand(), this,
                    "<red>Cancel the send to " + send.recipientName() + "?",
                    List.of("<gray>They will not be able to accept it.", "<gray>Your items come back to you."),
                    "<dark_gray>You can send them again afterwards.",
                    () -> services.sends().release(viewer, send.id())).open());
        }
    }

    @Override
    public String describe() {
        return "looking inside one send";
    }
}
