package de.raindancer.modules.twitch.service;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.twitch.TwitchSettings;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.model.StreamInfo;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Says it in chat: on this server, and on every other server of the network through the link. The server that watches
 * Twitch sends what happened; each server words it itself from its own messages, so a banner reads the same everywhere.
 */
public final class Announcer {

    public static final String TOPIC = "twitch.announce";

    private final Plugin plugin;
    private final Server server;
    private final LogChannel log;
    private final Messages messages;
    private final RainsCore core;
    private final Supplier<TwitchSettings> settings;

    public Announcer(Plugin plugin, Server server, LogChannel log, Messages messages, RainsCore core,
                     Supplier<TwitchSettings> settings) {
        this.plugin = plugin;
        this.server = server;
        this.log = log;
        this.messages = messages;
        this.core = core;
        this.settings = settings;
    }

    /** Listens for what the watching server sends, on every link. */
    public void listen() {
        core.links().all().values().forEach(link -> link.on(TOPIC, data -> {
            show(data);
            return null;
        }));
    }

    public void stop() {
        core.links().all().values().forEach(link -> link.off(TOPIC));
    }

    /** Tells this server and every linked one. */
    public void announce(StreamEvent event) {
        Map<String, String> data = encode(event);
        show(data);
        for (ServerLink link : core.links().all().values()) {
            link.send(TOPIC, data);
        }
    }

    private Map<String, String> encode(StreamEvent event) {
        Map<String, String> data = new HashMap<>();
        StreamInfo stream = event instanceof StreamEvent.WentLive live ? live.stream() : ((StreamEvent.Ended) event).stream();
        data.put("kind", event instanceof StreamEvent.WentLive ? "live" : "ended");
        data.put("name", stream.channel());
        data.put("title", stream.title());
        data.put("game", stream.game());
        data.put("url", settings.get().url());
        data.put("channel", settings.get().channelClean());
        if (event instanceof StreamEvent.Ended ended) {
            data.put("lasted", StreamParser.spoken(ended.lasted()));
        }
        return data;
    }

    /** Words one announcement for everybody here. Safe to call from any thread. */
    void show(Map<String, String> data) {
        TwitchSettings live = settings.get();
        if (!live.enabled()) {
            return;
        }
        boolean going = "live".equals(data.get("kind"));
        String name = data.getOrDefault("name", live.channelClean());
        String title = data.getOrDefault("title", "");
        String game = data.getOrDefault("game", "");
        String url = data.getOrDefault("url", live.url());
        String channel = data.getOrDefault("channel", live.channelClean());
        log.info("Twitch announcement: {} {}.", name, going ? "is live" : "is over");
        for (Player player : server.getOnlinePlayers()) {
            Scheduling.entity(plugin, player, () -> {
                player.sendMessage(Component.empty());
                if (going) {
                    messages.sendPlain(player, "twitch.live.rule");
                    messages.sendPlain(player, "twitch.live.head");
                    messages.sendPlain(player, "twitch.live.who", "name", name);
                    if (!title.isBlank() && !game.isBlank()) {
                        messages.sendPlain(player, "twitch.live.what", "title", title, "game", game);
                    } else if (!title.isBlank()) {
                        messages.sendPlain(player, "twitch.live.what-title", "title", title);
                    } else if (!game.isBlank()) {
                        messages.sendPlain(player, "twitch.live.what-game", "game", game);
                    }
                    player.sendMessage(opening(messages.get("twitch.live.watch"), url));
                    messages.sendPlain(player, "twitch.live.rule");
                } else {
                    messages.sendPlain(player, "twitch.ended.rule");
                    messages.sendPlain(player, "twitch.ended.head");
                    messages.sendPlain(player, "twitch.ended.who", "name", name,
                            "lasted", data.getOrDefault("lasted", ""));
                    player.sendMessage(opening(messages.get("twitch.ended.thanks", "channel", channel), url));
                    messages.sendPlain(player, "twitch.ended.rule");
                }
                player.sendMessage(Component.empty());
                if (live.sound()) {
                    player.playSound(Sound.sound(going ? org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE.key()
                            : org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME.key(), Sound.Source.MASTER, 0.7f,
                            going ? 1.0f : 1.2f));
                }
            });
        }
    }

    /** A line that opens the channel when it is clicked — set here rather than in the wording, which cannot hold a link. */
    private static Component opening(Component line, String url) {
        return line.clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("Open " + url, net.kyori.adventure.text.format.NamedTextColor.GRAY)));
    }

    /** What a test says: a made-up stream, to see the banner. */
    public StreamInfo sample() {
        return new StreamInfo("test", settings.get().channelClean().isEmpty() ? "Streamer"
                : settings.get().channelClean(), "Building the castle — come hang out", "Minecraft",
                java.time.Instant.now(), 42);
    }

    public Duration sampleLength() {
        return Duration.ofMinutes(135);
    }
}
