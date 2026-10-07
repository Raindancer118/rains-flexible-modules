package de.raindancer.modules.anticheat.rules;

/**
 * What a rule found.
 *
 * @param offset how far past the limit, in the rule's own unit — what staff read in the alert
 * @param reason a short phrase: "ascending", "hovering"
 */
public record Judgement(boolean failed, double offset, String reason) {

    public static final Judgement PASS = new Judgement(false, 0, "");

    public static Judgement fail(double offset, String reason) {
        return new Judgement(true, offset, reason);
    }

    public boolean passed() {
        return !failed;
    }
}
