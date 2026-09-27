package de.raindancer.modules.moderation.util;

import de.raindancer.core.ui.chat.ChatChannel;
import de.raindancer.modules.moderation.model.ModerationPermission;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Staff chat as one of Core's chat channels — {@code /chat staff} and RainsChat's channel picker reach
 * the same room {@code /staffchat} does. Moderation still says every line itself ({@link #deliver}), so
 * the staff format and the console record are the same whichever plugin routed it.
 *
 * <p>Asked from the async chat thread. Paper's online-player list is a concurrent copy and a permission
 * lookup is a map read, so nothing here needs a region thread.
 */
public final class StaffChannel implements ChatChannel {

    public static final String ID = "staff";

    private final Server server;
    private final BiConsumer<String, String> say;

    /** @param say speaker's name and the line, put in front of the staff and the console */
    public StaffChannel(Server server, BiConsumer<String, String> say) {
        this.server = Objects.requireNonNull(server, "server");
        this.say = Objects.requireNonNull(say, "say");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Staff";
    }

    @Override
    public Optional<Set<UUID>> audienceFor(UUID speaker) {
        Player talking = server.getPlayer(speaker);
        if (talking == null || !isStaff(talking)) {
            return Optional.empty();
        }
        Set<UUID> staff = new HashSet<>();
        for (Player online : server.getOnlinePlayers()) {
            if (isStaff(online)) {
                staff.add(online.getUniqueId());
            }
        }
        return Optional.of(staff);
    }

    @Override
    public boolean deliver(Player speaker, String text) {
        say.accept(speaker.getName(), text);
        return true;
    }

    private static boolean isStaff(Player player) {
        return player.hasPermission(ModerationPermission.STAFF_CHAT.node());
    }
}
