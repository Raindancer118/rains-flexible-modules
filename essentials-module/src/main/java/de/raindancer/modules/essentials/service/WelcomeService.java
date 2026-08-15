package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import org.bukkit.entity.Player;

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

    private final Messages messages;
    private final Chat chat;

    private volatile EssentialsSettings settings;

    public WelcomeService(Messages messages, Chat chat, EssentialsSettings settings) {
        this.messages = messages;
        this.chat = chat;
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
        if (firstJoin && settings.welcomeFirstJoin()) {
            chat.broadcast(messages.raw("essentials.welcome.first-join"),
                    Chat.arg("player", who.getName()));
            return;
        }
        if (settings.joinQuitEnabled()) {
            chat.broadcast(messages.raw("essentials.welcome.joined"),
                    Chat.arg("player", who.getName()));
        }
    }

    public void quit(Player who) {
        if (settings.joinQuitEnabled()) {
            chat.broadcast(messages.raw("essentials.welcome.quit"),
                    Chat.arg("player", who.getName()));
        }
    }

    @Override
    public String describe() {
        return "the lines around joining and leaving";
    }
}
