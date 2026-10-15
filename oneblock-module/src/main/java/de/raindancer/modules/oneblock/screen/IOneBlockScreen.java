package de.raindancer.modules.oneblock.screen;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.service.Replies;
import org.bukkit.entity.Player;

/**
 * A screen of this module, keeping the menu grammar {@code ScreenGrammarTest} holds against the source: greyed
 * never hidden, every modifier click named in the lore, nothing irreversible without a confirmation, buttons from
 * Core's {@code Icons}, and no refusal that says nothing.
 */
public interface IOneBlockScreen {

    void open();

    default String describe() {
        return getClass().getSimpleName();
    }

    /** Says how it went; a page that changed is redrawn by whoever called. */
    static void tell(Messages messages, Player viewer, Reply reply) {
        Replies.say(messages, viewer, reply);
    }
}
