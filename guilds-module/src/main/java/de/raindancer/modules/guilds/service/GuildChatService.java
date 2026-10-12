package de.raindancer.modules.guilds.service;

import de.raindancer.core.ui.chat.ChatChannel;
import de.raindancer.core.ui.chat.ChatChannels;
import de.raindancer.core.ui.identity.NameBadges;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Relation;
import de.raindancer.modules.guilds.store.GuildBook;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The three channels — the guild, its officers, the guild and its allies — offered through Core's
 * {@link ChatChannels}, so {@code /chat guild} in the chat module and {@code /g c} here are the same channel,
 * and the tag in front of every member's name through Core's {@link NameBadges}.
 *
 * <p>Each channel delivers itself: the line names the speaker and the channel the same way whichever way in it
 * came, and a player's text is put in as text — a member typing markup sees it printed, not obeyed.
 */
public final class GuildChatService implements IGuildsService {

    public static final String BADGE_OWNER = "guilds";

    public enum Kind {
        GUILD("guild", "Guild", "guilds.chat.guild"),
        OFFICER("officer", "Officers", "guilds.chat.officer"),
        ALLY("ally", "Allies", "guilds.chat.ally");

        private final String id;
        private final String label;
        private final String key;

        Kind(String id, String label, String key) {
            this.id = id;
            this.label = label;
            this.key = key;
        }

        public String id() {
            return id;
        }
    }

    private final GuildBook book;
    private final GuildNotifier notify;
    private final List<Channel> channels = List.of(new Channel(Kind.GUILD), new Channel(Kind.OFFICER), new Channel(Kind.ALLY));
    private volatile GuildsSettings settings;

    public GuildChatService(GuildBook book, GuildNotifier notify, GuildsSettings settings) {
        this.book = book;
        this.notify = notify;
        this.settings = settings;
    }

    @Override
    public void settings(GuildsSettings settings) {
        boolean was = this.settings.tagInNames();
        this.settings = settings;
        if (was != settings.tagInNames()) {
            badges(settings.tagInNames());
        }
    }

    public void open() {
        channels.forEach(ChatChannels::register);
        badges(settings.tagInNames());
    }

    public void close() {
        channels.forEach(ChatChannels::unregister);
        NameBadges.unregister(BADGE_OWNER);
    }

    private void badges(boolean on) {
        if (on) {
            NameBadges.register(BADGE_OWNER, player -> book.guildOf(player).map(GuildNotifier::tag));
        } else {
            NameBadges.unregister(BADGE_OWNER);
        }
    }

    /** Who hears {@code speaker} on that channel, or empty when they have no part in it. */
    public Optional<Set<UUID>> audience(Kind kind, UUID speaker) {
        Optional<Guild> found = book.guildOf(speaker);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Guild guild = found.get();
        return switch (kind) {
            case GUILD -> Optional.of(guild.members().keySet());
            case OFFICER -> guild.may(speaker, GuildPermission.OFFICER_CHAT)
                    ? Optional.of(Set.copyOf(guild.members().keySet().stream()
                    .filter(member -> guild.may(member, GuildPermission.OFFICER_CHAT)).toList()))
                    : Optional.empty();
            case ALLY -> {
                Set<UUID> heard = new HashSet<>(guild.members().keySet());
                guild.relations().forEach((other, relation) -> {
                    if (relation == Relation.ALLY) {
                        book.guild(other).filter(ally -> ally.relationTo(guild.id()) == Relation.ALLY)
                                .ifPresent(ally -> heard.addAll(ally.members().keySet()));
                    }
                });
                yield Optional.of(Set.copyOf(heard));
            }
        };
    }

    /** Says a line on a channel. @return false when the speaker has no part in it */
    public boolean say(Kind kind, Player speaker, String text) {
        Optional<Set<UUID>> heard = audience(kind, speaker.getUniqueId());
        Optional<Guild> guild = book.guildOf(speaker.getUniqueId());
        if (heard.isEmpty() || guild.isEmpty() || text == null || text.isBlank()) {
            return false;
        }
        Component line = notify.messages().get(kind.key, "tag", GuildNotifier.tag(guild.get()),
                "player", notify.name(speaker.getUniqueId()), "message", text.strip());
        for (UUID listener : heard.get()) {
            notify.tell(listener, line);
        }
        return true;
    }

    private final class Channel implements ChatChannel {

        private final Kind kind;

        private Channel(Kind kind) {
            this.kind = kind;
        }

        @Override
        public String id() {
            return kind.id;
        }

        @Override
        public String label() {
            return kind.label;
        }

        @Override
        public Optional<Set<UUID>> audienceFor(UUID speaker) {
            return audience(kind, speaker);
        }

        @Override
        public boolean deliver(Player speaker, String text) {
            return say(kind, speaker, text);
        }
    }
}
