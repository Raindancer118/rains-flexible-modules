package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.store.ChatHistoryStore;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * "What did I miss" — recording chat while somebody is away, and answering {@code /chathistory}.
 *
 * <p>Everything that decides <em>whether</em> and <em>how much</em> is here; {@link ChatHistoryStore}
 * only remembers lines and timestamps and knows nothing about settings.
 */
public final class ChatHistoryService implements IChatService {

    private final ChatHistoryStore store;
    private final LongSupplier clock;

    private volatile ChatSettings settings;

    public ChatHistoryService(ChatHistoryStore store, ChatSettings settings) {
        this(store, settings, System::currentTimeMillis);
    }

    /** For a test that wants to control time rather than race a real clock. */
    ChatHistoryService(ChatHistoryStore store, ChatSettings settings, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(ChatSettings fresh) {
        this.settings = fresh;
        store.capacity(fresh.historyLimit());
    }

    /** Remembers a line that just went out, if history is switched on at all. */
    public void record(UUID sender, String senderName, String text) {
        if (settings.historyEnabled()) {
            store.record(sender, senderName, text, clock.getAsLong());
        }
    }

    /** Everything said since this player was last seen leaving — empty if history is off, or there is nothing. */
    public List<ChatHistoryStore.Line> missedBy(UUID player) {
        if (!settings.historyEnabled()) {
            return List.of();
        }
        return store.lastQuit(player).map(store::linesAfter).orElse(List.of());
    }

    /** The most recent lines overall, regardless of who was online for them. */
    public List<ChatHistoryStore.Line> recent(int count) {
        return settings.historyEnabled() ? store.lastLines(count) : List.of();
    }

    /**
     * Marks this moment as when the player left — the point {@link #missedBy} measures from next
     * time they join — and writes the log to disk, piggybacking the write on an event that already
     * happens far less often than a chat message does.
     */
    public void markLeft(UUID player) {
        store.markQuit(player, clock.getAsLong());
        store.flush();
    }

    public boolean historyEnabled() {
        return settings.historyEnabled();
    }

    public boolean notifyOnJoin() {
        return settings.historyEnabled() && settings.historyNotifyOnJoin();
    }

    @Override
    public String describe() {
        return "recent public chat, and who missed how much of it";
    }
}
