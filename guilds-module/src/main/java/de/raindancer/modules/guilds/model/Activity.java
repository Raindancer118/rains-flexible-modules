package de.raindancer.modules.guilds.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Something a member did that may earn experience and count towards a contract.
 *
 * @param what   lower case: the entity killed ("zombie"), the ore group mined ("iron"), the crop ("wheat");
 *               empty when the kind has no "what"
 * @param amount how many — fish caught, levels spent enchanting, minutes played
 */
public record Activity(XpSource source, String what, long amount) {

    public Activity {
        Objects.requireNonNull(source, "source");
        what = what == null ? "" : what.toLowerCase(Locale.ROOT);
        amount = Math.max(0, amount);
    }

    public static Activity of(XpSource source, String what) {
        return new Activity(source, what, 1);
    }

    public static Activity of(XpSource source, long amount) {
        return new Activity(source, "", amount);
    }
}
