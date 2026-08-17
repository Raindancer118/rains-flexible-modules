package de.raindancer.modules.chat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.chat.model.ChatStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.Map;
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
 * <h2>Thread safety</h2>
 * The map is concurrent because a style is read from the chat thread on every message and written
 * from whichever thread owns the picker's clicks. Persistence goes through {@link #load()} and
 * {@link #save()}, both meant to be called off the server's own threads — see {@code YamlStore}'s
 * own note.
 */
public final class ChatStyleStore {

    private static final LogChannel log = Log.of("chat");

    private final YamlStore store;
    private final Map<UUID, ChatStyle> styles = new ConcurrentHashMap<>();

    /** False stops any write, the same guard {@code TpaPrefsFile} uses for the same reason: a file
     * that could not be parsed must never be overwritten by an in-memory map that started empty. */
    private volatile boolean readable = true;

    public ChatStyleStore(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("chatstyles.yml"));
    }

    public void load() {
        styles.clear();
        readable = true;
        if (!store.exists()) {
            return;
        }
        ConfigurationSection players = store.read().getConfigurationSection("players");
        if (!store.problems().isEmpty()) {
            readable = false;
            return;
        }
        if (players == null) {
            return;
        }
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
            ChatStyle read = new ChatStyle(colorOf(theirs.getString("color")),
                    theirs.getBoolean("bold"), theirs.getBoolean("italic"),
                    theirs.getBoolean("underlined"), theirs.getBoolean("strikethrough"));
            if (!read.isDefault()) {
                styles.put(who, read);
            }
        }
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
        save();
    }

    public boolean save() {
        if (!readable) {
            return false;
        }
        return store.write(yaml -> styles.forEach((who, style) -> {
            String base = "players." + who;
            if (style.color() != null) {
                yaml.set(base + ".color", NamedTextColor.NAMES.key(style.color()));
            }
            yaml.set(base + ".bold", style.bold());
            yaml.set(base + ".italic", style.italic());
            yaml.set(base + ".underlined", style.underlined());
            yaml.set(base + ".strikethrough", style.strikethrough());
        }));
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
