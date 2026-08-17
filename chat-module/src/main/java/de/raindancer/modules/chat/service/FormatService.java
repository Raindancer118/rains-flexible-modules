package de.raindancer.modules.chat.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.util.Links;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the {@link Component} a chat line is actually shown as: this module's format template
 * around Core's own {@link Identities#chatName} — prefix, suffix and name colour, all set once and
 * read here rather than reinvented — with every @-mention highlighted and every link made clickable.
 *
 * <h2>Why the mention list is a parameter, not looked up again here</h2>
 * {@link MentionService#mentionsIn} already answered "who does this line mention" once, deciding for
 * itself which tokens are real and which a vanished target rules out. Asking a second time here would
 * either repeat that decision or, worse, disagree with it — a message highlighted for a name vanish
 * had just refused to match.
 */
public final class FormatService implements IChatService {

    /** {@code @} then a run of characters a Minecraft name is made of — the same shape as the token
     * {@link MentionService} matched to build the list this class is handed. */
    private static final Pattern MENTION_TOKEN = Pattern.compile("@([A-Za-z0-9_]{1,16})");

    private final Chat chat;
    private final Identities identities;

    private volatile ChatSettings settings;

    public FormatService(Chat chat, Identities identities, ChatSettings settings) {
        this.chat = chat;
        this.identities = identities;
        settings(settings);
    }

    @Override
    public void settings(ChatSettings fresh) {
        this.settings = fresh;
    }

    /** The finished line: the format template, the speaker's identity, and their message. */
    public Component render(Player sender, String plainText, List<Player> mentioned) {
        return chat.mm(settings.format(),
                Chat.formatted("name", nameOf(sender)),
                Chat.formatted("message", messageOf(plainText, mentioned)));
    }

    private Component nameOf(Player sender) {
        Component name = identities.chatName(sender.getUniqueId(), sender.getName());
        if (!settings.clickToMessage()) {
            return name;
        }
        return name.clickEvent(ClickEvent.suggestCommand("/msg " + sender.getName() + " "))
                .hoverEvent(HoverEvent.showText(Component.text("Click to message " + sender.getName())));
    }

    private Component messageOf(String plainText, List<Player> mentioned) {
        if (plainText == null || plainText.isBlank()) {
            return Component.text(plainText == null ? "" : plainText);
        }
        if (!settings.mentionsEnabled() || mentioned == null || mentioned.isEmpty()) {
            return linkifyIfEnabled(plainText);
        }
        Set<String> mentionedNames = new LinkedHashSet<>();
        for (Player who : mentioned) {
            mentionedNames.add(who.getName());
        }

        Component built = Component.empty();
        Matcher matcher = MENTION_TOKEN.matcher(plainText);
        int cursor = 0;
        while (matcher.find()) {
            if (!containsIgnoreCase(mentionedNames, matcher.group(1))) {
                continue;
            }
            built = built.append(linkifyIfEnabled(plainText.substring(cursor, matcher.start())));
            built = built.append(highlighted(matcher.group()));
            cursor = matcher.end();
        }
        return built.append(linkifyIfEnabled(plainText.substring(cursor)));
    }

    private Component linkifyIfEnabled(String text) {
        return settings.linkifyUrls() ? Links.linkify(text) : Component.text(text);
    }

    private static Component highlighted(String token) {
        return Component.text(token).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD);
    }

    private static boolean containsIgnoreCase(Set<String> names, String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return "the rendered chat line: format, identity, mention highlighting and links";
    }
}
