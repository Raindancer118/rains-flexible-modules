package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkEvent;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.moderation.AntiCheatSettings;
import de.raindancer.modules.moderation.model.AlertDigest;
import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.util.CheatPermissions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Telling staff. A player's first flag at once, then one line per player every few seconds gathering
 * everything since ("Fly ×12, Speed ×4") — a hacker is one readable line, not a scrolling wall.
 *
 * <p>On a network every line a server tells its staff is told to the staff of the other servers too, over the bus,
 * with the name of the server the player is on in front. The other servers get the facts, not a rendered line, and
 * word it themselves for whoever holds the permission and has not muted the check.
 */
public final class AlertService implements IModerationService {

    public static final String TOPIC = "anticheat-alert";

    public static final PlayerSwitch ALERTS = new PlayerSwitch("rainsanticheat", "alerts", true);

    private final Server server;
    private final Messages messages;
    private final LogChannel log;
    private final LongSupplier clock;
    private final AlertDigest digest;
    private final NetworkBus bus;
    private final NetworkBus.Subscription heard;
    private volatile de.raindancer.modules.moderation.store.AlertMutes mutes;
    /** Whether each staff member wants alerts, read from their profile on join so no thread touches it later. */
    private final Map<UUID, Boolean> wants = new ConcurrentHashMap<>();
    private final Set<UUID> verbose = ConcurrentHashMap.newKeySet();

    public AlertService(Server server, Messages messages, LogChannel log, LongSupplier clock) {
        this(server, messages, log, clock, NetworkBus.NONE);
    }

    public AlertService(Server server, Messages messages, LogChannel log, LongSupplier clock, NetworkBus bus) {
        this.server = server;
        this.messages = messages;
        this.log = log;
        this.clock = clock;
        this.digest = new AlertDigest(clock);
        this.bus = bus == null ? NetworkBus.NONE : bus;
        this.heard = this.bus.subscribe(TOPIC, event -> {
            try {
                heard(event);
            } catch (RuntimeException failed) {
                log.warn("An alert from {} could not be shown: {}", event.server(), failed.getMessage());
            }
        });
    }

    public void close() {
        heard.close();
    }

    /** A failed check past its alert level: the log hears every one, staff the first and then a line per window. */
    public void alert(Player subject, CheckType check, double level, String detail, int ping) {
        log.info("{} failed {} (level {}): {} [{} ms]", subject.getName(), check.title(),
                String.format(Locale.ROOT, "%.1f", level), detail, ping);
        digest.offer(subject.getUniqueId(), subject.getName(), check, level, detail, ping).ifPresent(line -> {
            toStaff(check, alertLine(subject.getName(), check, level, detail, ping));
            YamlConfiguration payload = payload("alert");
            payload.set("check", check.key());
            payload.set("player", subject.getName());
            payload.set("level", level);
            payload.set("detail", detail);
            payload.set("ping", ping);
            publish(payload);
        });
    }

    private Component alertLine(String player, CheckType check, double level, String detail, int ping) {
        return messages.get("anticheat.alert",
                "player", player, "check", check.title(), "level", String.format(Locale.ROOT, "%.1f", level),
                "count", "", "detail", detail, "ping", ping,
                // The description sits inside a quoted hover; a plain apostrophe would end it early.
                "description", check.description().replace('\'', '’'), "alert-at", check.alertAt());
    }

    /** What gathered since a player's first line: one line each. Called about once a second, from any thread. */
    public void flushDigests() {
        for (AlertDigest.Line line : digest.due()) {
            deliver(line, null);
            YamlConfiguration payload = payload("digest");
            payload.set("player", line.name());
            payload.set("id", line.player().toString());
            payload.set("level", line.level());
            payload.set("detail", line.detail() == null ? "" : line.detail());
            payload.set("ping", line.ping());
            line.counts().forEach((check, count) -> payload.set("counts." + check.key(), count));
            publish(payload);
        }
    }

    /** One gathered line to the staff here; {@code from} names the server it came from, null for this one. */
    private void deliver(AlertDigest.Line line, Component from) {
        for (Player staff : server.getOnlinePlayers()) {
            if (!listens(staff)) {
                continue;
            }
            // Built for each reader: what they muted is left out, and nothing is sent if that was all of it.
            de.raindancer.modules.moderation.store.AlertMutes muted = mutes;
            java.util.Map<CheckType, Integer> counts = muted == null ? line.counts() : muted.visible(staff.getUniqueId(), line.counts());
            if (counts.isEmpty()) {
                continue;
            }
            StringBuilder checks = new StringBuilder();
            counts.forEach((check, count) -> checks.append(checks.isEmpty() ? "" : ", ").append(check.title())
                    .append(count > 1 ? " ×" + count : ""));
            Component text = messages.get("anticheat.alert-digest", "player", line.name(), "checks", checks.toString(),
                    "level", String.format(Locale.ROOT, "%.1f", line.level()), "detail", line.detail() == null ? "" : line.detail(),
                    "ping", line.ping());
            staff.sendMessage(from == null ? text : from.append(text));
        }
    }

