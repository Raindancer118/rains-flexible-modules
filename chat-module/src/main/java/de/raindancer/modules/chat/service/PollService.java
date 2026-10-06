package de.raindancer.modules.chat.service;

import de.raindancer.core.content.vote.Ballot;
import de.raindancer.core.content.vote.Tally;
import de.raindancer.core.content.vote.Vote;
import de.raindancer.core.content.vote.Votes;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.rules.PollRule;
import de.raindancer.modules.chat.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls in chat: a question, a clickable button per answer, results with bars when it ends.
 *
 * <p>The counting is Core's {@link Votes} — one vote per player, changing a vote is a change and not
 * a second vote, a tie is a tie. This holds what a chat poll adds: the buttons, the timer that ends it
 * and the result lines. Core's own sweep marks finished votes as reported, so the end is scheduled
 * here rather than read off that sweep. One poll at a time: two at once is two button rows nobody can
 * tell apart.
 */
public final class PollService implements IChatService {

    /** A poll that is running. */
    public record Running(UUID voteId, String question, List<String> answers, UUID starter, String starterName,
                          long closesAt) {
    }

    /** What somebody is building in the menu, before it starts. */
    public record Draft(String question, List<String> answers, Duration lasting) {

        public Draft withQuestion(String text) {
            return new Draft(text, answers, lasting);
        }

        public Draft withAnswer(String answer) {
            List<String> more = new ArrayList<>(answers);
            more.add(answer);
            return new Draft(question, List.copyOf(more), lasting);
        }

        public Draft withoutAnswer(int index) {
            List<String> fewer = new ArrayList<>(answers);
            if (index >= 0 && index < fewer.size()) {
                fewer.remove(index);
            }
            return new Draft(question, List.copyOf(fewer), lasting);
        }

        public Draft lasting(Duration length) {
            return new Draft(question, answers, length);
        }
    }

    private static final int BAR_WIDTH = 12;

    private final Plugin plugin;
    private final Server server;
    private final Votes votes;
    private final ChatButtons buttons;
    private final Messages messages;
    private final PollRule rule = new PollRule();
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    private volatile ChatSettings settings;

