package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.service.SideChat.Audience;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;
import java.util.UUID;

/**
 * Side chat, done by narrowing an {@link AsyncChatEvent}'s viewers rather than by cancelling it and
 * sending the message again.
 *
 * <h2>Why viewers, and not cancel-and-resend</h2>
 * Cancelling means rebuilding the whole line by hand — the format, the display name, whatever another
 * plugin had added to it — and every plugin downstream of the chat event stops seeing the message at
 * all, including the ones a server runs for logging or moderation. {@link AsyncChatEvent#viewers()}
 * is the mutable set of who will receive it, and removing from it says exactly the one thing this
 * module means: the same message, fewer people. The console stays in the set on purpose, so a
 * server's own log is still complete.
 *
 * <h2>Async, and what that costs</h2>
 * This event fires off the main thread. Everything asked here is either an immutable settings record
 * or a {@code ConcurrentHashMap}-backed team lookup, and nothing touches the world — which is why
 * this handler is allowed to answer inline instead of hopping threads and making the chat wait.
 */
public final class ManhuntChatListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ManhuntService manhunt;
    private final SideChat sideChat;
    private final Messages messages;

    public ManhuntChatListener(ManhuntService manhunt, SideChat sideChat, Messages messages) {
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.sideChat = Objects.requireNonNull(sideChat, "sideChat");
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player speaker = event.getPlayer();
        UUID id = speaker.getUniqueId();
        boolean runner = manhunt.teams().isRunner(id);
        boolean hunter = manhunt.teams().isHunter(id);

        String text = PLAIN.serialize(event.message());
        Audience audience = sideChat.audienceFor(text, manhunt.isRunning(), runner || hunter);
        if (audience == Audience.EVERYBODY) {
            stripPrefixIfAny(event, text);
            return;
        }

        event.viewers().removeIf(viewer -> viewer instanceof Player watcher
                && !onSameSide(watcher.getUniqueId(), runner));
    }

    /** A message that used the prefix is shown without it — see {@link SideChat#strip}. */
    private void stripPrefixIfAny(AsyncChatEvent event, String text) {
        String stripped = sideChat.strip(text);
        if (!stripped.equals(text)) {
            event.message(Component.text(stripped));
        }
    }

    private boolean onSameSide(UUID viewer, boolean speakerIsRunner) {
        return speakerIsRunner ? manhunt.teams().isRunner(viewer) : manhunt.teams().isHunter(viewer);
    }

    public String describe() {
        return "side chat: while a hunt runs, your own side hears you";
    }
}
