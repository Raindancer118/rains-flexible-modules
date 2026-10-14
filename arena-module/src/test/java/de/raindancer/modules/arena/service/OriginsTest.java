package de.raindancer.modules.arena.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginsTest {

    private final Origins origins = new Origins();
    private final UUID player = UUID.randomUUID();

    @Test
    void announcedBySurvivalGoesBackToSurvival() {
        origins.arrived(player, true);
        assertEquals("lillyxoxo", origins.backTo(player, "lillyxoxo"));
        assertTrue(origins.cameFromSurvival(player));
    }

    @Test
    void unannouncedGoesBackToTheHub() {
        origins.arrived(player, false);
        assertEquals(Origins.HUB, origins.backTo(player, "lillyxoxo"));
        assertFalse(origins.cameFromSurvival(player));
    }

    @Test
    void unknownPlayerGoesToTheHub() {
        assertEquals(Origins.HUB, origins.backTo(player, "lillyxoxo"));
    }

    @Test
    void lastArrivalWinsAndForgetClears() {
        origins.arrived(player, true);
        origins.arrived(player, false);
        assertEquals(Origins.HUB, origins.backTo(player, "lillyxoxo"));
        origins.arrived(player, true);
        origins.forget(player);
        assertEquals(Origins.HUB, origins.backTo(player, "lillyxoxo"));
    }

    @Test
    void goesBackToTheSurvivalServerThatSentThem() {
        origins.arrived(player, true, "smp-2");
        assertEquals("smp-2", origins.backTo(player, "lillyxoxo"));
        assertEquals("smp-2", origins.homeOf(player).orElseThrow());
    }

    @Test
    void aLinkSendsNoNameAndSoTheConfiguredServerIsUsed() {
        origins.arrived(player, true, "");
        assertEquals("lillyxoxo", origins.backTo(player, "lillyxoxo"));
        assertEquals("", origins.homeOf(player).orElseThrow());
    }

    @Test
    void nobodySentThemMeansNoHome() {
        origins.arrived(player, false, "smp-2");
        assertFalse(origins.homeOf(player).isPresent());
    }
}
