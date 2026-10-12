package de.raindancer.modules.chat;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * One public chat across two servers of a network, over Core's server link. A record of its own: the chat settings
 * are already long, and this is a separate decision an owner makes once per server.
 */
@Settings(id = "chat-network", topics = {
        @Topic(path = "chat/network", title = "Network chat", icon = Material.ENDER_EYE),
})
public record NetworkChatSettings(

        @In("chat/network") @Title("Share chat with the linked server")
        @Describe("Public chat said here is shown on the server this one is linked to (link.yml in RainsCore), and "
                + "theirs here. Private chats and channels stay where they are. A mute on this server also hides "
                + "what the muted player says over there.")
        @Key("network.enabled")
        boolean enabled,

        @In("chat/network") @Title("This server's tag")
        @Describe("Shown in front of every line from this server on the other one, e.g. SMP or Arena.")
        @Key("network.tag")
        String tag) {

    public static final NetworkChatSettings DEFAULTS = new NetworkChatSettings(false, "");

    public String tagOrDefault() {
        return tag == null || tag.isBlank() ? "Network" : tag.strip();
    }
}
