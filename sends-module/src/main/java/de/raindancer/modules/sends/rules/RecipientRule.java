package de.raindancer.modules.sends.rules;

import java.util.Optional;
import java.util.UUID;

/** Who may be sent something: somebody else, and somebody who is here to be asked. */
public final class RecipientRule implements ISendsRule {

    public Optional<String> refusal(UUID sender, UUID recipient, boolean recipientOnline) {
        if (sender.equals(recipient)) {
            return Optional.of("sends.refuse.self");
        }
        if (!recipientOnline) {
            return Optional.of("sends.refuse.offline");
        }
        return Optional.empty();
    }

    @Override
    public String describe() {
        return "the recipient is somebody else, and online";
    }
}
