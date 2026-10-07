package de.raindancer.modules.anticheat.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.anticheat.AntiCheatSettings;
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
 * Telling staff. One line per player and check per second at most; anything in between is counted
 * into the next line ("×4"), so a flying cheater is one readable line a second, not twenty.
 */
public final class AlertService implements IAntiCheatService {

    public static final long QUIET_MILLIS = 1000;
    public static final PlayerSwitch ALERTS = new PlayerSwitch("rainsanticheat", "alerts", true);

    private final Server server;
    private final Messages messages;
    private final LogChannel log;
    private final LongSupplier clock;
    private final Map<String, long[]> recent = new ConcurrentHashMap<>();
    /** Whether each staff member wants alerts, read from their profile on join so no thread touches it later. */
    private final Map<UUID, Boolean> wants = new ConcurrentHashMap<>();
    private final Set<UUID> verbose = ConcurrentHashMap.newKeySet();

    public AlertService(Server server, Messages messages, LogChannel log, LongSupplier clock) {
        this.server = server;
        this.messages = messages;
        this.log = log;
        this.clock = clock;
    }

    /** A failed check past its alert level. */
    public void alert(Player subject, CheckType check, double level, String detail, int ping) {
        String key = subject.getUniqueId() + ":" + check.key();
        long now = clock.getAsLong();
        long[] entry = recent.computeIfAbsent(key, ignored -> new long[]{0, 0});
        int count;
        synchronized (entry) {
            if (now - entry[0] < QUIET_MILLIS) {
                entry[1]++;
                return;
            }
            count = (int) entry[1] + 1;
            entry[0] = now;
            entry[1] = 0;
        }
        Component line = messages.get(check.experimental() ? "anticheat.alert-experimental" : "anticheat.alert",
                "player", subject.getName(), "check", check.title(), "level", String.format(Locale.ROOT, "%.1f", level),
                "count", count > 1 ? " ×" + count : "", "detail", detail, "ping", ping);
        toStaff(line);
        log.info("{} failed {}{} (level {}): {} [{} ms]", subject.getName(), check.title(),
                count > 1 ? " x" + count : "", String.format(Locale.ROOT, "%.1f", level), detail, ping);
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

    public void forget(UUID player) {
        wants.remove(player);
        verbose.remove(player);
        recent.keySet().removeIf(key -> key.startsWith(player.toString()));
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
