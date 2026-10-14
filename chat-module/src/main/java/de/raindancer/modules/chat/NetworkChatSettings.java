package de.raindancer.modules.chat;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * One public chat across the servers of a network — over the network's bus, so every server hears it — or, on a
 * server not on one, across the two servers of Core's 1.x link. A record of its own: the chat settings are already
 * long, and this is a separate decision.
 */
@Settings(id = "chat-network", topics = {
        @Topic(path = "chat/network", title = "Network chat", icon = Material.ENDER_EYE),
})
public record NetworkChatSettings(

        @In("chat/network") @Title("Share chat with the other servers")
        @Describe("Public chat said here is shown on every other server of the network, and theirs here (on a "
                + "server of its own: on the server it is linked to, link.yml in RainsCore). Private chats and "
                + "channels stay where they are. A mute on a server hides what the muted player says there, "
                + "whichever server they said it on.")
        @Key("network.enabled")
        boolean enabled,

        @In("chat/network") @Title("This server's tag")
        @Describe("Shown in front of every line from this server on the others, e.g. SMP or Arena. Empty: the "
                + "server's id on a network.")
        @Key("network.tag")
        String tag) {

    public static final NetworkChatSettings DEFAULTS = new NetworkChatSettings(false, "");

    public String tagOrDefault() {
        return tag == null || tag.isBlank() ? "Network" : tag.strip();
    }
}
