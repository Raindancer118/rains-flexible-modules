package de.raindancer.modules.chat;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.chat.listener.ChatListener;
import de.raindancer.modules.chat.service.ChatHistoryService;
import de.raindancer.modules.chat.service.ChatQualityService;
import de.raindancer.modules.chat.service.ChatStyleService;
import de.raindancer.modules.chat.service.FormatService;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.MentionService;
import de.raindancer.modules.chat.service.PrivateChatService;
import de.raindancer.modules.chat.store.ChatHistoryStore;
import de.raindancer.modules.chat.store.ChatStyleStore;
import de.raindancer.modules.chat.util.PermissionNodes;
import org.bukkit.Server;

import java.util.List;

/**
 * Everything that happens to a line typed into public chat, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsChat}, a plugin of its own. Hosted
 * inside another plugin it is one feature among several.
 *
 * <h2>What is deliberately not here</h2>
 * A player's prefix, suffix and name colour are Core's own {@code Identities}, set by
 * essentials-module's {@code NicknameService} and read here rather than duplicated — see
 * {@link ChatSettings}'s own note. Private messages are essentials-module's {@code /msg}; a muted
 * player never reaches this module at all, refused by Core's own {@code PunishmentListener} before
 * {@link ChatListener} ever sees the line. What is left, once those are taken out, is what this
 * module is: the format a line is shown in, @-mentions, a quality filter, {@code /chathistory} for
 * catching up after being away, {@code /chat private} for a conversation among a chosen few, and the
 * staff tools to reach for when chat needs calming down.
 */
public final class ChatModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("chat", "Chat", "1.9.0")
            .describedAs("Chat format, @-mentions, a caps and repeat filter, a message cooldown, "
                    + "private chats, and /chat clear, freeze and slowmode")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<ChatSettings> settings;

    private ChatHistoryStore history;
    private de.raindancer.modules.chat.store.MentionInbox inbox;
    private ChatStyleStore styles;
    private ChatServices services;
    private java.util.function.BiConsumer<java.util.UUID, String> channelWatcher;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(ChatSettings.class, ChatSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written — never
        // Messages.load, which would throw away Core's own lines and every other module's with them.
        context.core().messages().defineFrom(
                ChatModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        styles = new ChatStyleStore(context.dataFolder());
        styles.load();
        ChatStyleService styleService = new ChatStyleService(styles);

        FormatService format = new FormatService(context.chat(), context.core().identities(),
                styleService, settings.current());
        inbox = new de.raindancer.modules.chat.store.MentionInbox(context.dataFolder());
        inbox.load();
        MentionService mentions = new MentionService(server, context.core().vanish(),
                context.core().messages(), settings.current(), inbox);
        var inboxSaving = de.raindancer.core.platform.util.Scheduling.asyncTimer(context.plugin(), 60, 60,
                task -> inbox.save());
        if (inboxSaving != null) {
            context.closeWith(inboxSaving::cancel);
        }
        ChatQualityService quality = new ChatQualityService(settings.current());
        FreezeService freeze = new FreezeService();
        PrivateChatService privateChat = new PrivateChatService();

        history = new ChatHistoryStore(context.dataFolder());
        history.load();
        ChatHistoryService chatHistory = new ChatHistoryService(history, settings.current());

        de.raindancer.modules.chat.service.PollService polls = new de.raindancer.modules.chat.service.PollService(
                context.plugin(), server, context.core().votes(), context.core().buttons(),
                context.core().messages(), settings.current());

        services = new ChatServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, format, mentions, quality, freeze, chatHistory, styleService,
                privateChat, polls);

        // @Name suggestions in the chat box — only while mentions are actually drawn.
        context.core().mentionCompletions().enable(settings.current().mentionsEnabled());
        settings.onChange(fresh -> {
            context.core().mentionCompletions().enable(fresh.mentionsEnabled());
            format.settings(fresh);
            mentions.settings(fresh);
            quality.settings(fresh);
            freeze.settings(fresh);
            chatHistory.settings(fresh);
            styleService.settings(fresh);
            privateChat.settings(fresh);
            polls.settings(fresh);
        });

        context.listener(new ChatListener(services));
        // From here on this module routes channel lines, so a module that could say its channel alone
        // (moderation's staff chat) stands down and a line is said once.
        de.raindancer.core.ui.chat.ChatChannels.claimRouting(this);
        // Whichever plugin's command chose a channel, a private chat steps aside for it.
        channelWatcher = privateChat::channelChosen;
        de.raindancer.core.ui.chat.ChatChannels.watch(channelWatcher);

        // The commands were registered during bootstrap, long before any of this existed, and have
        // been answering "not started yet" until now. See ChatCommands.
        ChatCommands.ready(services);

        log.info("Chat is up: format \"{}\", mentions {}, caps filter {}.",
                settings.current().format(), settings.current().mentionsEnabled() ? "on" : "off",
                settings.current().capsFilterEnabled() ? "on" : "off");
    }

    @Override
    public List<ModuleCommand> commands() {
        return ChatCommands.declared();
    }

    @Override
    public void disable() {
        de.raindancer.core.ui.chat.ChatChannels.releaseRouting(this);
        de.raindancer.core.ui.chat.ChatChannels.unwatch(channelWatcher);
        ChatCommands.stopped();
        if (history != null) {
            history.flush();
        }
        if (inbox != null) {
            inbox.save();
        }
        if (de.raindancer.core.RainsCore.isAvailable()) {
            de.raindancer.core.RainsCore.get().mentionCompletions().enable(false);
        }
        // The listener is unregistered by the context, in the reverse order it was registered.
    }
}
