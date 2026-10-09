package de.raindancer.modules.economy.rules;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** What a community fund does when it is full, as staff wrote it, and whether its boost is on. */
public final class FundRule implements IEconomyRule {

    public sealed interface Effect {
        /** Payouts from every source are raised by {@code percent} for {@code hours} once the fund is full. */
        record Boost(int percent, int hours) implements Effect {
        }

        /** Console commands run once, when the fund fills. */
        record Commands(List<String> lines) implements Effect {
        }

        /** Just a goal to reach together. */
        record Nothing() implements Effect {
        }
    }

    /** {@code boost <percent> <hours>}, {@code command <a> && <b>}, or blank; empty when it cannot be read. */
    public Optional<Effect> effect(String written) {
        String text = written == null ? "" : written.strip();
        if (text.isEmpty()) {
            return Optional.of(new Effect.Nothing());
        }
        String[] words = text.split("\\s+", 2);
        String verb = words[0].toLowerCase(Locale.ROOT);
        if (verb.equals("boost")) {
            String[] numbers = words.length < 2 ? new String[0] : words[1].strip().split("\\s+");
            if (numbers.length != 2) {
                return Optional.empty();
            }
            try {
                int percent = Integer.parseInt(numbers[0].replace("%", ""));
                int hours = Integer.parseInt(numbers[1]);
                return percent > 0 && percent <= 1000 && hours > 0 && hours <= 24 * 30
                        ? Optional.of(new Effect.Boost(percent, hours)) : Optional.empty();
            } catch (NumberFormatException notANumber) {
                return Optional.empty();
            }
        }
        if (verb.equals("command") && words.length == 2) {
            List<String> lines = Arrays.stream(words[1].split("&&")).map(String::strip)
                    .map(line -> line.startsWith("/") ? line.substring(1) : line).filter(line -> !line.isEmpty()).toList();
            return lines.isEmpty() ? Optional.empty() : Optional.of(new Effect.Commands(lines));
        }
        return Optional.empty();
    }

    public boolean boosting(Effect.Boost boost, long filledAt, long now) {
        return filledAt > 0 && now >= filledAt && now - filledAt <= boost.hours() * 3_600_000L;
    }

    @Override
    public String describe() {
        return "what a community fund does when it is full";
    }
}
