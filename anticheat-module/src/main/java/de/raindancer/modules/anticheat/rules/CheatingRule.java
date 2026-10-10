package de.raindancer.modules.anticheat.rules;

import de.raindancer.core.moderation.rules.ServerRule;
import de.raindancer.core.moderation.rules.ServerRules;

import java.util.List;
import java.util.Optional;

/** Which of the server's rules a cheat breaks, and how often the anti-cheat may hand out its punishment. */
public final class CheatingRule implements IAntiCheatRule {

    private static final List<String> WORDS = List.of("cheat", "hack");
    static final long COOLDOWN_MILLIS = 5 * 60_000;

    /**
     * @param setting "auto", "off" or a rule's number in /rules
     * @return empty when no rule fits or the one that does has no punishments
     */
    public Optional<ServerRule> choose(String setting, List<ServerRule> rules) {
        String wanted = setting == null ? "" : setting.strip();
        if (wanted.equalsIgnoreCase("off")) {
            return Optional.empty();
        }
        Optional<ServerRule> found;
        if (wanted.isEmpty() || wanted.equalsIgnoreCase("auto")) {
            found = ServerRules.about(rules, WORDS);
        } else {
            int number;
            try {
                number = Integer.parseInt(wanted);
            } catch (NumberFormatException notANumber) {
                return Optional.empty();
            }
            found = rules.stream().filter(rule -> rule.number() == number).findFirst();
        }
        return found.filter(rule -> !rule.ladder().isEmpty());
    }

    /** One burst of cheating is one offence, not a climb up the whole ladder within a minute. */
    public boolean mayPunishAgain(Long lastMillis, long nowMillis) {
        return lastMillis == null || nowMillis - lastMillis >= COOLDOWN_MILLIS;
    }

    @Override
    public String describe() {
        return "which of the server's rules a cheat breaks";
    }
}
