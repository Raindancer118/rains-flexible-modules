package de.raindancer.modules.chat.screen;

import de.raindancer.core.ui.chat.ChatChannel;
import de.raindancer.core.ui.chat.ChatChannels;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.command.ChatCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /chat} on its own: who your lines go to. Everybody, or any channel you are part of right
 * now — team chat during a hunt, say. The current one is marked.
 */
public final class ChatChannelMenu extends PaginatedMenu<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ChatServices services;

    public ChatChannelMenu(ChatServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Talk to whom?");
    }

    @Override
    public String breadcrumb() {
        return "Chat";
    }

    @Override
    protected List<String> entries() {
        List<String> ids = new ArrayList<>();
        ids.add(ChatChannels.ALL);
        ChatChannels.availableTo(viewer.getUniqueId()).forEach(channel -> ids.add(channel.id()));
        return ids;
    }

    @Override
    protected ItemStack icon(String id) {
        boolean current = ChatChannels.selected(viewer.getUniqueId()).equalsIgnoreCase(id);
        String footer = current ? "<yellow>You are talking here." : "<dark_gray>Click to talk here.";
        if (id.equals(ChatChannels.ALL)) {
            return Icons.of(current ? Material.LIME_BANNER : Material.WHITE_BANNER, "<white>Everybody",
                    "<gray>Public chat — everybody online reads it.", footer);
        }
        ChatChannel channel = ChatChannels.byId(id).orElse(null);
        String label = channel == null ? id : channel.label();
        String tag = channel == null ? "" : channel.tagFor(viewer.getUniqueId());
        return Icons.of(current ? Material.LIME_BANNER : Material.CYAN_BANNER, "<aqua>" + MINI.escapeTags(label),
                "<gray>Only " + MINI.escapeTags(tag) + " reads it.", footer);
    }

    @Override
    protected void onClick(String id, InventoryClickEvent event) {
        ChatCommand.switchChannel(services, viewer, id);
        refresh();
    }
}
