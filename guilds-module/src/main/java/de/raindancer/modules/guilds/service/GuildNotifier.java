package de.raindancer.modules.guilds.service;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import de.raindancer.core.ui.chat.ChatButtons;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Telling people things: a guild's online members, those of them holding a permission, one player — wherever on
 * the network they are. A player on another server is told through Core's {@code NetworkPlayers}; what travels is
 * the message key and its placeholders as plain text, said there in that server's own words.
 */
public final class GuildNotifier implements IGuildsService {

    private static final String ANNOUNCE = "guilds-announce";
    private static final char SEPARATOR = '\u001F';
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Server server;
    private final Messages messages;
    private final Supplier<ChatButtons> buttons;
    private volatile NetworkBus bus = NetworkBus.NONE;
    private volatile Supplier<Optional<NetworkPlayers>> network = Optional::empty;

    public GuildNotifier(Server server, Messages messages) {
        this(server, messages, () -> null);
    }

    /** @param buttons Core's chat buttons; null from the supplier when there are none (tests) */
    public GuildNotifier(Server server, Messages messages, Supplier<ChatButtons> buttons) {
        this.server = server;
        this.messages = messages;
        this.buttons = buttons;
    }

    /** Reaches players on the network's other servers too, and says what the other servers announce. Close to stop. */
    public NetworkBus.Subscription network(NetworkBus shared, Supplier<Optional<NetworkPlayers>> everyone) {
        this.bus = shared == null ? NetworkBus.NONE : shared;
        this.network = everyone == null ? Optional::empty : everyone;
        return bus.subscribe(ANNOUNCE, event -> {
            String[] parts = event.payload().split(String.valueOf(SEPARATOR), -1);
            Object[] values = Arrays.copyOfRange(parts, 1, parts.length);
            server.getOnlinePlayers().forEach(player -> messages.send(player, parts[0], values));
        });
    }

    private static String plain(Object value) {
        return value instanceof ComponentLike component ? PLAIN.serialize(component.asComponent()) : String.valueOf(value);
    }

    private static String[] strings(Object[] values) {
        return Arrays.stream(values).map(GuildNotifier::plain).toArray(String[]::new);
    }

    /**
     * A yes-or-no question in chat, if they are online: the line, then [Accept] [Deny]. The answer runs once —
     * Core takes both buttons away on the first click.
     */
    public void ask(UUID player, Duration validFor, Consumer<UUID> yes, Consumer<UUID> no, String key, Object... values) {
        Optional<Player> found = online(player);
        ChatButtons made = buttons.get();
        if (found.isEmpty()) {
            // Online elsewhere they get the line without its buttons: the answer is a command or the menu.
            network.get().ifPresent(players -> players.tell(player, key, strings(values)));
            return;
        }
        Component line = messages.prefixed(key, values);
        if (made != null) {
            line = line.append(Component.space()).append(made.ask(player, validFor, yes, no));
        }
        found.get().sendMessage(line);
    }

    @Override
    public void settings(GuildsSettings settings) {
        // Nothing read from the settings: who is told is the guild's business, how it reads is messages.yml.
    }

    public Messages messages() {
        return messages;
    }

    public Optional<Player> online(UUID player) {
        Player found = player == null ? null : server.getPlayer(player);
        return Optional.ofNullable(found);
    }

    /** A player's name as shown, online or not. */
    public String name(UUID player) {
        if (player == null) {
            return "?";
        }
        return PlayerTargets.shownName(server.getOfflinePlayer(player));
    }

    /** The guild's tag, in its colour — inserted as a component, never parsed. */
    public static Component tag(Guild guild) {
        if (!guild.tagStyle().isEmpty()) {
            return de.raindancer.core.ui.text.Gradients.styled("[" + guild.tag() + "]", guild.tagStyle());
        }
        return Component.text("[" + guild.tag() + "]", guild.colour().textColour());
    }

    /** The guild's name, in its colour, bold. */
    public static Component title(Guild guild) {
        if (!guild.nameStyle().isEmpty()) {
            return Component.text(guild.emblem().prefix(), guild.colour().textColour()).append(
                    de.raindancer.core.ui.text.Gradients.styled(guild.name(), guild.nameStyle()).decorate(TextDecoration.BOLD));
        }
        return Component.text(guild.emblem().prefix() + guild.name(), guild.colour().textColour())
                .decorate(TextDecoration.BOLD);
    }

    public void tell(UUID player, String key, Object... values) {
        Optional<Player> found = online(player);
        if (found.isPresent()) {
            messages.send(found.get(), key, values);
            return;
        }
        if (player != null) {
            network.get().ifPresent(players -> players.tell(player, key, strings(values)));
        }
    }

    public void tell(UUID player, Component line) {
        online(player).ifPresent(found -> found.sendMessage(line));
    }

    public void members(Guild guild, String key, Object... values) {
        members(guild, member -> true, key, values);
    }

    public void members(Guild guild, Predicate<UUID> who, String key, Object... values) {
        for (UUID member : guild.members().keySet()) {
            if (who.test(member)) {
                tell(member, key, values);
            }
        }
    }

    public void holding(Guild guild, GuildPermission permission, String key, Object... values) {
        members(guild, member -> guild.may(member, permission), key, values);
    }

    /** Everybody on this server and on every other of the network. */
    public void everybody(String key, Object... values) {
        for (Player player : server.getOnlinePlayers()) {
            messages.send(player, key, values);
        }
        StringBuilder payload = new StringBuilder(key);
        for (String value : strings(values)) {
            payload.append(SEPARATOR).append(value.replace(SEPARATOR, ' '));
        }
        bus.publish(ANNOUNCE, key, payload.toString());
    }
}
