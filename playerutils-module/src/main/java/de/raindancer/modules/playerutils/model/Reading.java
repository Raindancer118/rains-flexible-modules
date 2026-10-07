package de.raindancer.modules.playerutils.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What somebody typed after a command, sorted into who and what.
 *
 * @param target   the name, nickname or selector they gave; empty means themselves
 * @param numbers  every number parameter, given or defaulted
 * @param words    every word parameter, given or defaulted
 * @param switches the switches they gave
 * @param rest     everything after the target, for an action that takes text
 * @param problems what could not be understood; non-empty means do nothing
 */
public record Reading(Optional<String> target, Map<String, Double> numbers, Map<String, String> words,
                      List<String> switches, String rest, List<String> problems) {

    public Reading {
        numbers = Map.copyOf(numbers);
        words = Map.copyOf(words);
        switches = List.copyOf(switches);
        problems = List.copyOf(problems);
        rest = rest == null ? "" : rest;
    }

    public double number(String name) {
        Double value = numbers.get(name);
        return value == null ? Double.NaN : value;
    }

    public String word(String name) {
        return words.getOrDefault(name, "");
    }

    public boolean isOn(String name) {
        return switches.contains(name);
    }

    public boolean isUnderstood() {
        return problems.isEmpty();
    }
}
