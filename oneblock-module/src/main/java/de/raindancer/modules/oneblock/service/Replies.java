package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.model.Reply;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Says a {@link Reply}. A value named {@code something-key} is a message key, not a word: it is put into the player's
 * own wording as {@code something} — "the nether", "the SMP economy" — so a reply that crossed the network still
 * reads in the words of the server saying it.
 */
public final class Replies {

    private static final String KEY_SUFFIX = "-key";

    private Replies() {
    }

    public static void say(Messages messages, CommandSender to, Reply reply) {
        if (reply.key().isEmpty()) {
            return;
        }
        messages.send(to, reply.key(), values(messages, reply.values()).toArray());
    }

    static List<Object> values(Messages messages, List<String> pairs) {
        List<Object> out = new ArrayList<>(pairs.size());
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            String name = pairs.get(i);
            String value = pairs.get(i + 1);
            if (name.endsWith(KEY_SUFFIX)) {
                out.add(name.substring(0, name.length() - KEY_SUFFIX.length()));
                out.add(messages.get(value));
            } else {
                out.add(name);
                out.add(value);
            }
        }
        return out;
    }
}
