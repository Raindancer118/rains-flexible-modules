package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Away from the keyboard, decided by silence rather than declared.
 *
 * <h2>Why a nametag prefix rather than a new idea of state</h2>
 * Core already keeps one prefix per player for exactly this — a moderator's rank, a vanish icon —
 * and {@link Identities} is what every plugin's own tag is drawn beside. A second "AFK flag" that
 * lived only here would be invisible to the tablist and the nametag both, and would need its own
 * arbitration the moment two plugins wanted to write the same prefix.
 *
 * <h2>Thread safety</h2>
 * {@link #activity} is called from movement, chat and command events, which can arrive on different
 * threads under Folia. Both maps are concurrent, and marking or unmarking somebody is a single
 * {@code computeIfAbsent}/{@code remove}, never a read followed by a separate write.
 */
public final class AfkService implements IEssentialsService {

    private static final String PREFIX = "<gray>[AFK] ";

    private final Identities identities;
    private final Messages messages;
    private final Chat chat;
    private final LongSupplier clock;

    private final java.util.Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    private volatile EssentialsSettings settings;

    public AfkService(Identities identities, Messages messages, Chat chat,
                      EssentialsSettings settings) {
        this(identities, messages, chat, System::currentTimeMillis, settings);
    }

    public AfkService(Identities identities, Messages messages, Chat chat, LongSupplier clock,
                      EssentialsSettings settings) {
        this.identities = identities;
        this.messages = messages;
        this.chat = chat;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        this.settings = fresh;
    }

    public boolean isAfk(UUID who) {
        return who != null && afk.contains(who);
    }

    /**
     * Something happened that counts as being here: a step, a word, a command.
     *
     * <p>Comes back from AFK the moment it is called, announced the same way going away was — a
     * player who moved because a mob pushed them still counts, and that is the right side to err on:
     * staying marked AFK after actually coming back is the annoying failure, not the reverse.
     */
    public void activity(Player who) {
        if (who == null) {
            return;
        }
        lastActivity.put(who.getUniqueId(), clock.getAsLong());
        if (afk.remove(who.getUniqueId())) {
            announce(who, false);
        }
    }

    /** Somebody typed {@code /afk} — toggles regardless of the timeout. */
    public void toggle(Player who) {
        if (who == null) {
            return;
        }
        if (afk.remove(who.getUniqueId())) {
            lastActivity.put(who.getUniqueId(), clock.getAsLong());
            announce(who, false);
        } else {
            afk.add(who.getUniqueId());
            announce(who, true);
        }
    }

    /**
     * Marks everybody who has been silent too long. Meant to be called on a timer.
     *
     * <p>Never unmarks anyone — that is only ever {@link #activity} or {@link #toggle}, so a check
     * that runs while nobody happens to be moving cannot flip a player back from AFK on its own.
     */
    public void sweep(Iterable<? extends Player> online) {
        if (!settings.afkEnabled()) {
            return;
        }
        long now = clock.getAsLong();
        long timeout = settings.afkTimeout() * 1000L;
        for (Player player : online) {
            UUID id = player.getUniqueId();
            if (afk.contains(id)) {
                continue;
            }
            long last = lastActivity.computeIfAbsent(id, ignored -> now);
            if (now - last >= timeout) {
                afk.add(id);
                announce(player, true);
            }
        }
    }

    private void announce(Player who, boolean nowAfk) {
        identities.setNametagPrefix(who.getUniqueId(), nowAfk ? PREFIX : "");
        if (settings.afkBroadcast()) {
            chat.broadcast(messages.raw(nowAfk ? "essentials.afk.went" : "essentials.afk.back"),
                    Chat.arg("player", who.getName()));
        } else {
            messages.send(who, nowAfk ? "essentials.afk.went-quiet" : "essentials.afk.back-quiet");
        }
    }

    public void forget(UUID who) {
        if (who == null) {
            return;
        }
        lastActivity.remove(who);
        afk.remove(who);
    }

    @Override
    public String describe() {
        return "away from the keyboard, decided by silence rather than declared";
    }
}