    /** Every failure, unthrottled, to staff who asked for it with /anticheat verbose. */
    public void verbose(Player subject, CheckType check, double level, String detail) {
        if (verbose.isEmpty()) {
            return;
        }
        Component line = messages.get("anticheat.verbose", "player", subject.getName(), "check", check.title(),
                "level", String.format(Locale.ROOT, "%.2f", level), "detail", detail);
        for (UUID id : verbose) {
            Player staff = server.getPlayer(id);
            if (staff != null) {
                staff.sendMessage(line);
            }
        }
    }

    /** A line every alert recipient sees: a kick, a ban, a refused client. */
    public void staff(String key, Object... values) {
        Component line = messages.get(key, values);
        toStaff(line);
        log.info("{}", PlainTextComponentSerializer.plainText().serialize(line));
        YamlConfiguration payload = payload("staff");
        payload.set("key", key);
        List<String> texts = new java.util.ArrayList<>();
        for (Object value : values) {
            texts.add(String.valueOf(value));
        }
        payload.set("values", texts);
        publish(payload);
    }

    private void toStaff(Component line) {
        for (Player staff : server.getOnlinePlayers()) {
            if (listens(staff)) {
                staff.sendMessage(line);
            }
        }
    }

    private static YamlConfiguration payload(String kind) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("kind", kind);
        return yaml;
    }

    private void publish(YamlConfiguration payload) {
        bus.publish(TOPIC, payload.getString("kind", ""), payload.saveToString());
    }

    /** What another server told its staff: worded here, for the staff here, with that server's name in front. */
    private void heard(NetworkEvent event) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(event.payload());
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            return;
        }
        Component from = messages.get("anticheat.on-server", "server", event.server());
        switch (yaml.getString("kind", "")) {
            case "alert" -> {
                Optional<CheckType> check = CheckType.find(yaml.getString("check", ""));
                if (check.isPresent()) {
                    toStaff(check.get(), from.append(alertLine(yaml.getString("player", "?"), check.get(),
                            yaml.getDouble("level"), yaml.getString("detail", ""), yaml.getInt("ping"))));
                }
            }
            case "digest" -> {
                Map<CheckType, Integer> counts = new EnumMap<>(CheckType.class);
                var section = yaml.getConfigurationSection("counts");
                if (section != null) {
                    section.getKeys(false).forEach(key -> CheckType.find(key)
                            .ifPresent(check -> counts.put(check, section.getInt(key))));
                }
                if (!counts.isEmpty()) {
                    deliver(new AlertDigest.Line(idOf(yaml.getString("id", "")), yaml.getString("player", "?"), counts,
                            yaml.getDouble("level"), yaml.getString("detail", ""), yaml.getInt("ping"), null), from);
                }
            }
            case "staff" -> {
                String key = yaml.getString("key", "");
                if (key.startsWith("anticheat.")) {
                    toStaff(from.append(messages.get(key, yaml.getStringList("values").toArray())));
                }
            }
            default -> { }
        }
    }

    private static UUID idOf(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAnId) {
            return new UUID(0, 0);
        }
    }

    /** An alert about one check: only to staff who have not muted it, for themselves or for everybody. */
    private void toStaff(CheckType check, Component line) {
        de.raindancer.modules.moderation.store.AlertMutes muted = mutes;
        for (Player staff : server.getOnlinePlayers()) {
            if (listens(staff) && (muted == null || !muted.isMuted(staff.getUniqueId(), check))) {
                staff.sendMessage(line);
            }
        }
    }

    private boolean listens(Player staff) {
        return wants.getOrDefault(staff.getUniqueId(), false) && staff.hasPermission(CheatPermissions.ALERTS);
    }

    /** Where per-check mutes are kept; without one, nothing is muted. */
    public void mutesFrom(de.raindancer.modules.moderation.store.AlertMutes store) {
        this.mutes = store;
    }

    public de.raindancer.modules.moderation.store.AlertMutes mutes() {
        return mutes;
    }

    /** Read on join, on the player's own thread. */
    public void joined(Player player) {
        listening(player.getUniqueId(), ALERTS.isOn(player));
    }

    void listening(UUID staff, boolean on) {
        wants.put(staff, on);
    }

    /** @return whether alerts are now on for them */
    public boolean toggle(Player player) {
        boolean on = ALERTS.toggle(player);
        wants.put(player.getUniqueId(), on);
        return on;
    }

    /** @return whether verbose is now on for them */
    public boolean toggleVerbose(Player player) {
        if (verbose.remove(player.getUniqueId())) {
            return false;
        }
        verbose.add(player.getUniqueId());
        return true;
    }

    /** Measured afresh: what was gathering for their next line goes; their own alert settings, if staff, stay. */
    public void freshStart(UUID player) {
        digest.forget(player);
    }

    public void forget(UUID player) {
        wants.remove(player);
        verbose.remove(player);
        digest.forget(player);
    }

    @Override
    public void settings(ModerationSettings fresh) {
        settings(fresh.antiCheat());
    }

    public void settings(AntiCheatSettings settings) {
        // Whether to alert at all is the violation service's question; this only delivers.
    }

    @Override
    public String describe() {
        return "telling staff who failed what";
    }
}
