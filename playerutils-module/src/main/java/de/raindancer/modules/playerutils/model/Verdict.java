package de.raindancer.modules.playerutils.model;

/**
 * A rule's answer: yes, or no with the message key that says why.
 *
 * @param values placeholder name/value pairs for the message
 */
public record Verdict(boolean allowed, String key, Object... values) {

    public static final Verdict YES = new Verdict(true, "");

    public static Verdict no(String key, Object... values) {
        return new Verdict(false, key, values);
    }
}
