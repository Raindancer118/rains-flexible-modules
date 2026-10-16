package de.raindancer.modules.ctf.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Who plays on which side: even sides first, then parties together, then wishes. */
public final class BalanceRule implements ICtfRule {

    /** A guild war's sides: the two guilds, who plays for which, and who is left to watch. */
    public record GuildSides(List<String> groups, Map<UUID, Integer> sides, List<UUID> left) {
    }

    /**
     * @param wishes  side each player asked for, if any
     * @param parties groups that want to be together; a party too big to fit one side evenly is split
     */
    public Map<UUID, Integer> assign(List<UUID> players, int sides, Map<UUID, Integer> wishes,
                                     Collection<Set<UUID>> parties, Random random) {
        Map<UUID, Integer> assigned = new LinkedHashMap<>();
        int[] sizes = new int[sides];
        int most = (players.size() + sides - 1) / sides;
        Set<UUID> playing = new HashSet<>(players);

        List<List<UUID>> groups = new ArrayList<>();
        Set<UUID> grouped = new HashSet<>();
        for (Set<UUID> party : parties) {
            List<UUID> here = party.stream().filter(playing::contains).filter(id -> !grouped.contains(id)).toList();
            if (here.size() > 1 && here.size() <= most) {
                groups.add(here);
                grouped.addAll(here);
            }
        }
        groups.sort(Comparator.comparingInt((List<UUID> group) -> group.size()).reversed());
        for (List<UUID> group : groups) {
            int side = smallestFitting(sizes, group.size(), most, null);
            for (UUID member : group) {
                assigned.put(member, side);
            }
            sizes[side] += group.size();
        }

        List<UUID> rest = new ArrayList<>(players.stream().filter(id -> !grouped.contains(id)).toList());
        Collections.shuffle(rest, random);
        rest.sort(Comparator.comparing((UUID id) -> !wishes.containsKey(id)));
        for (UUID player : rest) {
            Integer wish = wishes.get(player);
            int side = smallestFitting(sizes, 1, most, wish != null && wish >= 0 && wish < sides ? wish : null);
            assigned.put(player, side);
            sizes[side]++;
        }
        return assigned;
    }

    private static int smallestFitting(int[] sizes, int adding, int most, Integer wish) {
        if (wish != null && sizes[wish] + adding <= most) {
            return wish;
        }
        int smallest = 0;
        for (int side = 1; side < sizes.length; side++) {
            if (sizes[side] < sizes[smallest]) {
                smallest = side;
            }
        }
        return smallest;
    }

    /** Somebody joining a running round: the smallest side; their wish only if it is no bigger than that. */
    public int lateSide(int[] sizes, Integer wish) {
        int smallest = 0;
        for (int side = 1; side < sizes.length; side++) {
            if (sizes[side] < sizes[smallest]) {
                smallest = side;
            }
        }
        if (wish != null && wish >= 0 && wish < sizes.length && sizes[wish] <= sizes[smallest]) {
            return wish;
        }
        return smallest;
    }

    /** Whether somebody may move from one side to another without tipping the balance. */
    public boolean maySwitch(int[] sizes, int from, int to) {
        return from != to && sizes[to] < sizes[from];
    }

    /** The two guilds with the most players here become the sides. Empty with fewer than two guilds present. */
    public Optional<GuildSides> guildSides(List<UUID> players, Map<UUID, String> groupOf) {
        Map<String, Integer> counts = new HashMap<>();
        for (UUID player : players) {
            String group = groupOf.get(player);
            if (group != null) {
                counts.merge(group, 1, Integer::sum);
            }
        }
        if (counts.size() < 2) {
            return Optional.empty();
        }
        List<String> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(2).map(Map.Entry::getKey).toList();
        Map<UUID, Integer> sides = new LinkedHashMap<>();
        List<UUID> left = new ArrayList<>();
        for (UUID player : players) {
            int index = top.indexOf(groupOf.get(player));
            if (index >= 0) {
                sides.put(player, index);
            } else {
                left.add(player);
            }
        }
        return Optional.of(new GuildSides(top, sides, left));
    }
}
