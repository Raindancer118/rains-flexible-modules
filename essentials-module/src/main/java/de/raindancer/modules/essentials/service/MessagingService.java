package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.store.EssentialsStore;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private messages, who replies to whom, and who has shut a person out.
 *
 * <h2>Why "last spoke to" lives here and not in the store</h2>
 * It is session state, not a decision anybody wants remembered past a restart — a reply-to from
 * three days ago pointing at whoever last messaged them then is a message sent to the wrong person
 * with no warning it happened. The ignore list is the opposite: a decision made once and meant to
 * stick, which is exactly what {@link EssentialsStore} is for.
 */
public final class MessagingService implements IEssentialsService {

    private final EssentialsStore store;
    private final Messages messages;
    private final Chat chat;
    private final Vanish vanish;

    private final java.util.Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();
    /** Partners on the other server of the link, by id — their name is what a reply is addressed to over there. */
    private final java.util.Map<UUID, String> across = new ConcurrentHashMap<>();

    public static final String TOPIC = "essentials.msg";
    private volatile de.raindancer.core.platform.link.ServerLink link;

    private volatile EssentialsSettings settings;

    public MessagingService(EssentialsStore store, Messages messages, Chat chat, Vanish vanish,
                            EssentialsSettings settings) {
        this.store = store;
        this.messages = messages;
        this.chat = chat;
        this.vanish = vanish;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        this.settings = fresh;
    }

    /**
     * Sends one, or says why it did not go.
     *
     * @return whether it was delivered
     */
    public boolean send(Player from, Player to, String text) {
        if (from.equals(to)) {
            messages.send(from, "essentials.msg.not-yourself");
            return false;
        }
        if (store.isIgnoring(to.getUniqueId(), from.getUniqueId())
                || !vanish.canSee(from.getUniqueId(), to.getUniqueId())) {
            // The same wording as the "is offline" /msg gives for somebody who really is. A vanished
            // moderator who can be messaged, or who answers "you have been ignored" instead of the
            // ordinary "offline", has been given away exactly as much as one who is seen.
            messages.send(from, "essentials.player.offline", "player", to.getName());
            return false;
        }
        chat.tell(from, messages.raw("essentials.msg.sent"),
                Chat.arg("player", to.getName()), Chat.arg("text", text));
        chat.tell(to, messages.raw("essentials.msg.received"),
                Chat.arg("player", from.getName()), Chat.arg("text", text));
        lastPartner.put(to.getUniqueId(), from.getUniqueId());
        lastPartner.put(from.getUniqueId(), to.getUniqueId());
        across.remove(to.getUniqueId());
        across.remove(from.getUniqueId());
        return true;
    }

    /**
     * Listens for messages from the other server of the link. There, as here, the receiver's ignore list and whether
     * they are vanished decide — the sender is told "offline" either way.
     */
    public void connect(de.raindancer.core.platform.link.ServerLink link, java.util.function.Function<String, Player> byName) {
        this.link = link;
        link.on(TOPIC, data -> {
            Player to = byName.apply(data.getOrDefault("to", ""));
            UUID from;
            try {
                from = UUID.fromString(data.get("from"));
            } catch (RuntimeException garbled) {
                return java.util.Map.of("ok", "false");
            }
            String fromName = data.getOrDefault("fromName", "?");
            String text = data.getOrDefault("text", "");
            if (to == null || store.isIgnoring(to.getUniqueId(), from) || !vanish.canSee(from, to.getUniqueId())) {
                return java.util.Map.of("ok", "false");
            }
            chat.tell(to, messages.raw("essentials.msg.received"), Chat.arg("player", fromName), Chat.arg("text", text));
            lastPartner.put(to.getUniqueId(), from);
            across.put(from, fromName);
            return java.util.Map.of("ok", "true", "toId", to.getUniqueId().toString(), "to", to.getName());
        });
    }

    public void disconnect() {
        de.raindancer.core.platform.link.ServerLink current = link;
        if (current != null) {
            current.off(TOPIC);
        }
        link = null;
    }

    public boolean linked() {
        de.raindancer.core.platform.link.ServerLink current = link;
        return current != null && current.isConnected();
    }

    /** The name a reply to this partner goes to on the other server, when they were over there. */
    public Optional<String> acrossName(UUID partner) {
        return Optional.ofNullable(across.get(partner));
    }

    /** To somebody on the other server: the answer decides what the sender is told. */
    public void sendAcross(Player from, String toName, String text) {
        de.raindancer.core.platform.link.ServerLink current = link;
        if (current == null) {
            messages.send(from, "essentials.player.offline", "player", toName);
            return;
        }
        current.request(TOPIC, java.util.Map.of("from", from.getUniqueId().toString(), "fromName", from.getName(),
                        "to", toName, "text", text), java.time.Duration.ofSeconds(4))
                .whenComplete((answer, failure) -> {
                    if (failure != null || !"true".equals(answer.get("ok"))) {
                        messages.send(from, "essentials.player.offline", "player", toName);
                        return;
                    }
                    String name = answer.getOrDefault("to", toName);
                    chat.tell(from, messages.raw("essentials.msg.sent"), Chat.arg("player", name), Chat.arg("text", text));
                    try {
                        UUID partner = UUID.fromString(answer.get("toId"));
                        lastPartner.put(from.getUniqueId(), partner);
                        across.put(partner, name);
                    } catch (RuntimeException ignored) {
                        // An answer without an id still delivered; there is just nobody to /r to.
                    }
                });
    }

    /** Who a reply would go to, if anybody. */
    public Optional<UUID> replyTarget(UUID who) {
        return who == null ? Optional.empty() : Optional.ofNullable(lastPartner.get(who));
    }

    /**
     * Blocks somebody, or says they already were.
     *
     * @return whether this changed anything
     */
    public boolean ignore(Player who, UUID target) {
        boolean changed = store.ignore(who.getUniqueId(), target);
        store.flush();
        return changed;
    }

    public boolean stopIgnoring(Player who, UUID target) {
        boolean changed = store.stopIgnoring(who.getUniqueId(), target);
        store.flush();
        return changed;
    }

    public boolean isIgnoring(UUID who, UUID target) {
        return store.isIgnoring(who, target);
    }

    public void forget(UUID who) {
        lastPartner.remove(who);
        // Removed as a key only — who has blocked this player is kept, because leaving is not the
        // same as changing your mind about somebody. It is a value that may still point at them from
        // somebody else's entry, which is fine: replyTarget answers empty for an offline partner and
        // the command that would use it says so.
        lastPartner.values().remove(who);
    }

    @Override
    public String describe() {
        return "private messages, who replies to whom, and who has shut a person out";
    }
}
