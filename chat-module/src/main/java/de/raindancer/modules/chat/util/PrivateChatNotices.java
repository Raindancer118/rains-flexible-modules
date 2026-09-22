package de.raindancer.modules.chat.util;

import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.model.PrivateChat;
import de.raindancer.modules.chat.service.PrivateChatService.Outcome;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Telling a private chat's members what just happened to it.
 *
 * <p>Shared by {@code /chat private} and the quit listener, because both end a chat or take somebody
 * out of one, and a member who is never told the chat closed keeps typing into public chat believing
 * they are talking to three people.
 */
public final class PrivateChatNotices {

    private PrivateChatNotices() {
    }

    /** One line to every online member, skipping {@code except} (who is usually told something else). */
    public static void tell(ChatServices services, Collection<UUID> members, UUID except, String key,
                            Object... values) {
        for (UUID member : members) {
            if (member.equals(except)) {
                continue;
            }
            Player online = services.server().getPlayer(member);
            if (online != null) {
                services.messages().send(online, key, values);
            }
        }
    }

    /** Somebody went offline: their chat is left, or — if it was theirs — closed for everybody. */
    public static void disconnected(ChatServices services, Player gone) {
        Optional<PrivateChat> before = services.privateChat().chatOf(gone.getUniqueId());
        Outcome outcome = services.privateChat().disconnect(gone.getUniqueId());
        if (before.isEmpty()) {
            return;
        }
        String key = outcome == Outcome.ENDED ? "chat.private.ended-offline" : "chat.private.went-offline";
        tell(services, before.get().members(), gone.getUniqueId(), key, "player", gone.getName());
    }
}
