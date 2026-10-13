package de.raindancer.modules.ranks.screen;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ranks.RanksServices;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.function.Consumer;

/** A screen belonging to this module, keeping to the menu grammar the other modules keep. */
public interface IRanksScreen {

    Duration TO_ANSWER = Duration.ofSeconds(60);

    String describe();

    /**
     * Asks for a line of text in chat — the one thing a menu cannot ask — then reopens {@code back}.
     * "cancel" leaves things as they are.
     */
    static void ask(RanksServices services, Player viewer, Menu back, String questionKey, Consumer<String> answer) {
        viewer.closeInventory();
        boolean asking = services.core().prompts().ask(viewer.getUniqueId(), "ranks", TO_ANSWER,
                typed -> {
                    if (typed != null && !typed.strip().equalsIgnoreCase("cancel")) {
                        answer.accept(typed.strip());
                    }
                    back.open();
                },
                back::open);
        if (!asking) {
            services.messages().send(viewer, "ranks.busy");
            back.open();
            return;
        }
        services.messages().send(viewer, questionKey);
    }
}
