package de.raindancer.modules.performance.model;

import de.raindancer.modules.performance.rules.LagRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTest {

    private static Report lillys() {
        Attribution busy = new Attribution();
        for (int i = 0; i < 65; i++) {
            busy.count(Cause.vanilla(Cause.Area.ENTITIES));
        }
        for (int i = 0; i < 35; i++) {
            busy.count(Cause.vanilla(Cause.Area.CHUNKS));
        }
        ChunkCensus farm = new ChunkCensus("minecraft:overworld", 72, 79);
        farm.entities("chicken", EntityGroup.ANIMAL, 218);
        Finding crowd = new Finding(Finding.Kind.ANIMAL_CROWD, farm, "chicken", 218,
                List.of(Fix.thin("chicken", 50), Fix.tellOwner()))
                .on("The greate Empire of Hades", List.of(UUID.randomUUID()), List.of("BROTxHades"));
        return new Report(7, Instant.parse("2026-10-09T20:10:27Z"), "the server has been lagging for a minute",
                LagRule.State.LAGGING, 57.2, 78.5, 231.6, 17.5, 10, busy.ranked(), List.of(crowd),
                List.of(Fix.simulationDistance(8)));
    }

    @Test
    @DisplayName("the text says how slow, why, where, whose, and what could be done — in that order")
    void text() {
        String text = String.join("\n", lillys().lines());

        assertThat(text)
                .contains("Report #7")
                .contains("17.5 TPS")
                .contains("57.2 ms")
                .contains("65% mobs and other entities")
                .contains("218 chicken")
                .contains("x 1160 z 1272")
                .contains("The greate Empire of Hades (BROTxHades)")
                .contains("thin to 50")
                .contains("simulation distance 10 → 8");
        assertThat(text.indexOf("TPS")).isLessThan(text.indexOf("entities"));
        assertThat(text.indexOf("entities")).isLessThan(text.indexOf("chicken"));
    }

    @Test
    @DisplayName("a finding keeps its place in the report: fix 1 of report 7 is always the same fix")
    void numbered() {
        Report report = lillys();

        assertThat(report.finding(1)).isPresent();
        assertThat(report.finding(1).orElseThrow().what()).isEqualTo("chicken");
        assertThat(report.finding(2)).isEmpty();
        assertThat(report.finding(0)).isEmpty();
    }

    @Test
    @DisplayName("a finding on nobody's land says so")
    void wild() {
        ChunkCensus pile = new ChunkCensus("minecraft:the_nether", 50, -11);
        pile.entities("item", EntityGroup.ITEM, 400);
        Finding items = new Finding(Finding.Kind.ITEM_PILE, pile, "item", 400, List.of(Fix.clearItems()));

        assertThat(items.landName()).isEqualTo("unclaimed");
        assertThat(items.key()).isEqualTo("ITEM_PILE@minecraft:the_nether/50/-11/item");
    }
}
