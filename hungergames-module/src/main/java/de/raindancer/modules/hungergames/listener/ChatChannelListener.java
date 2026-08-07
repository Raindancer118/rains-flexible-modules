package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.team.Team;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.model.ChatChannel;
import de.raindancer.modules.hungergames.service.ChatChannelService;
import de.raindancer.modules.hungergames.store.GameSession;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Routing a tribute's chat to their team, to every living tribute, or to their fellow spectators — never
 * to whichever of those they are not currently allowed.
 *
 * <h2>What this replaces</h2>
 * Nothing. There was no channel at all: every tribute's chat was ordinary server chat, heard by everybody
 * including whoever had just been eliminated. A team coordinating a fight was overheard by the sixteen
 * people they were fighting, and being eliminated changed nothing about who could read a tribute's words.
 *
 * <h2>Where this sits in the chain, and why</h2>
 * {@code NORMAL}, after Core's punishment check at {@code LOW} — a muted tribute is muted in every
 * channel this offers, not just the server's — and after Core's {@code PromptListener} at {@code LOWEST},
 * so a line typed in answer to a chat prompt (a team's new name, a player being renamed) is eaten there
 * and never reaches a channel at all. See {@code StaffChatListener}'s identical note; the ordering is the
 * same fix for the same two problems.
 *
 * <h2>Only tributes are touched</h2>
 * A message from anybody {@link GameSession#participants()} does not know about — staff watching, another
 * module's players on a shared server — is left completely alone. This module's channels exist for its
 * own round, not for the server's chat as a whole.
 *
 * <h2>Why the message is read as plain text</h2>
 * The line is rebuilt through {@link Messages}, which escapes every placeholder it is given — but only
 * the placeholder is escaped, and the raw {@link net.kyori.adventure.text.Component} Paper hands this
 * event can carry formatting of its own. Read as plain text first, exactly as {@code StaffChatListener}
 * does, so a tribute who somehow sends a styled component cannot close a tag the template opened.
 */
public final class ChatChannelListener implements IHungerGamesListener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final org.bukkit.plugin.Plugin plugin;
    private final GameSession session;
    private final ChatChannelService channels;
    private final Messages messages;
    private final Server server;

    public ChatChannelListener(org.bukkit.plugin.Plugin plugin, GameSession session,
                               ChatChannelService channels, Messages messages, Server server) {
        this.plugin = plugin;
        this.session = session;
        this.channels = channels;
        this.messages = messages;
        this.server = server;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player talking = event.getPlayer();
        UUID uuid = talking.getUniqueId();
        if (!session.participants().contains(uuid)) {
            return;
        }
        event.setCancelled(true);
        String text = PLAIN.serialize(event.message());
        ChatChannel channel = channels.effectiveChannel(uuid);

        // AsyncChatEvent fires off the main thread, and deciding an audience — reading the online player
        // list, asking Teams who is on this tribute's team — is main-thread (or, on Folia, global-region)
        // work. See StaffChatListener's identical note.
        Scheduling.global(plugin, () -> {
            switch (channel) {
                case TEAM -> sayToTeam(talking, text);
                case ALL -> sayToEverybodyStillPlaying(talking, text);
                case SPECTATOR -> sayToFellowSpectators(talking, text);
            }
        });
    }

    private void sayToTeam(Player talking, String text) {
        Optional<Team> team = session.teams().teamOf(talking.getUniqueId());
        if (team.isEmpty()) {
            // A race rather than a real state: ChatChannelService.effectiveChannel already falls back to
            // ALL for a tribute with no team, so this only fires if the team was left in the instant
            // between that check and this one running on the main thread.
            sayToEverybodyStillPlaying(talking, text);
            return;
        }
        List<Player> teammates = new ArrayList<>();
        for (UUID member : team.get().members()) {
            Player online = server.getPlayer(member);
            if (online != null) {
                teammates.add(online);
            }
        }
        line("hungergames.chat-team-line", talking, text, teammates);
    }

    private void sayToEverybodyStillPlaying(Player talking, String text) {
        List<Player> listening = new ArrayList<>();
        for (Player online : server.getOnlinePlayers()) {
            if (!session.participants().contains(online.getUniqueId())
                    || session.participants().isAlive(online.getUniqueId())) {
                listening.add(online);
            }
        }
        line("hungergames.chat-all-line", talking, text, listening);
    }

    private void sayToFellowSpectators(Player talking, String text) {
        List<Player> listening = new ArrayList<>();
        for (UUID eliminated : session.participants().eliminated()) {
            Player online = server.getPlayer(eliminated);
            if (online != null) {
                listening.add(online);
            }
        }
        line("hungergames.chat-spectator-line", talking, text, listening);
    }

    private void line(String key, Player talking, String text, List<Player> recipients) {
        if (recipients.isEmpty()) {
            return;
        }
        var rendered = messages.get(key, "who", talking.getName(), "what", text);
        for (Player recipient : recipients) {
            recipient.sendMessage(rendered);
        }
    }

    @Override
    public void forget(UUID player) {
        channels.forget(player);
    }

    @Override
    public String describe() {
        return "routing a tribute's chat to their team, to everybody, or to fellow spectators";
    }
}
