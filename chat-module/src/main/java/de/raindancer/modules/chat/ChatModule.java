package de.raindancer.modules.chat;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.chat.listener.ChatListener;
import de.raindancer.modules.chat.service.ChatEffects;
import de.raindancer.modules.chat.service.ChatHistoryService;
import de.raindancer.modules.chat.service.ChatNetwork;
import de.raindancer.modules.chat.service.ChatQualityService;
import de.raindancer.modules.chat.service.ChatStyleService;
import de.raindancer.modules.chat.service.FormatService;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.MentionService;
import de.raindancer.modules.chat.service.NetworkMentions;
import de.raindancer.modules.chat.service.PrivateChatService;
import de.raindancer.modules.chat.store.ChatHistoryStore;
import de.raindancer.modules.chat.store.ChatStyleStore;
import de.raindancer.modules.chat.util.PermissionNodes;
import org.bukkit.Server;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

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

    private static final ModuleInfo INFO = ModuleInfo.of("chat", "Chat", "1.19.0")
            .describedAs("Chat format, @-mentions, a caps and repeat filter, a message cooldown, "
                    + "private chats, and /chat clear, freeze and slowmode")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<ChatSettings> settings;

    private ChatHistoryStore history;
    private de.raindancer.modules.chat.store.MentionInbox inbox;
    private ChatStyleStore styles;
    private ChatServices services;
    private de.raindancer.modules.chat.store.PanelFeed feed;

    /** What the Pelican panel's chat page shows. */
    private static final int PANEL_FEED_LINES = 100;
    private de.raindancer.modules.chat.service.NetworkChatService network;
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
        // The same lines said plainly, for a server whose Message tone is SERIOUS.
        context.core().messages().seriousFrom(ChatModule.class.getResourceAsStream("messages-serious.yml"));

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        styles = new ChatStyleStore(context.dataFolder());
        styles.load();
        // A colour is the player's on every server: the store is one network document, reloaded when another writes it.
        var stylesWatch = styles.watch();
        context.closeWith(stylesWatch::close);
        ChatStyleService styleService = new ChatStyleService(styles);

        FormatService format = new FormatService(context.chat(), context.core().identities(),
                styleService, settings.current());
        inbox = new de.raindancer.modules.chat.store.MentionInbox(context.dataFolder());
        inbox.load();
        var inboxWatch = inbox.watch();
        context.closeWith(inboxWatch::close);
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
        var historyWatch = history.watch();
        context.closeWith(historyWatch::close);
        ChatHistoryService chatHistory = new ChatHistoryService(history, settings.current());

        de.raindancer.modules.chat.service.PollService polls = new de.raindancer.modules.chat.service.PollService(
                context.plugin(), server, context.core().votes(), context.core().buttons(),
                context.core().messages(), settings.current());

        de.raindancer.modules.chat.service.AdService ads = new de.raindancer.modules.chat.service.AdService(
                quality, freeze, settings.current(), System::currentTimeMillis);

        feed = new de.raindancer.modules.chat.store.PanelFeed(context.dataFolder(), PANEL_FEED_LINES);
        feed.load();
        var feedWriting = de.raindancer.core.platform.util.Scheduling.asyncTimer(context.plugin(), 1, 1,
                task -> feed.flush());
        if (feedWriting != null) {
            context.closeWith(feedWriting::cancel);
        }
        var core = context.core();
        de.raindancer.modules.chat.service.SayAsService sayAs = new de.raindancer.modules.chat.service.SayAsService(
                server, format, mentions, chatHistory, feed,
                who -> core.punishments().isActive(who, de.raindancer.core.moderation.punishment.PunishmentKind.MUTE),
                core.chatLog(), core.audit(), System::currentTimeMillis);

        Predicate<UUID> mutedHere = who ->
                core.punishments().isActive(who, de.raindancer.core.moderation.punishment.PunishmentKind.MUTE);
        BooleanSupplier onNetwork = () -> core.network().isNetwork();

        // Everything below crosses to the other servers of a network and does nothing more than today on a server of its own.
        NetworkMentions networkMentions = new NetworkMentions(core::networkCalls,
                who -> core.networkPlayers().flatMap(players -> players.where(who, false))
                        .map(Presence.Here::server)
                        .filter(there -> core.networkPlayers().map(players -> !there.equals(players.self())).orElse(false)),
                mentions::pingHere);
        mentions.network(networkMentions);
        networkMentions.listen();
        context.closeWith(networkMentions::stop);

        ChatEffects effects = new ChatEffects(server, context.chat(), context.core().messages(), freeze, quality,
                format, who -> core.effects().playForAll(who, Cues.NOTIFY));
        ChatNetwork chatNetwork = new ChatNetwork(core.bus(), core::networkCalls, onNetwork,
                () -> core.networkPlayers().map(players -> players.servers().stream().map(NetworkServers.Server::id)
                        .filter(id -> !id.equals(players.self())).toList()).orElse(List.of()),
                mutedHere, effects, System::currentTimeMillis);
        chatNetwork.listen();
        chatNetwork.catchUp();
        context.closeWith(chatNetwork::stop);

        polls.network(core.bus(), core::networkCalls, onNetwork);
        context.closeWith(polls::stopNetwork);

        services = new ChatServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, format, mentions, quality, freeze, chatHistory, styleService,
                privateChat, polls, ads, sayAs, feed, chatNetwork);

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
            ads.settings(fresh);
        });

        var networkSettings = context.settings(NetworkChatSettings.class, NetworkChatSettings.DEFAULTS);
        network = new de.raindancer.modules.chat.service.NetworkChatService(core::link, core.bus(),
                onNetwork, mutedHere,
                server::sendMessage,
                (tag, line) -> context.chat().mm(context.core().messages().raw("chat.network.line"),
                        de.raindancer.core.ui.chat.Chat.arg("tag", tag),
                        de.raindancer.core.ui.chat.Chat.formatted("line", line)),
                networkSettings.current());
        networkSettings.onChange(network::settings);
        network.listen();
        context.closeWith(network::stop);

        context.listener(new ChatListener(services, network));
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
        if (feed != null) {
            feed.flush();
        }
        if (de.raindancer.core.RainsCore.isAvailable()) {
            de.raindancer.core.RainsCore.get().mentionCompletions().enable(false);
        }
        // The listener is unregistered by the context, in the reverse order it was registered.
    }
}
