package de.raindancer.modules.chat.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatSettings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @-mentions: turning {@code @Name} in ordinary chat into a ping the named player cannot miss.
 *
 * <h2>Why matching is against online names only</h2>
 * An offline player cannot be pinged — there is nobody to notify — and matching against every name
 * the server has ever seen would make every chat line a lookup over years of history for nothing.
 * Somebody typing {@code @Alex} while Alex is offline gets silence back; the moment Alex is online
 * for it to matter, {@code @Alex} matches.
 *
 * <h2>Why a vanished player is never matched</h2>
 * {@link Vanish#canSee} decides whether the token is a mention at all, not just whether the ping
 * goes out. Letting {@code @ModName} notify a hidden moderator would still tell everybody reading
 * the sender's own reaction that the name meant somebody real — the same leak essentials-module's
 * own {@code MessagingService} already refuses for {@code /msg}.
 *
 * <h2>Matching runs on the chat thread, on purpose</h2>
 * {@link ChatListener} needs the answer before it can build the rendered line — {@code
 * AsyncChatEvent}'s renderer has to be set before the handler returns, so there is no later tick to
 * defer this to. Core's own {@code Chat} javadoc says building and sending a component needs no
 * region thread; the one Bukkit call this makes, {@code Server#getPlayerExact}, is a single lookup
 * into the already-loaded online-player list, not a walk over the world.
 */
public final class MentionService implements IChatService {

    /** {@code @} then a run of characters a Minecraft name is made of. */
    private static final Pattern TOKEN = Pattern.compile("@([A-Za-z0-9_]{1,16})");

    /**
     * By key rather than {@code org.bukkit.Sound}'s own enum — that one resolves through Paper's
     * registry the moment its class loads, which a unit test never has running. A {@link Key} is
     * just a string; nothing here needs a live server to be tested.
     */
    private static final Key PING_SOUND = Key.key("entity.experience_orb.pickup");

    private final Server server;
    private final Vanish vanish;
    private final Messages messages;

    private volatile ChatSettings settings;

    public MentionService(Server server, Vanish vanish, Messages messages, ChatSettings settings) {
        this.server = server;
        this.vanish = vanish;
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(ChatSettings fresh) {
        this.settings = fresh;
    }

    /** Everybody named in this line the sender can actually see, in the order they appear, once each. */
    public List<Player> mentionsIn(Player sender, String plainText) {
        List<Player> found = new ArrayList<>();
        if (!settings.mentionsEnabled() || sender == null || plainText == null || plainText.isBlank()) {
            return found;
        }
        Set<UUID> seen = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(plainText);
        while (matcher.find()) {
            Player mentioned = server.getPlayerExact(matcher.group(1));
            if (mentioned == null || mentioned.equals(sender)) {
                continue;
            }
            if (!vanish.canSee(sender.getUniqueId(), mentioned.getUniqueId())) {
                continue;
            }
            if (seen.add(mentioned.getUniqueId())) {
                found.add(mentioned);
            }
        }
        return found;
    }

    /** Pings everybody this line mentions — a sound and a message naming who sent it and what it said. */
    public void notifyMentioned(Player sender, String plainText, List<Player> mentioned) {
        for (Player who : mentioned) {
            who.playSound(Sound.sound(PING_SOUND, Sound.Source.PLAYER, 0.7f, 1.4f));
            messages.send(who, "chat.mention.pinged", "player", sender.getName(), "text", plainText);
        }
    }

    @Override
    public String describe() {
        return "@-mentions in chat: pinging whoever a message names, with a sound and a note";
    }
}
