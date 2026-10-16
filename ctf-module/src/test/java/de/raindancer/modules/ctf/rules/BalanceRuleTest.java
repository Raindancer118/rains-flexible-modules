package de.raindancer.modules.ctf.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("who plays on which side")
class BalanceRuleTest {

    private final BalanceRule rule = new BalanceRule();

    private static List<UUID> players(int count) {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.add(UUID.nameUUIDFromBytes(("p" + i).getBytes()));
        }
        return all;
    }

    private static int[] sizes(Map<UUID, Integer> assigned, int sides) {
        int[] sizes = new int[sides];
        assigned.values().forEach(side -> sizes[side]++);
        return sizes;
    }

    @Test
    @DisplayName("everybody gets a side, and the sides differ by one at most")
    void even() {
        Map<UUID, Integer> assigned = rule.assign(players(7), 2, Map.of(), List.of(), new Random(1));
        assertThat(assigned).hasSize(7);
        int[] sizes = sizes(assigned, 2);
        assertThat(Math.abs(sizes[0] - sizes[1])).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a wish is kept while it keeps the sides even")
    void wishes() {
        List<UUID> all = players(4);
        Map<UUID, Integer> wishes = new HashMap<>();
        wishes.put(all.get(0), 1);
        wishes.put(all.get(1), 1);
        wishes.put(all.get(2), 1);
        Map<UUID, Integer> assigned = rule.assign(all, 2, wishes, List.of(), new Random(1));
        assertThat(sizes(assigned, 2)).containsExactly(2, 2);
        assertThat(assigned.values().stream().filter(side -> side == 1).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a party stays together")
    void party() {
        List<UUID> all = players(6);
        Set<UUID> party = Set.of(all.get(0), all.get(3), all.get(5));
        Map<UUID, Integer> assigned = rule.assign(all, 2, Map.of(), List.of(party), new Random(3));
        assertThat(party.stream().map(assigned::get).distinct()).hasSize(1);
        assertThat(sizes(assigned, 2)).containsExactly(3, 3);
    }

    @Test
    @DisplayName("a party bigger than half the players is split rather than making the game lopsided")
    void bigParty() {
        List<UUID> all = players(4);
        Set<UUID> party = Set.of(all.get(0), all.get(1), all.get(2));
        Map<UUID, Integer> assigned = rule.assign(all, 2, Map.of(), List.of(party), new Random(3));
        int[] sizes = sizes(assigned, 2);
        assertThat(Math.abs(sizes[0] - sizes[1])).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("somebody joining late goes to the smaller side, their wish if it is as small")
    void late() {
        assertThat(rule.lateSide(new int[]{3, 2}, null)).isEqualTo(1);
        assertThat(rule.lateSide(new int[]{2, 2}, 0)).isEqualTo(0);
        assertThat(rule.lateSide(new int[]{3, 2}, 0)).as("a wish never tips the balance").isEqualTo(1);
    }

    @Test
    @DisplayName("may somebody switch sides: only to a side not bigger than the one they leave")
    void switching() {
        assertThat(rule.maySwitch(new int[]{3, 2}, 0, 1)).isTrue();
        assertThat(rule.maySwitch(new int[]{2, 2}, 0, 1)).isFalse();
        assertThat(rule.maySwitch(new int[]{2, 3}, 0, 1)).isFalse();
    }

    @Test
    @DisplayName("guild war: the two guilds with the most players online are the sides")
    void guilds() {
        List<UUID> all = players(7);
        Map<UUID, String> guildOf = new HashMap<>();
        guildOf.put(all.get(0), "a");
        guildOf.put(all.get(1), "a");
        guildOf.put(all.get(2), "b");
        guildOf.put(all.get(3), "b");
        guildOf.put(all.get(4), "b");
        guildOf.put(all.get(5), "c");
        BalanceRule.GuildSides sides = rule.guildSides(all, guildOf).orElseThrow();
        assertThat(sides.groups()).containsExactly("b", "a");
        assertThat(sides.sides()).hasSize(5);
        assertThat(sides.left()).containsExactlyInAnyOrder(all.get(5), all.get(6));
    }

    @Test
    @DisplayName("no guild war with fewer than two guilds")
    void noGuilds() {
        List<UUID> all = players(3);
        assertThat(rule.guildSides(all, Map.of(all.get(0), "a"))).isEmpty();
    }
}
