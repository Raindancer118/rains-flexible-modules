package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.store.Positions;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Proximity mode's front door: a linked user joins the lobby voice channel, gets a private channel
 * with a bot of their own, and stands in the world as their player until either side leaves.
 */
public final class LobbyService implements IVoiceBridgeService {

    /** Private channels carry this in front of the player's name, which is also how orphans are found. */
    static final String CHANNEL_PREFIX = "🎧 ";

    private static final List<Permission> USE = List.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK);

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final LogChannel log;
    private final LinkService links;
    private final BotPool pool;
    private final Supplier<Optional<VoicechatServerApi>> voicechat;
    private final Positions positions;
    private final Map<Long, ProximityLine> byDiscord = new ConcurrentHashMap<>();
    private final Map<UUID, ProximityLine> byPlayer = new ConcurrentHashMap<>();
    private final Events events = new Events();

    private volatile VoiceBridgeSettings settings;
    private volatile JDA main;
    private volatile String guildId = "";

    public LobbyService(Plugin plugin, Server server, Messages messages, LogChannel log, LinkService links,
                        BotPool pool, Supplier<Optional<VoicechatServerApi>> voicechat, Positions positions,
                        VoiceBridgeSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.log = log;
        this.links = links;
        this.pool = pool;
        this.voicechat = voicechat;
        this.positions = positions;
        this.settings = settings;
    }

    @Override
    public void settings(VoiceBridgeSettings next) {
        settings = next;
        byDiscord.values().forEach(line -> line.settings(next));
    }

    /** The main bot is logged in: listen, offer /link, tidy up after a crash, pick up who is waiting. */
    public void attach(JDA jda, String guild) {
        detach();
        main = jda;
        guildId = guild;
        jda.addEventListener(events);
        Guild server = jda.getGuildById(guild);
        if (server == null) {
            return;
        }
        server.updateCommands().addCommands(
                Commands.slash("link", "Link your Minecraft player, for proximity voice chat")
                        .addOption(OptionType.STRING, "code", "The code /voicebridge link showed you in game", true),
                Commands.slash("unlink", "Unlink your Minecraft player")).queue(
                ok -> { }, failed -> log.warn("Could not offer /link on Discord: {}", failed.getMessage()));
        if (!settings.activeLobby().isEmpty()) {
            removeOrphans(server);
            VoiceChannel lobby = server.getVoiceChannelById(settings.activeLobby());
            if (lobby != null) {
                lobby.getMembers().forEach(this::tryOpen);
            }
        }
    }

    public void detach() {
        JDA was = main;
        main = null;
        if (was != null) {
            was.removeEventListener(events);
        }
        for (ProximityLine line : List.copyOf(byDiscord.values())) {
            end(line, false);
        }
    }

    /** A player came online: if their Discord account is already waiting in the lobby, carry them in. */
    public void playerJoined(UUID player) {
        Optional<Long> discord = links.discordOf(player);
        Guild guild = guild();
        if (discord.isEmpty() || guild == null || settings.activeLobby().isEmpty()) {
            return;
        }
        Member member = guild.getMemberById(discord.get());
        AudioChannel in = member == null || member.getVoiceState() == null ? null : member.getVoiceState().getChannel();
        if (in != null && in.getId().equals(settings.activeLobby())) {
            tryOpen(member);
        }
    }

    /** A player left the game: their Discord user goes back to the lobby, the private channel goes. */
    public void playerLeft(UUID player) {
        positions.forget(player);
        ProximityLine line = byPlayer.get(player);
        if (line != null) {
            end(line, true);
        }
    }

    /** Somebody unlinked: whatever line they had is over. */
    public void unlinked(UUID player) {
        ProximityLine line = byPlayer.get(player);
        if (line != null) {
            end(line, true);
        }
    }

    public boolean onDiscord(UUID player) {
        return byPlayer.containsKey(player);
    }

    public int linesInUse() {
        return byDiscord.size();
    }

    private Guild guild() {
        JDA jda = main;
        return jda == null || guildId.isEmpty() ? null : jda.getGuildById(guildId);
    }

    private void tryOpen(Member member) {
        if (!settings.proximityEnabled() || member.getUser().isBot() || byDiscord.containsKey(member.getIdLong())) {
            return;
        }
        Optional<UUID> linked = links.playerOf(member.getIdLong());
        if (linked.isEmpty()) {
            dm(member, "voicebridge.proximity.dm.not-linked");
            return;
        }
        Player player = server.getPlayer(linked.get());
        if (player == null) {
            dm(member, "voicebridge.proximity.dm.not-online");
            return;
        }
        Optional<VoicechatServerApi> api = voicechat.get();
        VoicechatConnection connection = api.map(live -> live.getConnectionOf(linked.get())).orElse(null);
        if (api.isEmpty() || connection == null) {
            dm(member, "voicebridge.proximity.dm.not-ready");
            return;
        }
        if (connection.isInstalled()) {
            dm(member, "voicebridge.proximity.dm.has-mod");
            return;
        }
        // Speaking is checked by SVC on every packet; hearing through a listener is not, so here.
        if (!player.hasPermission("voicechat.listen")) {
            dm(member, "voicebridge.proximity.dm.no-permission");
            return;
        }
        Optional<BotPool.LineBot> claimed = pool.claim();
        if (claimed.isEmpty()) {
            dm(member, "voicebridge.proximity.dm.no-free-bot");
            return;
        }
        BotPool.LineBot bot = claimed.get();
        Guild guild = member.getGuild();
        Category category = categoryFor(guild);
        guild.createVoiceChannel(CHANNEL_PREFIX + player.getName(), category)
                .addPermissionOverride(guild.getPublicRole(), List.of(), List.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                .addMemberPermissionOverride(member.getIdLong(), USE, List.of())
                .addMemberPermissionOverride(bot.id(), USE, List.of())
                .addMemberPermissionOverride(guild.getSelfMember().getIdLong(),
                        List.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_MOVE_OTHERS,
                                Permission.MANAGE_CHANNEL), List.of())
                .queue(channel -> guild.moveVoiceMember(member, channel).queue(
                                moved -> whenBotSees(bot, channel.getId(), 25,
                                        view -> start(member, player.getUniqueId(), bot, api.get(), view, channel)),
                                failed -> {
                                    log.warn("Could not move {} into their channel: {}", member.getEffectiveName(), failed.getMessage());
                                    pool.release(bot);
                                    channel.delete().queue();
                                }),
                        failed -> {
                            log.warn("Could not make a private voice channel (Manage Channels?): {}", failed.getMessage());
                            pool.release(bot);
                            dm(member, "voicebridge.proximity.dm.failed");
                        });
    }

    private Category categoryFor(Guild guild) {
        String wanted = settings.categoryOrEmpty();
        Category category = wanted.isEmpty() ? null : guild.getCategoryById(wanted);
        if (category == null) {
            VoiceChannel lobby = guild.getVoiceChannelById(settings.activeLobby());
            category = lobby == null ? null : lobby.getParentCategory();
        }
        return category;
    }

    /** A brand-new channel reaches the other bot's cache a moment later; wait for it, briefly. */
    private void whenBotSees(BotPool.LineBot bot, String channelId, int triesLeft,
                             java.util.function.Consumer<AudioChannel> then) {
        AudioChannel view = bot.jda().getChannelById(AudioChannel.class, channelId);
        if (view != null) {
            then.accept(view);
            return;
        }
        if (triesLeft <= 0) {
            log.warn("Proximity bot never saw channel {}.", channelId);
            pool.release(bot);
            return;
        }
        Scheduling.asyncLater(plugin, 200, TimeUnit.MILLISECONDS, () -> whenBotSees(bot, channelId, triesLeft - 1, then));
    }

    private void start(Member member, UUID player, BotPool.LineBot bot, VoicechatServerApi api, AudioChannel view,
                       VoiceChannel channel) {
        ProximityLine line = new ProximityLine(player, member.getIdLong(), bot, api, positions, log, settings);
        if (!line.open(view)) {
            pool.release(bot);
            channel.delete().queue();
            dm(member, "voicebridge.proximity.dm.failed");
            return;
        }
        byDiscord.put(member.getIdLong(), line);
        byPlayer.put(player, line);
        tell(player, "voicebridge.proximity.connected");
        log.info("{} is in proximity chat through Discord.", member.getEffectiveName());
    }

    private void end(ProximityLine line, boolean backToLobby) {
        if (byDiscord.remove(line.discordUser(), line)) {
            byPlayer.remove(line.player(), line);
        } else {
            return;
        }
        line.close();
        pool.release(line.bot());
        tell(line.player(), "voicebridge.proximity.disconnected");
        Guild guild = guild();
        VoiceChannel channel = guild == null ? null : guild.getVoiceChannelById(line.channelId());
        if (channel == null) {
            return;
        }
        Member member = guild.getMemberById(line.discordUser());
        VoiceChannel lobby = guild.getVoiceChannelById(settings.activeLobby());
        boolean stillThere = member != null && member.getVoiceState() != null
                && member.getVoiceState().getChannel() != null
                && member.getVoiceState().getChannel().getId().equals(channel.getId());
        if (backToLobby && stillThere && lobby != null) {
            guild.moveVoiceMember(member, lobby).queue(moved -> channel.delete().queue(), failed -> channel.delete().queue());
        } else {
            channel.delete().queue();
        }
    }

    /** Private channels left behind by a crash: ours by name and category, and empty. */
    private void removeOrphans(Guild guild) {
        Category category = categoryFor(guild);
        List<VoiceChannel> candidates = category == null ? guild.getVoiceChannels() : category.getVoiceChannels();
        for (VoiceChannel channel : candidates) {
            if (channel.getName().startsWith(CHANNEL_PREFIX) && channel.getMembers().isEmpty()
                    && !channel.getId().equals(settings.activeLobby())) {
                channel.delete().queue(ok -> { }, failed -> { });
            }
        }
    }

    private void dm(Member member, String key) {
        String text = PlainTextComponentSerializer.plainText().serialize(messages.get(key));
        member.getUser().openPrivateChannel().flatMap(channel -> channel.sendMessage(text))
                .queue(ok -> { }, closedDms -> { });
    }

    private void tell(UUID player, String key, Object... values) {
        Player online = server.getPlayer(player);
        if (online != null) {
            Scheduling.entity(plugin, online, () -> messages.send(online, key, values));
        }
    }

    private final class Events extends ListenerAdapter {

        @Override
        public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
            if (!event.getGuild().getId().equals(guildId) || pool.isLineBot(event.getMember().getIdLong())) {
                return;
            }
            ProximityLine line = byDiscord.get(event.getMember().getIdLong());
            if (line != null && event.getChannelLeft() != null && event.getChannelLeft().getId().equals(line.channelId())) {
                end(line, false);
            }
            String lobby = settings.activeLobby();
            if (!lobby.isEmpty() && event.getChannelJoined() != null && event.getChannelJoined().getId().equals(lobby)) {
                tryOpen(event.getMember());
            }
        }

        @Override
        public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
            if (event.getGuild() == null || !event.getGuild().getId().equals(guildId)) {
                return;
            }
            long user = event.getUser().getIdLong();
            switch (event.getName()) {
                case "link" -> {
                    String code = event.getOption("code") == null ? "" : event.getOption("code").getAsString();
                    Optional<UUID> player = links.redeem(code, user);
                    if (player.isEmpty()) {
                        event.reply(plain("voicebridge.link.discord-wrong")).setEphemeral(true).queue();
                        return;
                    }
                    String name = server.getOfflinePlayer(player.get()).getName();
                    event.reply(plain("voicebridge.link.discord-done", "player", name == null ? "?" : name))
                            .setEphemeral(true).queue();
                    tell(player.get(), "voicebridge.link.linked", "account", event.getUser().getName());
                }
                case "unlink" -> {
                    Optional<UUID> player = links.playerOf(user);
                    player.ifPresent(uuid -> {
                        unlinked(uuid);
                        links.unlink(uuid);
                    });
                    event.reply(plain(player.isPresent() ? "voicebridge.link.discord-unlinked" : "voicebridge.link.discord-not-linked"))
                            .setEphemeral(true).queue();
                }
                default -> {
                }
            }
        }

        private String plain(String key, Object... values) {
            return PlainTextComponentSerializer.plainText().serialize(messages.get(key, values));
        }
    }
}
