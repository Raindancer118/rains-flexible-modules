package de.raindancer.modules.chat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.chat.model.ChatStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has chosen a colour or a decoration for their own chat messages, and what.
 *
 * <h2>The shape on disk</h2>
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     color: gold
 *     bold: true
 *     italic: false
 *     underlined: false
 *     strikethrough: false
 * </pre>
 * A player who has never opened the picker, or who set everything back to none, is not written at
 * all — the same reason {@code TpaPrefsFile} leaves out anybody who has decided nothing: a server
 * this has run on for years keeps one entry per person who has ever touched it, not one per person
 * who has ever joined.
 *
 * <h2>On a network</h2>
 * One document for every server ({@code YamlStore.network}): a colour chosen anywhere is worn everywhere, and
 * nothing is carried between servers. {@link #watch()} reloads when another server writes; a choice this
 * server has not managed to write yet is kept across the reload, and a document that cannot be read leaves
 * what is held alone and is never written over.
 *
 * <h2>Thread safety</h2>
 * The map is concurrent because a style is read from the chat thread on every message and written
 * from whichever thread owns the picker's clicks. Persistence goes through {@link #load()} and
 * {@link #save()}, both meant to be called off the server's own threads — see {@code YamlStore}'s
 * own note.
 */
public final class ChatStyleStore {


    private final YamlStore store;
    private final Map<UUID, ChatStyle> styles = new ConcurrentHashMap<>();
    /** Chosen here and not yet written (DEFAULT for "none"): kept when a reload replaces the rest. */
    private final Map<UUID, ChatStyle> unwritten = new ConcurrentHashMap<>();

    /** False stops any write: a document that could not be read must never be overwritten by an
     * in-memory map that started empty. It is tried again before the next write. */
    private volatile boolean readable = true;

    public ChatStyleStore(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("chatstyles.yml")));
    }

    public ChatStyleStore(YamlStore store) {
        this.store = store;
    }

    /** Reads the document; a read that fails keeps what is held and stops writes until one succeeds. */
    public synchronized void load() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            readable = false;
            return;
        }
        readable = true;
        Map<UUID, ChatStyle> fresh = new java.util.HashMap<>();
        ConfigurationSection players = read.get().getConfigurationSection("players");
        if (players != null) {
            for (String rawId : players.getKeys(false)) {
                UUID who;
                try {
                    who = UUID.fromString(rawId);
                } catch (IllegalArgumentException notAnId) {
                    continue;   // one hand-edited line must not cost everybody else their style
                }
                ConfigurationSection theirs = players.getConfigurationSection(rawId);
                if (theirs == null) {
                    continue;
                }
                ChatStyle style = new ChatStyle(colorOf(theirs.getString("color")),
                        theirs.getBoolean("bold"), theirs.getBoolean("italic"),
                        theirs.getBoolean("underlined"), theirs.getBoolean("strikethrough"));
                if (!style.isDefault()) {
                    fresh.put(who, style);
                }
            }
        }
        unwritten.forEach((who, style) -> {
            if (style.isDefault()) {
                fresh.remove(who);
            } else {
                fresh.put(who, style);
            }
        });
        styles.keySet().retainAll(fresh.keySet());
        styles.putAll(fresh);
    }

    /** What this player has chosen, or {@link ChatStyle#DEFAULT} if they never have. */
    public ChatStyle of(UUID who) {
        return who == null ? ChatStyle.DEFAULT : styles.getOrDefault(who, ChatStyle.DEFAULT);
    }

    /** Remembers what somebody has chosen, and writes it out — on every change, the same reason
     * {@code TpaPrefsFile} does: a choice live now and gone after a restart is found again by
     * whoever made it, expecting it to have stuck. */
    public void set(UUID who, ChatStyle style) {
        if (who == null || style == null) {
            return;
        }
        if (style.isDefault()) {
            styles.remove(who);
        } else {
            styles.put(who, style);
        }
        unwritten.put(who, style);
        save();
    }

    public synchronized boolean save() {
        if (!readable) {
            load();
            if (!readable) {
                return false;
            }
        }
        if (unwritten.isEmpty()) {
            return true;
        }
        Map<UUID, ChatStyle> sent = Map.copyOf(unwritten);
        boolean written = store.write(yaml -> styles.forEach((who, style) -> {
            String base = "players." + who;
            if (style.color() != null) {
                yaml.set(base + ".color", NamedTextColor.NAMES.key(style.color()));
            }
            yaml.set(base + ".bold", style.bold());
            yaml.set(base + ".italic", style.italic());
            yaml.set(base + ".underlined", style.underlined());
            yaml.set(base + ".strikethrough", style.strikethrough());
        }));
        if (written) {
            sent.forEach(unwritten::remove);
            // The write merged what other servers wrote; the next must start from that, not from before it.
            load();
        }
        return written;
    }

    /** Reloads when another server writes the document, after writing what this one holds. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(() -> {
            synchronized (this) {
                save();
                load();
            }
        });
    }

    public boolean isReadable() {
        return readable;
    }

    public java.util.List<String> problems() {
        return store.problems();
    }

    /** How many players have chosen something, for the line in the log. */
    public int tracked() {
        return styles.size();
    }

    private static NamedTextColor colorOf(String named) {
        if (named == null || named.isBlank()) {
            return null;
        }
        NamedTextColor found = NamedTextColor.NAMES.value(named.toLowerCase(java.util.Locale.ROOT));
        return found;   // an unrecognised name — a hand-edited typo — is read as "none chosen"
    }
}
