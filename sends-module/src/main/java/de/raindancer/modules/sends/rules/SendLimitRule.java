package de.raindancer.modules.sends.rules;

import java.util.Optional;

/** How many sends one player may have waiting at once. */
public final class SendLimitRule implements ISendsRule {

    public Optional<String> refusal(int openBySender, int most) {
        return openBySender >= most ? Optional.of("sends.refuse.limit") : Optional.empty();
    }

    @Override
    public String describe() {
        return "a player has at most a set number of sends waiting";
    }
}
