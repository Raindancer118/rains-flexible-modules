package de.raindancer.modules.twitch.service;

import de.raindancer.core.content.advancement.AdvancementDef;
import de.raindancer.core.content.advancement.AdvancementFrame;
import de.raindancer.core.content.advancement.CustomAdvancements;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.twitch.TwitchSettings;
import de.raindancer.modules.twitch.model.ViewerTracker;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Twitch advancement tab: watching the stream from the game. What earns what is {@link ViewerTracker}'s; this
 * defines the tab, feeds the tracker and grants on each player's own thread.
 */
public final class TwitchAdvancements {

    private static final String PLUGIN = "rainstwitch";
    private static final String ROOT = PLUGIN + ":root";
    private static final int REGULAR_STREAMS = 10;
    /** The last stream counted towards Regular, on the player, so a rejoin or another server does not count it twice. */
    private static final NamespacedKey LAST_STREAM = new NamespacedKey(PLUGIN, "last_stream");

    private final Plugin plugin;
    private final Server server;
    private final CustomAdvancements advancements;
    private final Supplier<TwitchSettings> settings;
    private final ViewerTracker tracker = new ViewerTracker();

    public TwitchAdvancements(Plugin plugin, Server server, CustomAdvancements advancements,
                              Supplier<TwitchSettings> settings) {
        this.plugin = plugin;
        this.server = server;
        this.advancements = advancements;
        this.settings = settings;
    }

    public void define() {
        advancements.define(AdvancementDef.builder(PLUGIN, "root").icon(Material.AMETHYST_CLUSTER)
                .title("<light_purple>On Stream").description("Be on the server while the stream is live")
                .background("minecraft:gui/advancements/backgrounds/end").build());
        child(ViewerTracker.TUNED_IN, "root", Material.NOTE_BLOCK, "Tuned In",
                "Be online when the stream goes live", AdvancementFrame.TASK, 1);
        child(ViewerTracker.HYPE_TRAIN, ViewerTracker.TUNED_IN, Material.MINECART, "Hype Train",
                "Join within five minutes of the stream going live", AdvancementFrame.TASK, 1);
        child(ViewerTracker.DAY_ONE, ViewerTracker.TUNED_IN, Material.CLOCK, "Day One",
                "Stay online from the start of a stream to its end", AdvancementFrame.GOAL, 1);
        child(ViewerTracker.REGULAR, "root", Material.CAKE, "Regular",
                "Be online during " + REGULAR_STREAMS + " different streams", AdvancementFrame.CHALLENGE, REGULAR_STREAMS);
        child(ViewerTracker.AFTERPARTY, "root", Material.FIREWORK_ROCKET, "Afterparty",
                "Still be online when the stream ends", AdvancementFrame.TASK, 1);
        child(ViewerTracker.CAUGHT_ON_STREAM, "root", Material.SPYGLASS, "Caught on Stream",
                "Spend ten minutes near the streamer while live", AdvancementFrame.GOAL, 1);
        child(ViewerTracker.CLIP_THAT, ViewerTracker.CAUGHT_ON_STREAM, Material.SKELETON_SKULL, "Clip That!",
                "Die near the streamer while live", AdvancementFrame.TASK, 1);
    }

    private void child(String id, String parent, Material icon, String title, String description,
                       AdvancementFrame frame, int steps) {
        advancements.define(AdvancementDef.builder(PLUGIN, id).parent(parent).icon(icon).title(title)
                .description(description).frame(frame).steps(steps).build());
    }

    /** An announcement reached this server. */
    public void heard(Map<String, String> data) {
        if ("true".equals(data.get("test"))) {
            return;
        }
        Set<UUID> online = new HashSet<>();
        server.getOnlinePlayers().forEach(player -> online.add(player.getUniqueId()));
        if ("live".equals(data.get("kind"))) {
            long started = 0;
            try {
                started = Long.parseLong(data.getOrDefault("started", "0"));
            } catch (NumberFormatException unreadable) {
                started = 0;
            }
            grant(tracker.live(data.get("stream"), started, online, System.currentTimeMillis()));
        } else if ("ended".equals(data.get("kind"))) {
            grant(tracker.ended(online));
        }
    }

    public void joined(Player player) {
        grant(tracker.joined(player.getUniqueId(), System.currentTimeMillis()));
    }

    public void quit(Player player) {
        tracker.quit(player.getUniqueId());
    }

    /** The streamer, if they are on this server and named in the settings. */
    private Player streamer() {
        String name = settings.get().streamerName();
        return name == null || name.isBlank() ? null : server.getPlayerExact(name.strip());
    }

    public void died(Player player) {
        Player streamer = streamer();
        if (streamer == null || streamer.equals(player) || !tracker.isLive()) {
            return;
        }
        if (near(streamer, player)) {
            grant(tracker.diedNear(player.getUniqueId()));
        }
    }

    private boolean near(Player streamer, Player other) {
        int blocks = settings.get().nearBlocks();
        return streamer.getWorld().equals(other.getWorld())
                && streamer.getLocation().distanceSquared(other.getLocation()) <= (double) blocks * blocks;
    }

    /** Once a minute: who is near the streamer, read on the streamer's own thread. */
    public void minute() {
        Player streamer = streamer();
        if (streamer == null || !tracker.isLive()) {
            return;
        }
        Scheduling.entity(plugin, streamer, () -> {
            Set<UUID> near = new HashSet<>();
            streamer.getLocation().getNearbyPlayers(settings.get().nearBlocks()).forEach(player -> {
                if (!player.equals(streamer)) {
                    near.add(player.getUniqueId());
                }
            });
            grant(tracker.minuteNear(near));
        });
    }

    private void grant(List<ViewerTracker.Award> awards) {
        String stream = tracker.stream();
        for (ViewerTracker.Award award : awards) {
            Player player = server.getPlayer(award.player());
            if (player == null) {
                continue;
            }
            Scheduling.entity(plugin, player, () -> {
                advancements.award(player.getUniqueId(), ROOT);
                String key = PLUGIN + ":" + award.advancement();
                if (!award.advancement().equals(ViewerTracker.REGULAR)) {
                    advancements.award(player.getUniqueId(), key);
                    return;
                }
                String seen = player.getPersistentDataContainer().get(LAST_STREAM, PersistentDataType.STRING);
                if (stream == null || stream.equals(seen)) {
                    return;
                }
                player.getPersistentDataContainer().set(LAST_STREAM, PersistentDataType.STRING, stream);
                advancements.progress(player.getUniqueId(), key, advancements.stepsDone(player.getUniqueId(), key) + 1);
            });
        }
    }
}
