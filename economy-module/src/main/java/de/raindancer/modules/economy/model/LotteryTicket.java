package de.raindancer.modules.economy.model;

import java.util.List;
import java.util.UUID;

/** One lottery ticket: whose, and the numbers on it, in order. */
public record LotteryTicket(UUID player, List<Integer> numbers) {

    public LotteryTicket {
        numbers = numbers.stream().sorted().toList();
    }

    public String written() {
        return String.join(" ", numbers.stream().map(String::valueOf).toList());
    }

    public static List<Integer> read(String written) {
        return java.util.Arrays.stream(written.trim().split("\\s+")).filter(part -> !part.isEmpty())
                .map(Integer::parseInt).sorted().toList();
    }
}
