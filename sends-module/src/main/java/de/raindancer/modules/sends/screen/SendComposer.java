package de.raindancer.modules.sends.screen;

import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.choose.PlayerEntry;
import de.raindancer.core.ui.menu.ItemTrayMenu;
import de.raindancer.modules.sends.SendsServices;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Making a send: pick somebody who is online, then put items in a window and press Send.
 *
 * <p>Not a menu itself: the two windows are Core's — the player chooser and the item tray — and this is the order
 * they come in. The tray gives everything back if the window is closed without sending.
 */
public final class SendComposer implements ISendsScreen {

    private final SendsServices services;
    private final Player viewer;
    private final UUID recipient;
    private final String recipientName;

    /** Starts with the chooser. */
    public SendComposer(SendsServices services, Player viewer) {
        this(services, viewer, null, null);
    }

    /** Starts at the window, for somebody already picked. */
    public SendComposer(SendsServices services, Player viewer, UUID recipient, String recipientName) {
        this.services = services;
        this.viewer = viewer;
        this.recipient = recipient;
        this.recipientName = recipientName;
    }

    @Override
    public void open() {
        if (recipient != null) {
            tray(recipient, recipientName);
            return;
        }
        PlayerDirectory online = new PlayerDirectory(() -> {
            List<PlayerEntry> everybody = new ArrayList<>(services.server().getOnlinePlayers().stream()
                    .map(player -> new PlayerEntry(player.getUniqueId(), player.getName(), true,
                            System.currentTimeMillis()))
                    .toList());
            // Players of the other servers, as presence shows them: never a vanished one.
            services.core().networkPlayers().ifPresent(players -> players.everyone(false).stream()
                    .filter(there -> !there.server().equals(players.self()))
                    .forEach(there -> everybody.add(new PlayerEntry(there.player(), there.name(), true,
                            System.currentTimeMillis()))));
            return everybody;
        }, System::currentTimeMillis);
        new PlayerChooser(viewer, services.brand(), null, "Send items to…", online,
                List.of(viewer.getUniqueId()), entry -> tray(entry.id(), entry.name())).open();
    }

    private void tray(UUID to, String name) {
        long minutes = Math.max(1, services.config().expiryMinutes());
        new ItemTrayMenu(viewer, services.brand(), null, "<dark_gray>Send to <white>" + name,
                "<green>Send it",
                List.of("<gray>To <white>" + name,
                        "<gray>They have <white>" + minutes + "</white> minutes to accept.",
                        "<dark_gray>Closing this window gives your items back."),
                items -> services.sends().create(viewer, to, items),
                ItemTrayMenu.giveBack(viewer)).open();
    }

    @Override
    public String describe() {
        return "choosing somebody and filling a window for them";
    }
}
