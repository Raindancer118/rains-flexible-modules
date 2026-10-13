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
}
