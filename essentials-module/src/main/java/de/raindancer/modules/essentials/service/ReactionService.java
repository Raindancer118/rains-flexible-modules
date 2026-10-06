package de.raindancer.modules.essentials.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.rules.ReactionRule;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * A line about somebody, with a button for everybody else that makes them say something nice — Say Hi!
 * under a join, Congrats! under an advancement.
 *
 * <p>Rendered per recipient and bound to them, so one click does not spend everybody's button; and
 * repeatable, so a second click reaches {@link ReactionRule}, whose answer is better than the button's
 * generic "already used". What is said goes through chat as the clicker, so formats, filters and mutes
 * apply as if they had typed it.
 */
public final class ReactionService implements IEssentialsService {

    /** One kind of reaction: its button, its refusals, and what it may say. */
    public record Reaction(String buttonKey, String tooltipKey, ReactionRule rule,
                           Function<EssentialsSettings, List<String>> phrases, String fallback) {
    }

    public static final Reaction HI = new Reaction("essentials.welcome.hi-button", "essentials.welcome.hi-tooltip",
            new ReactionRule("essentials.welcome.hi-yourself", "essentials.welcome.hi-already"),
            EssentialsSettings::hiGreetings, "Hi");

    public static final Reaction CONGRATS = new Reaction("essentials.congrats.button",
            "essentials.congrats.tooltip",
            new ReactionRule("essentials.congrats.yourself", "essentials.congrats.already"),
            EssentialsSettings::congratsPhrases, "GG");

    /** How long a button answers after the line it sits under. */
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private final Messages messages;
    private final ChatButtons buttons;
    private final Server server;
    private final Plugin plugin;

    private volatile EssentialsSettings settings;

    public ReactionService(Messages messages, ChatButtons buttons, Server server, Plugin plugin,
                           EssentialsSettings settings) {
        this.messages = messages;
        this.buttons = buttons;
        this.server = server;
        this.plugin = plugin;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        this.settings = fresh;
    }

    /** Whether buttons can be clicked at all on this server. Without, the line goes out plain. */
    public boolean isClickable() {
        return buttons.isClickable();
    }

    /** Sends {@code line} to everybody — with the reaction's button, for everybody but {@code subject}. */
    public void broadcast(Component line, Player subject, Reaction reaction) {
        UUID subjectId = subject.getUniqueId();
        String subjectName = subject.getName();
        Set<UUID> reacted = ConcurrentHashMap.newKeySet();
        server.getConsoleSender().sendMessage(line);
        for (Player recipient : server.getOnlinePlayers()) {
            if (recipient.getUniqueId().equals(subjectId)) {
                recipient.sendMessage(line);
                continue;
            }
            Component button = buttons.label(messages.raw(reaction.buttonKey()))
                    .tooltip(messages.raw(reaction.tooltipKey()))
                    .forOnly(recipient.getUniqueId())
                    .expiringIn(LIFETIME)
                    .repeatable()
                    .does(clicker -> react(clicker, subjectId, subjectName, reacted, reaction))
                    .render();
            recipient.sendMessage(line.append(Component.space()).append(button));
        }
    }

    private void react(UUID clickerId, UUID subjectId, String subjectName, Set<UUID> reacted, Reaction reaction) {
        Player clicker = server.getPlayer(clickerId);
        if (clicker == null) {
            return;
        }
        Verdict verdict = reaction.rule().judge(
                new ReactionRule.Click(clickerId, subjectId, !reacted.add(clickerId)));
        if (verdict.isRefused()) {
            messages.send(clicker, verdict.reason());
            return;
        }
        String said = ReactionRule.line(reaction.phrases().apply(settings), subjectName,
                ThreadLocalRandom.current().nextInt(), reaction.fallback());
        Scheduling.entity(plugin, clicker, () -> clicker.chat(said));
    }

    @Override
    public String describe() {
        return "the Say Hi! and Congrats! buttons";
    }
}
