package de.raindancer.modules.chat;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.service.ChatHistoryService;
import de.raindancer.modules.chat.service.ChatQualityService;
import de.raindancer.modules.chat.service.ChatStyleService;
import de.raindancer.modules.chat.service.FormatService;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.MentionService;
import de.raindancer.modules.chat.service.PrivateChatService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener or a command can be handed what it
 * needs.
 *
 * <p>Data, not a god object — a record of collaborators built once by the module and passed down. A
 * test builds one with fakes in the fields it cares about.
 *
 * @param settings behind a supplier rather than captured: a reload replaces the snapshot wholesale
 */
public record ChatServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<ChatSettings> settings,

        FormatService format,
        MentionService mentions,
        ChatQualityService quality,
        FreezeService freeze,
        ChatHistoryService history,
        ChatStyleService styles,
        PrivateChatService privateChat,
        de.raindancer.modules.chat.service.PollService polls,
        de.raindancer.modules.chat.service.AdService ads,
        de.raindancer.modules.chat.service.SayAsService sayAs,
        de.raindancer.modules.chat.store.PanelFeed feed,
        de.raindancer.modules.chat.service.ChatNetwork network) {

    /** Without the network side, for a test that never announces or controls chat. */
    public ChatServices(Plugin plugin, Server server, RainsCore core, LogChannel log, Messages messages,
                        Chat chat, Brand brand, Supplier<ChatSettings> settings, FormatService format,
                        MentionService mentions, ChatQualityService quality, FreezeService freeze,
                        ChatHistoryService history, ChatStyleService styles, PrivateChatService privateChat,
                        de.raindancer.modules.chat.service.PollService polls,
                        de.raindancer.modules.chat.service.AdService ads,
                        de.raindancer.modules.chat.service.SayAsService sayAs,
                        de.raindancer.modules.chat.store.PanelFeed feed) {
        this(plugin, server, core, log, messages, chat, brand, settings, format, mentions, quality, freeze,
                history, styles, privateChat, polls, ads, sayAs, feed, null);
    }

    /** Without paid ads, for a test that never places one. */
    public ChatServices(Plugin plugin, Server server, RainsCore core, LogChannel log, Messages messages,
                        Chat chat, Brand brand, Supplier<ChatSettings> settings, FormatService format,
                        MentionService mentions, ChatQualityService quality, FreezeService freeze,
                        ChatHistoryService history, ChatStyleService styles, PrivateChatService privateChat,
                        de.raindancer.modules.chat.service.PollService polls) {
        this(plugin, server, core, log, messages, chat, brand, settings, format, mentions, quality, freeze,
                history, styles, privateChat, polls, null);
    }

    public ChatServices(Plugin plugin, Server server, RainsCore core, LogChannel log, Messages messages,
                        Chat chat, Brand brand, Supplier<ChatSettings> settings, FormatService format,
                        MentionService mentions, ChatQualityService quality, FreezeService freeze,
                        ChatHistoryService history, ChatStyleService styles, PrivateChatService privateChat,
                        de.raindancer.modules.chat.service.PollService polls,
                        de.raindancer.modules.chat.service.AdService ads) {
        this(plugin, server, core, log, messages, chat, brand, settings, format, mentions, quality, freeze,
                history, styles, privateChat, polls, ads, null, null);
    }

    /** Who sees the doors to Core's /settings pages. */
    public boolean mayOpenSettings(org.bukkit.entity.Player who) {
        return who.hasPermission("rainscore.settings");
    }

    /** The settings as they are right now. */
    public ChatSettings config() {
        return settings.get();
    }
}
