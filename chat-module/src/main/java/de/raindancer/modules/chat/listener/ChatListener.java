package de.raindancer.modules.chat.listener;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.util.PermissionNodes;
import de.raindancer.modules.chat.util.PrivateChatNotices;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

/**
 * Everything a public chat line goes through, in order: whether chat is frozen, whether the message
 * quality rules let it through, and — if it does — how it is actually shown and who it pings.
 *
 * <h2>Where this sits, and why it must run after the mute check</h2>
 * {@code ignoreCancelled = true} at the default priority, after Core's own {@code PunishmentListener}
 * mutes somebody at {@code LOW} — a muted player's line never reaches any of the checks below, so a
 * mute and a freeze or a slowmode never have to agree about which refusal wins.
 *
 * <h2>Why the deciding is here and not in a service</h2>
 * Every actual decision — the verdict, the render, who is mentioned — is one call into a service that
 * makes it without touching Bukkit. This only reads the event, asks in the right order, and turns a
 * refusal or a render into what {@code AsyncChatEvent} wants back. See {@code ChatQualityService} and
 * {@code MentionService} for where the logic itself lives.
 */
public final class ChatListener implements IChatListener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ChatServices services;

    public ChatListener(ChatServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String text = PLAIN.serialize(event.message());

        if (services.privateChat().isTalkingPrivately(sender.getUniqueId())) {
            event.setCancelled(true);
            sayPrivately(sender, text);
            return;
        }

        if (services.freeze().isFrozen() && !sender.hasPermission(PermissionNodes.BYPASS_FREEZE)) {
            event.setCancelled(true);
            services.messages().send(sender, "chat.frozen");
            return;
        }

        boolean bypass = sender.hasPermission(PermissionNodes.BYPASS_FILTERS);
        Verdict verdict = services.quality().check(sender.getUniqueId(), text, bypass);
        if (verdict.isRefused()) {
            event.setCancelled(true);
            services.messages().send(sender, verdict.reason(), "seconds", verdict.detail());
            return;
        }
        services.quality().recordSent(sender.getUniqueId(), text);
        services.history().record(sender.getUniqueId(), sender.getName(), text);

        List<Player> mentioned = services.mentions().mentionsIn(sender, text);
        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, message) ->
                services.format().render(sender, text, mentioned)));
        services.mentions().notifyMentioned(sender, text, mentioned);
    }

    /**
     * A line said in a private chat, put in front of its members and nobody else.
     *
     * <h2>Why cancelling is the whole trick</h2>
     * Paper writes a chat line to the console only as part of delivering an event nobody cancelled,
     * and every listener after this one that honours a cancel — the Discord bridge sits at
     * {@code MONITOR} with {@code ignoreCancelled = true} for exactly this reason — never sees the line
     * at all. So the event is cancelled and the line is sent here, by hand, to each member. It is also
     * why this comes before the freeze, the quality filters and {@code /chathistory}: none of them is
     * about a conversation the public cannot read, and the history is one the public <em>can</em>.
     *
     * <h2>A mute still applies</h2>
     * Core's {@code PunishmentListener} cancels a muted player's line at {@code LOW}, before this
     * handler — which ignores cancelled events — is ever asked.
     *
     * <h2>Why nothing here is scheduled</h2>
     * The same call {@link de.raindancer.modules.chat.service.MentionService} already makes on this
     * thread: one online-player lookup per member, and sending a component, which needs no region
     * thread. Deferring it would only let the chat end between the line and its delivery.
     */
    private void sayPrivately(Player sender, String text) {
        Component line = services.chat().mm(services.messages().raw("chat.private.line"),
                Chat.formatted("line", services.format().render(sender, text, List.of())));
        for (UUID reader : services.privateChat().readersOf(sender.getUniqueId())) {
            Player online = services.server().getPlayer(reader);
            if (online != null) {
                online.sendMessage(line);
            }
        }
    }

    /**
     * Offers {@code @Name} completions once the last word being typed starts with {@code @} —
     * everything else about the request is left untouched, so plain-word completion still works
     * however the server would otherwise have answered it.
     *
     * <h2>Why this event and not {@code PlayerChatTabCompleteEvent}</h2>
     * {@code PlayerChatTabCompleteEvent} has been dead since 1.13 — Bukkit's own javadoc says so
     * ("no longer fired due to client changes") — because the client stopped asking the server for
     * chat-text completions over that packet. {@link AsyncTabCompleteEvent} is what actually still
     * fires for both commands and plain chat; {@link AsyncTabCompleteEvent#isCommand()} is how the
     * two are told apart here.
     */
    @EventHandler
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (event.isCommand()) {
            return;
        }
        CommandSender sender = event.getSender();
        if (!(sender instanceof Player player)) {
            return;
        }
        String buffer = event.getBuffer();
        int lastSpace = buffer.lastIndexOf(' ');
        String token = lastSpace >= 0 ? buffer.substring(lastSpace + 1) : buffer;
        if (token.isEmpty() || token.charAt(0) != '@') {
            return;
        }
        List<String> candidates = services.mentions().candidatesFor(player, token.substring(1));
        if (candidates.isEmpty()) {
            return;
        }
        event.setCompletions(candidates);
    }

    /**
     * A quiet hint rather than the history itself — dumping every missed line into a fresh join is
     * exactly the wall of text a player already has to get past on a server with a MOTD, a welcome
     * broadcast and a scoreboard all firing at once. {@code /chathistory} is one command away for
     * whoever wants to actually read it.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!services.history().notifyOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        int missed = services.history().missedBy(player.getUniqueId()).size();
        if (missed > 0) {
            services.messages().send(player, "chat.history.missed", "count", String.valueOf(missed));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        services.history().markLeft(event.getPlayer().getUniqueId());
        PrivateChatNotices.disconnected(services, event.getPlayer());
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.quality().forget(player);
    }
}
