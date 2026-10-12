package de.raindancer.modules.anticheat.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.AlertDigest;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Telling staff. A player's first flag at once, then one line per player every few seconds gathering
 * everything since ("Fly ×12, Speed ×4") — a hacker is one readable line, not a scrolling wall.
 */
public final class AlertService implements IAntiCheatService {

    public static final PlayerSwitch ALERTS = new PlayerSwitch("rainsanticheat", "alerts", true);

    private final Server server;
    private final Messages messages;
    private final LogChannel log;
    private final LongSupplier clock;
    private final AlertDigest digest;
    /** Whether each staff member wants alerts, read from their profile on join so no thread touches it later. */
    private final Map<UUID, Boolean> wants = new ConcurrentHashMap<>();
    private final Set<UUID> verbose = ConcurrentHashMap.newKeySet();

    public AlertService(Server server, Messages messages, LogChannel log, LongSupplier clock) {
        this.server = server;
        this.messages = messages;
        this.log = log;
        this.clock = clock;
        this.digest = new AlertDigest(clock);
    }

    /** A failed check past its alert level: the log hears every one, staff the first and then a line per window. */
    public void alert(Player subject, CheckType check, double level, String detail, int ping) {
        log.info("{} failed {} (level {}): {} [{} ms]", subject.getName(), check.title(),
                String.format(Locale.ROOT, "%.1f", level), detail, ping);
        digest.offer(subject.getUniqueId(), subject.getName(), check, level, detail, ping).ifPresent(line -> toStaff(
                messages.get(check.experimental() ? "anticheat.alert-experimental" : "anticheat.alert",
                        "player", subject.getName(), "check", check.title(), "level", String.format(Locale.ROOT, "%.1f", level),
                        "count", "", "detail", detail, "ping", ping,
                        // The description sits inside a quoted hover; a plain apostrophe would end it early.
                        "description", check.description().replace('\'', '’'), "alert-at", check.alertAt())));
    }

    /** What gathered since a player's first line: one line each. Called about once a second, from any thread. */
    public void flushDigests() {
        for (AlertDigest.Line line : digest.due()) {
            StringBuilder checks = new StringBuilder();
            line.counts().forEach((check, count) -> checks.append(checks.isEmpty() ? "" : ", ").append(check.title())
                    .append(count > 1 ? " ×" + count : ""));
            toStaff(messages.get("anticheat.alert-digest", "player", line.name(), "checks", checks.toString(),
                    "level", String.format(Locale.ROOT, "%.1f", line.level()), "detail", line.detail() == null ? "" : line.detail(),
                    "ping", line.ping()));
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
    }

    private void toStaff(Component line) {
        for (Player staff : server.getOnlinePlayers()) {
            if (wants.getOrDefault(staff.getUniqueId(), false) && staff.hasPermission(PermissionNodes.ALERTS)) {
                staff.sendMessage(line);
            }
        }
    }

    /** Read on join, on the player's own thread. */
    public void joined(Player player) {
        wants.put(player.getUniqueId(), ALERTS.isOn(player));
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
    public void settings(AntiCheatSettings settings) {
        // Whether to alert at all is the violation service's question; this only delivers.
    }

    @Override
    public String describe() {
        return "telling staff who failed what";
    }
}
