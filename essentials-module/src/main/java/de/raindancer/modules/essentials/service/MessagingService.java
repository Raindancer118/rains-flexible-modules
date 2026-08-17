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
            // The same wording — and the same refusal — as "they are not here". A vanished
            // moderator who can be messaged, or who replies with "you have been ignored" instead
            // of the ordinary "not here", has been given away exactly as much as one who is seen.
            messages.send(from, "essentials.msg.unreachable", "player", to.getName());
            return false;
        }
        chat.tell(from, messages.raw("essentials.msg.sent"),
                Chat.arg("player", to.getName()), Chat.arg("text", text));
        chat.tell(to, messages.raw("essentials.msg.received"),
                Chat.arg("player", from.getName()), Chat.arg("text", text));
        lastPartner.put(to.getUniqueId(), from.getUniqueId());
        lastPartner.put(from.getUniqueId(), to.getUniqueId());
        return true;
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
