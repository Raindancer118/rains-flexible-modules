package de.raindancer.modules.chat.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.chatlog.ChatLog;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.Mention;
import de.raindancer.modules.chat.store.PanelFeed;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * {@code /chat say <player> <message>} from the console (the Pelican panel's chat page): a public line in a
 * player's name, online or not, rendered exactly like one they typed.
 *
 * <p>Not through {@link Player#chat}: that needs them online and follows their private chat or team channel.
 * The freeze and the quality filters are staff's to skip; a mute is not. The chat log keeps it under
 * {@link #CHANNEL} and the audit log says it came from the console, so moderation never takes it for
 * something the player typed.
 */
public final class SayAsService implements IChatService {

    public static final String CHANNEL = "panel";

    /** Vanilla's own limit for a chat line. */
    private static final int MOST_LENGTH = 256;

    public enum Said { SAID, MUTED, EMPTY }

    private final Server server;
    private final FormatService format;
    private final MentionService mentions;
    private final ChatHistoryService history;
    private final PanelFeed feed;
    private final Predicate<UUID> muted;
    private final ChatLog chatLog;
    private final Audit audit;
    private final LongSupplier clock;

    public SayAsService(Server server, FormatService format, MentionService mentions, ChatHistoryService history,
                        PanelFeed feed, Predicate<UUID> muted, ChatLog chatLog, Audit audit, LongSupplier clock) {
        this.server = server;
        this.format = format;
        this.mentions = mentions;
        this.history = history;
        this.feed = feed;
        this.muted = muted;
        this.chatLog = chatLog;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void settings(ChatSettings settings) {
    }

    public Said say(OfflinePlayer who, String typed) {
        String text = clean(typed);
        if (text.isEmpty()) {
            return Said.EMPTY;
        }
        if (muted.test(who.getUniqueId())) {
            return Said.MUTED;
        }
        String name = who.getName();
        List<Mention> mentioned = mentions.find(who, text);
        Component line = format.renderLine(who, text, mentioned);
        for (Player online : server.getOnlinePlayers()) {
            online.sendMessage(line);
        }
        server.getConsoleSender().sendMessage(line);

        history.record(who.getUniqueId(), name, text);
        mentions.notify(who, text, mentioned);
        if (feed != null) {
            feed.add(clock.getAsLong(), who.getUniqueId(), name, text, line, true);
        }
        if (chatLog != null) {
            chatLog.record(who.getUniqueId(), name, CHANNEL, text);
        }
        if (audit != null) {
            audit.record(AuditEntry.of("chat", "said-as").by(null, "the console").to(who.getUniqueId(), name)
                    .saying(text));
        }
        return Said.SAID;
    }

    static String clean(String typed) {
        if (typed == null) {
            return "";
        }
        String text = typed.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        return text.length() > MOST_LENGTH ? text.substring(0, MOST_LENGTH) : text;
    }

    @Override
    public String describe() {
        return "public lines said from the console in a player's name";
    }
}
