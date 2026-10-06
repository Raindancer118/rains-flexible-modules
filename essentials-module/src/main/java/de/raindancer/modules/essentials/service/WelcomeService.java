package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import org.bukkit.entity.Player;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.modules.essentials.rules.HiRule;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;


/**
 * The lines around joining and leaving.
 *
 * <h2>Why the wording is not a setting</h2>
 * Every other server-facing sentence in this ecosystem lives in {@code messages.yml}, editable by
 * the owner without a restart's worth of settings plumbing — a join line is not special enough to be
 * the one exception. What {@link de.raindancer.modules.essentials.EssentialsSettings} decides is only
 * whether these are said at all, and whether a first join gets its own line.
 */
public final class WelcomeService implements IEssentialsService {

    /** How long a "Say Hi!" button answers after the join it sits under. */
    private static final Duration HI_LIFETIME = Duration.ofMinutes(5);

    private final Messages messages;
    private final Chat chat;
    private final ChatButtons buttons;
    private final Server server;
    private final Plugin plugin;
    private final HiRule hiRule = new HiRule();

    private volatile EssentialsSettings settings;

    public WelcomeService(Messages messages, Chat chat, ChatButtons buttons, Server server, Plugin plugin,
                          EssentialsSettings settings) {
        this.messages = messages;
        this.chat = chat;
        this.buttons = buttons;
        this.server = server;
        this.plugin = plugin;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        this.settings = fresh;
    }

    /** Whether this module's own join/quit lines replace vanilla's — the listener asks this first. */
    public boolean ownsJoinQuitLines() {
        return settings.joinQuitEnabled();
    }

    public void joined(Player who, boolean firstJoin) {
        String key;
        if (firstJoin && settings.welcomeFirstJoin()) {
            key = "essentials.welcome.first-join";
        } else if (settings.joinQuitEnabled()) {
            key = "essentials.welcome.joined";
        } else {
            return;
        }
        if (!settings.sayHiButton() || !buttons.isClickable()) {
            chat.broadcast(messages.raw(key), Chat.arg("player", who.getName()));
            return;
        }
        announceWithHi(who, key);
    }

    /**
     * The join line, with a "Say Hi!" button for everybody but the newcomer. Rendered per recipient and
     * bound to them, so one person's click does not use up everybody else's button.
     */
    private void announceWithHi(Player joiner, String key) {
        Component line = messages.prefixed(key, "player", joiner.getName());
        UUID joinerId = joiner.getUniqueId();
        String joinerName = joiner.getName();
        Set<UUID> greeted = ConcurrentHashMap.newKeySet();
        server.getConsoleSender().sendMessage(line);
        for (Player recipient : server.getOnlinePlayers()) {
            if (recipient.getUniqueId().equals(joinerId)) {
                recipient.sendMessage(line);
                continue;
            }
            Component button = buttons.label(messages.raw("essentials.welcome.hi-button"))
                    .tooltip(messages.raw("essentials.welcome.hi-tooltip"))
                    .forOnly(recipient.getUniqueId())
                    .expiringIn(HI_LIFETIME)
                    // Repeatable so a second click reaches HiRule, which has a better answer than
                    // the button's generic "already used".
                    .repeatable()
                    .does(clicker -> sayHi(clicker, joinerId, joinerName, greeted))
                    .render();
            recipient.sendMessage(line.append(Component.space()).append(button));
        }
    }

    private void sayHi(UUID clickerId, UUID joinerId, String joinerName, Set<UUID> greeted) {
        Player clicker = server.getPlayer(clickerId);
        if (clicker == null) {
            return;
        }
        Verdict verdict = hiRule.judge(new HiRule.Click(clickerId, joinerId, !greeted.add(clickerId)));
        if (verdict.isRefused()) {
            messages.send(clicker, verdict.reason());
            return;
        }
        String said = HiRule.line(settings.hiGreetings(), joinerName, ThreadLocalRandom.current().nextInt());
        // Said as the player, so it goes through chat like anything they type: format, filters, mutes.
        Scheduling.entity(plugin, clicker, () -> clicker.chat(said));
    }

    public void quit(Player who) {
        if (settings.joinQuitEnabled()) {
            chat.broadcast(messages.raw("essentials.welcome.quit"),
                    Chat.arg("player", who.getName()));
        }
    }

    @Override
    public String describe() {
        return "the lines around joining and leaving, and the Say Hi! button";
    }
}
