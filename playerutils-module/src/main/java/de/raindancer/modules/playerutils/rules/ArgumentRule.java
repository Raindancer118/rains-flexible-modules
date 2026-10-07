package de.raindancer.modules.playerutils.rules;

import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Parameter;
import de.raindancer.modules.playerutils.model.Reading;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sorts what was typed after a command into a target and the action's parameters — by what each word
 * <em>is</em>, so the order does not matter. Anything that is neither a number, nor a word the action
 * knows, nor a switch, is the target; a second such thing is a mistake and is named.
 */
public final class ArgumentRule implements IPlayerUtilsRule {

    private static final Set<String> ME = Set.of("me", "self", "myself");

    public Reading read(Action action, String[] typed) {
        String[] args = typed == null ? new String[0] : typed;
        List<Parameter> parameters = action.parameters();
        Map<String, Double> numbers = new LinkedHashMap<>();
        Map<String, String> words = new LinkedHashMap<>();
        List<String> switches = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String target = null;
        String rest = "";

        boolean takesRest = parameters.stream().anyMatch(p -> p.kind() == Parameter.Kind.REST);
        if (takesRest) {
            if (args.length > 0) {
                target = args[0];
                rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
            }
        } else {
            for (String token : args) {
                if (token.isBlank()) {
                    continue;
                }
                if (claimWord(parameters, words, switches, token)) {
                    continue;
                }
                Optional<Double> number = number(token);
                if (number.isPresent()) {
                    Optional<Parameter> free = parameters.stream()
                            .filter(p -> p.kind() == Parameter.Kind.NUMBER && !numbers.containsKey(p.name()))
                            .findFirst();
                    if (free.isPresent()) {
                        Parameter slot = free.get();
                        double value = number.get();
                        if (value < slot.min() || value > slot.max()) {
                            problems.add(slot.name() + " goes from " + plain(slot.min()) + " to "
                                    + plain(slot.max()) + ", and " + token + " is not in there");
                        }
                        numbers.put(slot.name(), value);
                        continue;
                    }
                }
                if (target == null) {
                    target = token;
                } else {
                    problems.add("I already know who (" + target + "), so what is '" + token + "'?");
                }
            }
        }

        for (Parameter parameter : parameters) {
            switch (parameter.kind()) {
                case NUMBER -> {
                    if (!numbers.containsKey(parameter.name())) {
                        if (Double.isNaN(parameter.fallback())) {
                            problems.add("I need " + parameter.usage());
                        } else {
                            numbers.put(parameter.name(), parameter.fallback());
                        }
                    }
                }
                case WORD -> words.putIfAbsent(parameter.name(), parameter.words().getFirst());
                case REST -> {
                    if (rest.isBlank()) {
                        problems.add("I need " + parameter.usage());
                    }
                }
                case SWITCH -> {
                }
            }
        }

        Optional<String> who = target == null || ME.contains(target.toLowerCase(Locale.ROOT))
                ? Optional.empty() : Optional.of(target);
        return new Reading(who, numbers, words, switches, rest, problems);
    }

    private static boolean claimWord(List<Parameter> parameters, Map<String, String> words,
                                     List<String> switches, String token) {
        for (Parameter parameter : parameters) {
            Optional<String> word = parameter.wordFor(token);
            if (word.isEmpty()) {
                continue;
            }
            if (parameter.kind() == Parameter.Kind.SWITCH && !switches.contains(parameter.name())) {
                switches.add(parameter.name());
                return true;
            }
            if (parameter.kind() == Parameter.Kind.WORD && !words.containsKey(parameter.name())) {
                words.put(parameter.name(), word.get());
                return true;
            }
        }
        return false;
    }

    /** {@code 3}, {@code 2.5}, {@code 2.5h} (hearts), {@code 2x} (times), {@code 50%}. */
    static Optional<Double> number(String token) {
        String cleaned = token.strip().toLowerCase(Locale.ROOT).replace(',', '.');
        double factor = 1;
        if (cleaned.endsWith("%")) {
            factor = 0.01;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("h") || cleaned.endsWith("x") || cleaned.endsWith("s")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!cleaned.matches("-?\\d+(\\.\\d+)?")) {
            return Optional.empty();
        }
        return Optional.of(Double.parseDouble(cleaned) * factor);
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @Override
    public String describe() {
        return "sorting typed arguments into who and what, in any order";
    }
}
