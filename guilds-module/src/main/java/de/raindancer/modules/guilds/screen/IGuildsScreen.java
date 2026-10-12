package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.GuildOutcome;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * A screen of this module, keeping the menu grammar {@code ScreenGrammarTest} holds against the source: greyed
 * never hidden, every modifier click named in the lore, nothing irreversible without a confirmation, buttons from
 * Core's {@code Icons}, and no refusal that says nothing.
 */
public interface IGuildsScreen {

    Duration TO_ANSWER = Duration.ofSeconds(60);

    void open();

    default String describe() {
        return getClass().getSimpleName();
    }

    /** Says how it went — the outcome's own sentence — and redraws the page on success. */
    static void tell(GuildsServices services, Player viewer, Menu page, GuildOutcome outcome) {
        services.messages().send(viewer, outcome.messageKey());
        if (page != null) {
            page.refresh();
        }
    }

    /**
     * Asks for a line of text in chat — the one thing a menu cannot ask — then reopens {@code back}.
     * "cancel" leaves things as they are.
     */
    static void ask(GuildsServices services, Player viewer, Menu back, String questionKey, Consumer<String> answer,
                    Object... values) {
        viewer.closeInventory();
        boolean asking = services.core().prompts().ask(viewer.getUniqueId(), "guilds", TO_ANSWER,
                typed -> {
                    if (typed == null || typed.strip().equalsIgnoreCase("cancel")) {
                        services.messages().send(viewer, "guilds.left-as-it-is");
                    } else {
                        answer.accept(typed.strip());
                    }
                    if (back != null) {
                        back.open();
                    }
                },
                () -> {
                    if (back != null) {
                        back.open();
                    }
                });
        if (!asking) {
            services.messages().send(viewer, "guilds.busy");
            if (back != null) {
                back.open();
            }
            return;
        }
        services.messages().send(viewer, questionKey, values);
    }
}
