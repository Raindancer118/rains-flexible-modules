package de.raindancer.modules.ctf.service;

import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.rules.VoteRule;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lobby's ballot while a countdown runs: which map, which kind of round, which twist. One vote per player per
 * question, changeable until the countdown ends.
 */
public final class VoteService implements ICtfService {

    public enum Question { MAP, TYPE, MUTATOR }

    private final VoteRule rule = new VoteRule();
    private final Random random = new Random();
    private final Map<Question, List<String>> options = new EnumMap<>(Question.class);
    private final Map<Question, Map<UUID, String>> votes = new EnumMap<>(Question.class);
    private volatile boolean open;
    private volatile CtfSettings settings;

    public VoteService(CtfSettings settings) {
        this.settings = settings;
        for (Question question : Question.values()) {
            votes.put(question, new ConcurrentHashMap<>());
        }
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public VoteRule rule() {
        return rule;
    }

    /** Opens a fresh ballot; a question with fewer than two options is not asked. */
    public synchronized void open(List<String> maps, List<String> types, List<String> mutators) {
        options.clear();
        votes.values().forEach(Map::clear);
        if (settings.voteMaps() && maps.size() > 1) {
            options.put(Question.MAP, List.copyOf(maps));
        }
        if (settings.voteTypes() && types.size() > 1) {
            options.put(Question.TYPE, List.copyOf(types));
        }
        if (settings.voteMutators() && mutators.size() > 1) {
            options.put(Question.MUTATOR, List.copyOf(mutators));
        }
        open = true;
    }

    public boolean isOpen() {
        return open;
    }

    public synchronized List<String> options(Question question) {
        return options.getOrDefault(question, List.of());
    }

    public boolean asks(Question question) {
        return !options(question).isEmpty();
    }

    /** @return whether it counted — an option on the ballot, while it is open */
    public synchronized boolean cast(UUID player, Question question, String option) {
        if (!open || !options(question).contains(option)) {
            return false;
        }
        votes.get(question).put(player, option);
        return true;
    }

    public Optional<String> choiceOf(UUID player, Question question) {
        return Optional.ofNullable(votes.get(question).get(player));
    }

    public synchronized Map<String, Integer> counts(Question question) {
        Map<String, Integer> counts = new HashMap<>();
        votes.get(question).values().forEach(option -> counts.merge(option, 1, Integer::sum));
        return counts;
    }

    /** The winner of a question, or empty when it was not asked. */
    public synchronized Optional<String> winner(Question question) {
        List<String> asked = options(question);
        return asked.isEmpty() ? Optional.empty() : Optional.of(rule.winner(asked, counts(question), random));
    }

    public synchronized void close() {
        open = false;
    }

    public void forget(UUID player) {
        votes.values().forEach(map -> map.remove(player));
    }
}
