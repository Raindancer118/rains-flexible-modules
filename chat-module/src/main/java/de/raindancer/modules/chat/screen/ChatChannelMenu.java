package de.raindancer.modules.chat.screen;

import de.raindancer.core.ui.chat.ChatChannel;
import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.ui.chat.ChatChannels;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.modules.chat.command.AdCommand;
import de.raindancer.modules.chat.util.PermissionNodes;
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
    protected void render() {
        super.render();
        toolbar(2, adIcon(), click -> placeAd());
        if (services.mayOpenSettings(viewer)) {
            toolbar(6, Icons.of(Material.COMPARATOR, "<white>Server settings for chat",
                            "<gray>Format, mentions, filters, history, polls", "<gray>and the paid /ad with its switch and price.", "",
                            "<gray>Only people with the settings permission see this."),
                    click -> new SettingsMenu(viewer, services.brand(), services.chat(),
                            services.core().settingsNavigation(), "chat", this).open());
        }
    }

    /** What /ad costs, or why it cannot be used: greyed rather than hidden, so nobody asks where it went. */
    private ItemStack adIcon() {
        String price = services.ads().priceText();
        ItemStack icon = Icons.of(Material.GOLD_NUGGET, "<white>Place an ad",
                "<gray>A line everybody online reads, up to " + services.config().adLength() + " characters.",
                services.ads().enabled() ? (price.isEmpty() ? "<green>Free." : "<gold>Costs: <white>" + price)
                        : "<dark_gray>/ad is off on this server.",
                "", "<yellow>Click<gray> to type one, or use <white>/ad [message]");
        if (!services.ads().enabled()) {
            return Icons.locked(icon, "Ads are not for sale on this server.");
        }
        return viewer.hasPermission(PermissionNodes.AD) ? icon : Icons.locked(icon, "Needs " + PermissionNodes.AD);
    }

    private void placeAd() {
        if (!services.ads().enabled()) {
            services.messages().send(viewer, "chat.ad.off");
            return;
        }
        if (!viewer.hasPermission(PermissionNodes.AD)) {
            services.messages().send(viewer, "chat.no-permission");
            return;
        }
        String price = services.ads().priceText();
        AnvilInput.open(viewer, "Your ad", "", Parsers.text(services.config().adLength()), text ->
                new ConfirmMenu(viewer, services.brand(), this, "<dark_gray>Send this ad?",
                        java.util.List.of("<white>" + MINI.escapeTags(text),
                                price.isEmpty() ? "<gray>It is free." : "<gray>It costs <white>" + price + "<gray>."),
                        "<dark_gray>Nothing is charged if you say no.", () -> {
                    AdCommand.place(services, viewer, text);
                    viewer.closeInventory();
                }).open(), this::open);
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