    public PollService(Plugin plugin, Server server, Votes votes, ChatButtons buttons, Messages messages,
                       ChatSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.votes = votes;
        this.buttons = buttons;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(ChatSettings fresh) {
        this.settings = fresh;
    }

    public PollRule rule() {
        return rule;
    }

    public Duration standardLength() {
        return settings.pollLength();
    }

    public Optional<Running> current() {
        return running.values().stream().findFirst();
    }

    // ------------------------------------------------------------------ drafts (the menu)

    public Draft draftOf(UUID player) {
        return drafts.computeIfAbsent(player, who -> new Draft("", List.of(), settings.pollLength()));
    }

    public void draft(UUID player, Draft draft) {
        drafts.put(player, draft);
    }

    public void forget(UUID player) {
        drafts.remove(player);
    }

    // ------------------------------------------------------------------ starting

    /** Starts it, or tells them why not. */
    public boolean start(Player starter, String question, List<String> answers, Duration lasting) {
        if (!settings.pollsEnabled()) {
            messages.send(starter, "chat.poll.off");
            return false;
        }
        if (!running.isEmpty()) {
            messages.send(starter, "chat.poll.one-at-a-time");
            return false;
        }
        Verdict verdict = rule.check(question, answers, lasting);
        if (verdict.isRefused()) {
            messages.send(starter, verdict.reason());
            return false;
        }
        Optional<Vote> opened = votes.open(starter.getUniqueId(), question.strip(),
                answers.stream().map(String::strip).toList(), lasting);
        if (opened.isEmpty()) {
            messages.send(starter, "chat.poll.not-started");
            return false;
        }
        Vote vote = opened.get();
        Running poll = new Running(vote.id(), vote.question(), vote.options(), starter.getUniqueId(),
                starter.getName(), vote.closesAt());
        running.put(vote.id(), poll);
        drafts.remove(starter.getUniqueId());
        announce(poll, lasting);
        Scheduling.globalLater(plugin, Math.max(1, lasting.toMillis() / 50), () -> finish(vote.id()));
        return true;
    }

    private void announce(Running poll, Duration lasting) {
        Component asked = messages.prefixed("chat.poll.asked", "player", poll.starterName(),
                "question", poll.question());
        Component closes = messages.get("chat.poll.closes-in", "time", Times.describe(lasting));
        List<ChatButton> row = new ArrayList<>();
        for (String answer : poll.answers()) {
            row.add(buttons.label(messages.raw("chat.poll.button").replace("<answer>", escape(answer)))
                    .tooltip(messages.raw("chat.poll.button-tooltip").replace("<answer>", escape(answer)))
                    .repeatable()
                    .expiringIn(lasting.plusSeconds(10))
                    .does(clicker -> vote(clicker, poll.voteId(), answer)));
        }
        Component answers = buttons.row(row.toArray(ChatButton[]::new));
        server.getConsoleSender().sendMessage(asked);
        for (Player player : server.getOnlinePlayers()) {
            player.sendMessage(asked);
            player.sendMessage(answers);
            player.sendMessage(closes);
        }
    }

    /** A typed answer is text, never markup — a poll about {@code <red>} must not paint the button. */
    private static String escape(String text) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text);
    }

    // ------------------------------------------------------------------ answering

    public void vote(UUID voterId, UUID voteId, String answer) {
        Player voter = server.getPlayer(voterId);
        if (voter == null) {
            return;
        }
        Ballot ballot = votes.cast(voteId, voterId, answer);
        String key = switch (ballot) {
            case COUNTED -> "chat.poll.voted";
            case CHANGED -> "chat.poll.changed";
            case ALREADY -> "chat.poll.already";
            default -> "chat.poll.over";
        };
        messages.send(voter, key, "answer", answer);
    }

    // ------------------------------------------------------------------ ending

    /** Ends the running poll early — whoever started it, or staff. */
    public void endEarly(CommandSender who) {
        Optional<Running> poll = current();
        if (poll.isEmpty()) {
            messages.send(who, "chat.poll.none");
            return;
        }
        boolean theirs = who instanceof Player player && player.getUniqueId().equals(poll.get().starter());
        if (!theirs && !who.hasPermission(PermissionNodes.POLL_MANAGE)) {
            messages.send(who, "chat.poll.not-yours");
            return;
        }
        finish(poll.get().voteId());
    }

    /** The results as they stand, for whoever asks. */
    public void showResults(CommandSender who) {
        Optional<Running> poll = current();
        if (poll.isEmpty()) {
            messages.send(who, "chat.poll.none");
            return;
        }
        votes.tally(poll.get().voteId()).ifPresent(tally -> results(poll.get(), tally, false).forEach(who::sendMessage));
    }

    private void finish(UUID voteId) {
        Running poll = running.remove(voteId);
        if (poll == null) {
            return;     // ended early already
        }
        votes.close(voteId);
        Optional<Tally> tally = votes.tally(voteId);
        if (tally.isEmpty()) {
            return;
        }
        List<Component> lines = results(poll, tally.get(), true);
        lines.forEach(server.getConsoleSender()::sendMessage);
        for (Player player : server.getOnlinePlayers()) {
            lines.forEach(player::sendMessage);
        }
    }

    /** The question, a bar per answer, and who won. */
    List<Component> results(Running poll, Tally tally, boolean finished) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.prefixed(finished ? "chat.poll.results" : "chat.poll.results-so-far",
                "question", poll.question()));
        for (String answer : poll.answers()) {
            double share = tally.totalCast() == 0 ? 0 : tally.shareOf(answer);
            lines.add(messages.get("chat.poll.result-line", "answer", answer,
                    "bar", PollRule.bar(share, BAR_WIDTH),
                    "percent", Math.round(share * 100), "count", tally.votesFor(answer)));
        }
        if (!finished) {
            return lines;
        }
        if (tally.totalCast() == 0) {
            lines.add(messages.get("chat.poll.nobody-voted"));
        } else if (tally.isTie()) {
            lines.add(messages.get("chat.poll.tie", "answers", String.join(" & ", tally.leaders())));
        } else {
            lines.add(messages.get("chat.poll.winner", "answer", tally.winner().orElse("?"),
                    "count", tally.totalCast()));
        }
        return lines;
    }

    @Override
    public String describe() {
        return "polls in chat";
    }
}
