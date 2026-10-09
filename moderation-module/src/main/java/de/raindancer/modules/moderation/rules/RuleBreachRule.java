package de.raindancer.modules.moderation.rules;

import de.raindancer.core.moderation.rules.RulePenalty;
import de.raindancer.core.moderation.rules.ServerRule;
import de.raindancer.modules.moderation.model.ModerationPermission;

import java.util.Optional;

/**
 * What breaking one of the server's rules costs somebody, given how often they broke it before — the rung of
 * the rule's own ladder everybody can read in {@code /rules}, not a moderator's guess on the day.
 */
public final class RuleBreachRule implements IModerationRule {

    /**
     * @param offence which offence this is, counting from one
     * @param reason  what the record says: the rule by number and title, and the moderator's note
     */
    public record Breach(ServerRule rule, RulePenalty penalty, int offence, String reason) {
    }

    /** @return empty when the rule has no ladder — there is nothing fixed to hand out */
    public Optional<Breach> breach(ServerRule rule, int priorOffences, String note) {
        String added = note == null ? "" : note.strip();
        String reason = "Broke rule " + rule.number() + ": " + rule.title() + (added.isEmpty() ? "" : " — " + added);
        return rule.forOffence(priorOffences)
                .map(penalty -> new Breach(rule, penalty, Math.max(0, priorOffences) + 1, reason));
    }

    /** The node handing this out by hand needs; a ban's length is then the ban limit's to judge. */
    public static ModerationPermission permissionFor(RulePenalty penalty) {
        return switch (penalty.kind()) {
            case WARNING -> ModerationPermission.WARN;
            case KICK -> ModerationPermission.KICK;
            case MUTE -> ModerationPermission.MUTE;
            case FREEZE -> ModerationPermission.FREEZE;
            case FINE -> ModerationPermission.FINE;
            case BAN -> ModerationPermission.TEMPBAN;
        };
    }

    @Override
    public String describe() {
        return "what breaking one of the server's rules costs, from its own ladder";
    }
}
