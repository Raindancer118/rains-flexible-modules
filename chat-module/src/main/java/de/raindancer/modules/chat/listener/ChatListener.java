package de.raindancer.modules.chat.listener;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChatTabCompleteEvent;
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
     * Offers {@code @Name} completions once the last word being typed starts with {@code @} —
     * everything else about the request is left untouched, so plain-word completion still works
     * however the server would otherwise have answered it.
     */
    @EventHandler
    public void onTabComplete(PlayerChatTabCompleteEvent event) {
        String token = event.getLastToken();
        if (token.isEmpty() || token.charAt(0) != '@') {
            return;
        }
        List<String> candidates = services.mentions().candidatesFor(event.getPlayer(), token.substring(1));
        if (candidates.isEmpty()) {
            return;
        }
        event.getTabCompletions().clear();
        event.getTabCompletions().addAll(candidates);
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
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.quality().forget(player);
    }
}
