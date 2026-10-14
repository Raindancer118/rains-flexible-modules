package de.raindancer.modules.moderation.util;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A line for the staff online — on this server and, on a network, on every other: a report filed on one
 * server is as much the moderators' business on the next.
 *
 * <p>The other servers get the line and its placeholders, not a rendered message, and show it to whoever
 * holds the permission there.
 */
public final class StaffAlerts {

    static final String TOPIC = "moderation-staff-alert";

    /**
     * @param permission what a player online has to hold to be told
     * @param exclude    never told, even holding the permission — the subject of a report; null for nobody
     * @param line       MiniMessage, with {@code <name>} placeholders
     * @param args       the placeholders' values, as plain text
     */
    public record Alert(String permission, UUID exclude, String line, Map<String, String> args) {

        public Alert {
            args = Map.copyOf(args == null ? Map.of() : args);
        }

        TagResolver[] resolvers() {
            List<TagResolver> resolvers = new ArrayList<>();
            args.forEach((name, value) -> resolvers.add(Chat.arg(name, value)));
            return resolvers.toArray(TagResolver[]::new);
        }

        String encode() {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("permission", permission);
            yaml.set("exclude", exclude == null ? null : exclude.toString());
            yaml.set("line", line);
            args.forEach((name, value) -> yaml.set("args." + name, value));
            return yaml.saveToString();
        }

        static Optional<Alert> decode(String payload) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(payload);
                String permission = yaml.getString("permission");
                String line = yaml.getString("line");
                if (permission == null || line == null) {
                    return Optional.empty();
                }
                String exclude = yaml.getString("exclude");
                Map<String, String> args = new LinkedHashMap<>();
                var section = yaml.getConfigurationSection("args");
                if (section != null) {
                    section.getKeys(false).forEach(name -> args.put(name, section.getString(name, "")));
                }
                return Optional.of(new Alert(permission, exclude == null ? null : UUID.fromString(exclude), line, args));
            } catch (org.bukkit.configuration.InvalidConfigurationException | IllegalArgumentException broken) {
                return Optional.empty();
            }
        }
    }

    private final NetworkBus bus;
    private final Consumer<Alert> here;
    private final NetworkBus.Subscription heard;

    /**
     * @param here tells the staff on this server; called for this server's own alerts and for the other
     *             servers' (on the bus's thread, so it hops to the right scheduler itself)
     */
    public StaffAlerts(NetworkBus bus, Consumer<Alert> here) {
        this.bus = bus == null ? NetworkBus.NONE : bus;
        this.here = here;
        this.heard = this.bus.subscribe(TOPIC, event -> Alert.decode(event.payload()).ifPresent(here));
    }

    /** Tells the staff here and everywhere. */
    public void tell(Alert alert) {
        here.accept(alert);
        bus.publish(TOPIC, alert.permission(), alert.encode());
    }

    public void close() {
        heard.close();
    }

    /** Delivery to the players online: on the global region, where a server-wide list is safe to read. */
    public static Consumer<Alert> toStaffOnline(Plugin plugin, Server server, Chat chat) {
        return alert -> Scheduling.global(plugin, () -> {
            List<Player> staff = whoToTell(server.getOnlinePlayers(), alert.exclude(), alert.permission());
            if (!staff.isEmpty()) {
                chat.broadcast(staff, alert.line(), alert.resolvers());
            }
        });
    }

    /**
     * Who actually gets told, out of everybody online — a plain list computation a test can call without
     * a server.
     */
    public static List<Player> whoToTell(Collection<? extends Player> online, UUID exclude, String permission) {
        List<Player> staff = new ArrayList<>();
        for (Player who : online) {
            if (!who.getUniqueId().equals(exclude) && who.hasPermission(permission)) {
                staff.add(who);
            }
        }
        return staff;
    }
}
