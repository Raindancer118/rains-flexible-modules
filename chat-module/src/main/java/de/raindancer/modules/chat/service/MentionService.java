package de.raindancer.modules.chat.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.Mention;
import de.raindancer.modules.chat.store.MentionInbox;
import org.bukkit.OfflinePlayer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @-mentions: turning {@code @Name} in ordinary chat into a ping the named player cannot miss.
 *
 * <h2>Offline players are mentioned too</h2>
 * A name or nickname of somebody offline is still a mention: drawn like one, not pinged, and kept in a
 * {@link MentionInbox} they are shown when they are back. Looking it up is Core's {@code PlayerLookup},
 * which reads the server's player cache and the nickname directory — memory, not years of history.
 *
 * <h2>A vanished player is exactly an offline one</h2>
 * {@link Vanish#canSee} decides whether the ping goes out <em>now</em>. Somebody hidden from the sender is
 * treated precisely as if they were offline — drawn the same, told later — because anything different
 * would tell everybody reading the line that the name meant somebody who is here.
 *
 * <h2>Matching runs on the chat thread, on purpose</h2>
 * {@link ChatListener} needs the answer before it can build the rendered line — {@code
 * AsyncChatEvent}'s renderer has to be set before the handler returns, so there is no later tick to
 * defer this to. Core's own {@code Chat} javadoc says building and sending a component needs no
 * region thread; the one Bukkit call this makes, {@code Server#getPlayerExact}, is a single lookup
 * into the already-loaded online-player list (a nickname is one more in-memory map lookup), not a walk over the world.
 */
public final class MentionService implements IChatService {

    /** {@code @} then a run of characters a name, or a nickname in its typed form, is made of. */
    private static final Pattern TOKEN = Pattern.compile("@([A-Za-z0-9_]{1,32})");

    /**
     * By key rather than {@code org.bukkit.Sound}'s own enum — that one resolves through Paper's
     * registry the moment its class loads, which a unit test never has running. A {@link Key} is
     * just a string; nothing here needs a live server to be tested.
     */
    private static final Key PING_SOUND = Key.key("entity.experience_orb.pickup");

    private final Server server;
    private final Vanish vanish;
    private final Messages messages;

    private final MentionInbox inbox;

    private volatile ChatSettings settings;

    public MentionService(Server server, Vanish vanish, Messages messages, ChatSettings settings) {
        this(server, vanish, messages, settings, null);
    }

    public MentionService(Server server, Vanish vanish, Messages messages, ChatSettings settings,
                          MentionInbox inbox) {
        this.server = server;
        this.vanish = vanish;
        this.messages = messages;
        this.inbox = inbox;
        settings(settings);
    }

    /**
     * Every {@code @name} or {@code @nickname} in the line that means somebody other than the sender,
     * online or not, where it stands.
     */
    public List<Mention> find(Player sender, String plainText) {
        return found(sender, plainText).stream().map(Found::mention).toList();
    }

    /** A mention and whom the lookup found, so nothing has to be looked up a second time. */
    private record Found(Mention mention, OfflinePlayer who) {
    }

    private List<Found> found(Player sender, String plainText) {
        List<Found> found = new ArrayList<>();
        if (!settings.mentionsEnabled() || sender == null || plainText == null || plainText.isBlank()) {
            return found;
        }
        Matcher matcher = TOKEN.matcher(plainText);
        while (matcher.find()) {
            // No sender: a chat line is never a selector, and "@a" here means somebody called "a".
            PlayerLookup lookup = PlayerTargets.lookup(server, null, matcher.group(1));
            if (lookup.kind() != PlayerLookup.Kind.NAME && lookup.kind() != PlayerLookup.Kind.NICKNAME) {
                continue;
            }
            OfflinePlayer who = lookup.single().orElse(null);
            if (who == null || who.getUniqueId().equals(sender.getUniqueId())) {
                continue;
            }
            boolean here = who instanceof Player;
            boolean reachable = here && vanish.canSee(sender.getUniqueId(), who.getUniqueId());
            String name = who.getName() == null ? matcher.group(1) : who.getName();
            found.add(new Found(new Mention(matcher.start(), matcher.end(), who.getUniqueId(), name, reachable), who));
        }
        return found;
    }

    /**
     * Pings everybody reachable once, and leaves a note for everybody who is not — offline or hidden,
     * which have to look the same.
     */
    public void notify(Player sender, String plainText, List<Mention> found) {
        Set<UUID> done = new LinkedHashSet<>();
        for (Mention mention : found) {
            if (!done.add(mention.player())) {
                continue;
            }
            Player who = mention.reachable() ? server.getPlayer(mention.player()) : null;
            if (who != null) {
                who.playSound(Sound.sound(PING_SOUND, Sound.Source.PLAYER, 0.7f, 1.4f));
                messages.send(who, "chat.mention.pinged", "player", sender.getName(), "text", plainText);
            } else if (inbox != null) {
                inbox.add(mention.player(), sender.getName(), plainText);
            }
        }
    }

    /** What was said about {@code player} while they were away, shown once and then forgotten. */
    public void deliverWaiting(Player player) {
        if (inbox == null) {
            return;
        }
        List<MentionInbox.Note> notes = inbox.take(player.getUniqueId());
        if (notes.isEmpty()) {
            return;
        }
        messages.send(player, "chat.mention.while-away", "count", notes.size());
        for (MentionInbox.Note note : notes) {
            player.sendMessage(messages.get("chat.mention.while-away-line", "player", note.from(),
                    "text", note.text()));
        }
    }

    @Override
    public void settings(ChatSettings fresh) {
        this.settings = fresh;
    }

    /** Everybody named in this line who can be pinged right now, in the order they appear, once each. */
    public List<Player> mentionsIn(Player sender, String plainText) {
        List<Player> found = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (Found one : found(sender, plainText)) {
            if (one.mention().reachable() && one.who() instanceof Player who && seen.add(who.getUniqueId())) {
                found.add(who);
            }
        }
        return found;
    }

    /**
     * Tab-complete candidates for {@code @partial} — every online player the sender can see whose
     * name starts with it, as {@code @Name}, sender excluded. Same visibility rule as
     * {@link #mentionsIn}: a name nobody could actually ping is not worth suggesting either.
     */
    public List<String> candidatesFor(Player sender, String partial) {
        if (!settings.mentionsEnabled() || sender == null || partial == null) {
            return new ArrayList<>();
        }
        List<String> found = new ArrayList<>();
        for (String name : namesVisibleTo(sender, partial)) {
            found.add("@" + name);
        }
        return found;
    }

    /**
     * Every online player {@code asker} can see whose name starts with {@code partial}, {@code asker}
     * excluded — whether or not mentions are switched on, since {@code /chat private add} completes
     * names by the same rule.
     */
    public List<String> namesVisibleTo(Player asker, String partial) {
        if (asker == null || partial == null) {
            return new ArrayList<>();
        }
        return PlayerTargets.suggest(server, partial,
                        online -> !online.equals(asker) && vanish.canSee(asker.getUniqueId(), online.getUniqueId()))
                .stream().filter(name -> !PlayerTargets.isSelector(name)).toList();
    }

    /**
     * The online player called {@code name}, if {@code asker} can see them — a vanished player is
     * "not online" to anybody who cannot, exactly as they are to a mention.
     */
    public Optional<Player> visibleNamed(Player asker, String name) {
        Player found = PlayerTargets.online(server, name).orElse(null);
        if (found == null || asker == null || !vanish.canSee(asker.getUniqueId(), found.getUniqueId())) {
            return Optional.empty();
        }
        return Optional.of(found);
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
