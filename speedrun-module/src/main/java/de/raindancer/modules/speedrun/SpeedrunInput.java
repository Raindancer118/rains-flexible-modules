package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.core.ui.prompt.Parsed;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.core.ui.prompt.Question;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Asking somebody for a value: in a window when they are already in a menu — an anvil whose result
 * slot says what is wrong while they type — and in chat when they asked from a command. Both are
 * Core's ({@link AnvilInput}, {@link Question}); this is only the seam between them and the lobby, so
 * the lobby's flows are testable without a server.
 *
 * <p>The answer arrives on the player's own thread, already parsed: nothing here ever sees a value
 * the parser refused.
 */
public interface SpeedrunInput {

    /** A clock reading: {@code 42:05}, {@code 1:02:03}, {@code 1h30m} or {@code 0}. */
    Parsers.Parser<Duration> TIME = typed -> RunClock.parse(typed).map(Parsed::ok)
            .orElseGet(() -> Parsed.no("A time like 42:05, 1:02:03 or 1h30m."));

    /** One seed: a number, or a word the way the create-world screen takes one. */
    Parsers.Parser<String> SEED = typed -> {
        String seed = typed == null ? "" : typed.trim();
        if (seed.length() > 64) {
            return Parsed.no("At most 64 characters.");
        }
        return SpeedrunSeeds.fixed(seed).isPresent() ? Parsed.ok(seed)
                : Parsed.no("A number, or a word the way the create-world screen takes one.");
    };

    /** A pool of seeds, separated by commas — at least one. */
    Parsers.Parser<String> SEED_POOL = typed -> {
        String pool = typed == null ? "" : typed.trim();
        return SpeedrunSeeds.pool(pool).isEmpty() ? Parsed.no("Seeds separated by commas — at least one.")
                : Parsed.ok(pool);
    };

    /** Asks in a window, from a menu. {@code title} is text, not markup. */
    <T> void window(Player player, String title, String start, Parsers.Parser<T> parser, Consumer<T> answer);

    /** Asks in chat, from a command; the suggestions are clickable. {@code prompt} is text. */
    <T> void chat(Player player, String prompt, Parsers.Parser<T> parser, List<String> suggestions, Consumer<T> answer);

    /** Core's anvil and chat questions, worded through the lobby's own messages. */
    static SpeedrunInput core(ChatPrompts prompts, ChatButtons buttons, Messages messages) {
        return new SpeedrunInput() {
            @Override
            public <T> void window(Player player, String title, String start, Parsers.Parser<T> parser,
                                   Consumer<T> answer) {
                AnvilInput.open(player, title, start, parser, answer, null);
            }

            @Override
            public <T> void chat(Player player, String prompt, Parsers.Parser<T> parser, List<String> suggestions,
                                 Consumer<T> answer) {
                Question.asking(parser).owner("Speedrun").prompt(prompt)
                        .suggest(suggestions.toArray(String[]::new)).onAnswer(answer)
                        .ask(player.getUniqueId(), prompts, player::sendMessage, buttons, messages,
                                System::currentTimeMillis);
            }
        };
    }
}
