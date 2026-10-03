package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.visual.Navigator;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.tracker.TrailPreference;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@code /manhunt here}: says where you are, with the coordinates as a button — clicking it walks the
 * clicker there with Core's {@link Navigator} (trail and distance). Everybody online is told — asked
 * for that way; team-only is what team chat is for.
 */
public final class PositionShare {

    private static final Duration CLICKABLE_FOR = Duration.ofMinutes(15);

    private final Plugin plugin;
    private final ChatButtons buttons;
    private final Messages messages;
    private final Navigator navigator;
    private final Supplier<ManhuntSettings> settings;

    public PositionShare(Plugin plugin, ChatButtons buttons, Messages messages, Navigator navigator,
                         Supplier<ManhuntSettings> settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.buttons = Objects.requireNonNull(buttons, "buttons");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public static String dimension(World.Environment environment) {
        return switch (environment) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }

    /** Tells everybody online. @return how many were told */
    public int share(Player speaker) {
        Location at = speaker.getLocation().clone();
        String coordinates = at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
        String label = speaker.getName() + "'s spot";
        Component button = buttons.label("<aqua>[" + coordinates + "]")
                .tooltip("<gray>Click to be shown the way there.")
                .repeatable()
                .expiringIn(CLICKABLE_FOR)
                .does(clicker -> {
                    Player who = plugin.getServer().getPlayer(clicker);
                    if (who != null) {
                        navigator.navigate(who, at, "manhunt", label,
                                player -> TrailPreference.shows(player, settings.get()));
                    }
                })
                .render();
        Component line = messages.prefixed("manhunt.here.shared", "player", speaker.getName(),
                        "world", dimension(at.getWorld() == null ? World.Environment.NORMAL
                                : at.getWorld().getEnvironment()))
                .append(Component.space()).append(button);
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();
        online.forEach(reader -> reader.sendMessage(line));
        return online.size();
    }

    /** Stops the clicker's navigation — {@code /manhunt here stop}. */
    public boolean stop(Player player) {
        return navigator.stop(player);
    }

    public void stopAll() {
        navigator.stopAll();
    }
}
