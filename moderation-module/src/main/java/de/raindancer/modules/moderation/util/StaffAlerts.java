package de.raindancer.modules.moderation.util;

import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.profile.PlayerSwitch;
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
import java.util.function.Supplier;

/**
 * A line for the staff online — on this server and, on a network, on every other: a report filed on one
 * server is as much the moderators' business on the next.
 *
 * <p>The other servers get the line and its placeholders, not a rendered message, and show it to whoever
 * holds the permission there (and has not opted out), with the name of the server it came from.
 */
public final class StaffAlerts {

    static final String TOPIC = "moderation-staff-alert";
    static final String ORIGIN_TAG = "origin-server";

    /**
     * @param permission what a player online has to hold to be told; empty for everybody online
     * @param exclude    never told, even holding the permission — the subject of a report; null for nobody
     * @param line       MiniMessage, with {@code <name>} placeholders — anything a player typed goes in
     *                   {@code args}, never in the line
     * @param args       the placeholders' values, as plain text
     * @param optOut     the name of a moderation {@link PlayerSwitch} each member of staff may turn off, checked
     *                   on the receiving server; null for none
     * @param bare       sent without the module's chat prefix
     * @param console    this server's console hears it too — only when it came from this server
     * @param origin     the server it came from, set on arrival; null for this server's own
     */
    public record Alert(String permission, UUID exclude, String line, Map<String, String> args, String optOut,
                        boolean bare, boolean console, String origin) {

        public Alert {
            args = Map.copyOf(args == null ? Map.of() : args);
        }

        public Alert(String permission, UUID exclude, String line, Map<String, String> args) {
            this(permission, exclude, line, args, null, false, false, null);
        }

        TagResolver[] resolvers() {
            List<TagResolver> resolvers = new ArrayList<>();
            args.forEach((name, value) -> resolvers.add(Chat.arg(name, value)));
            if (origin != null) {
                resolvers.add(Chat.arg(ORIGIN_TAG, origin));
            }
            return resolvers.toArray(TagResolver[]::new);
        }

        /** The line as this server shows it: one from another server says which. */
        String shownAs(String fromServer) {
            return origin == null ? line : fromServer + line;
        }

        String encode() {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("permission", permission);
            yaml.set("exclude", exclude == null ? null : exclude.toString());
            yaml.set("line", line);
            yaml.set("optOut", optOut);
            yaml.set("bare", bare);
            yaml.set("console", console);
            args.forEach((name, value) -> yaml.set("args." + name, value));
            return yaml.saveToString();
        }

        static Optional<Alert> decode(String payload, String origin) {
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
                String optOut = yaml.getString("optOut");
                return Optional.of(new Alert(permission, exclude == null ? null : UUID.fromString(exclude), line, args,
                        optOut, yaml.getBoolean("bare"), yaml.getBoolean("console"),
                        origin == null || origin.isBlank() ? "?" : origin));
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
        this.heard = this.bus.subscribe(TOPIC, event -> Alert.decode(event.payload(), event.server()).ifPresent(here));
    }

    /** Tells the staff here and everywhere. */
    public void tell(Alert alert) {
        here.accept(alert);
        bus.publish(TOPIC, alert.permission(), alert.encode());
    }

    public void close() {
        heard.close();
    }

    /**
     * Delivery to the players online: on the global region, where a server-wide list is safe to read — and, for an
     * alert with an opt-out, on each player's own thread, where their switch is safe to read.
     *
     * @param fromServer the template put in front of a line from another server, with {@code <origin-server>}
     */
    public static Consumer<Alert> toStaffOnline(Plugin plugin, Server server, Chat chat, Supplier<String> fromServer) {
        return alert -> {
            String line = alert.shownAs(fromServer.get());
            TagResolver[] resolvers = alert.resolvers();
            Scheduling.global(plugin, () -> {
                if (alert.console() && alert.origin() == null) {
                    server.getConsoleSender().sendMessage(chat.mm(line, resolvers));
                }
                List<Player> staff = whoToTell(server.getOnlinePlayers(), alert.exclude(), alert.permission());
                if (staff.isEmpty()) {
                    return;
                }
                if (alert.optOut() == null && !alert.bare()) {
                    chat.broadcast(staff, line, resolvers);
                    return;
                }
                for (Player who : staff) {
                    Scheduling.entity(plugin, who, () -> {
                        if (who.isOnline() && wants(plugin, who, alert.optOut())) {
                            chat.raw(who, alert.bare() ? chat.mm(line, resolvers) : chat.prefixed(line, resolvers));
                        }
                    });
                }
            });
        };
    }

    private static boolean wants(Plugin plugin, Player who, String optOut) {
        if (optOut == null) {
            return true;
        }
        return AttemptAlertSwitch.NAME.equals(optOut)
                ? new AttemptAlertSwitch(plugin).isOn(who)
                : new PlayerSwitch("moderation", optOut, true).isOn(who);
    }

    /**
     * Who actually gets told, out of everybody online — a plain list computation a test can call without
     * a server.
     */
    public static List<Player> whoToTell(Collection<? extends Player> online, UUID exclude, String permission) {
        List<Player> staff = new ArrayList<>();
        for (Player who : online) {
            if (!who.getUniqueId().equals(exclude) && (permission.isEmpty() || who.hasPermission(permission))) {
                staff.add(who);
            }
        }
        return staff;
    }
}
