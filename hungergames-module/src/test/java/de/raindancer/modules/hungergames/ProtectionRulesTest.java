package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.ProtectionRules;
import de.raindancer.modules.hungergames.rules.ProtectionRules.ActionType;
import de.raindancer.modules.hungergames.rules.ProtectionRules.Config;
import de.raindancer.modules.hungergames.rules.ProtectionRules.Query;
import de.raindancer.modules.hungergames.rules.ProtectionRules.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protection rules from an ordinary player's point of view (no OP, no bypass permission) — the central
 * requirement is that the cornucopia is entirely open once the round is RUNNING.
 */
class ProtectionRulesTest {

    private final ProtectionRules rules = new ProtectionRules(Config.defaults());

    private static Query normalPlayer(Region region, ActionType action, GamePhase phase, String material) {
        return new Query(region, action, phase, false, material);
    }

    @Test
    @DisplayName("RUNNING: an ordinary player may do anything in the cornucopia -- chests, breaking, placing")
    void cornucopiaFreeDuringRunning() {
        for (ActionType action : ActionType.values()) {
            assertFalse(rules.shouldDeny(normalPlayer(
                            Region.CORNUCOPIA, action, GamePhase.RUNNING, "STONE")),
                    action + " must be allowed during RUNNING");
        }
        assertFalse(rules.shouldDeny(normalPlayer(
                Region.CORNUCOPIA, ActionType.CONTAINER, GamePhase.RUNNING, "CHEST")));
    }

    @Test
    @DisplayName("Before the round (LOBBY/READY): the cornucopia is protected for ordinary players")
    void cornucopiaProtectedPreGame() {
        for (GamePhase phase : new GamePhase[]{GamePhase.LOBBY, GamePhase.STARTUP, GamePhase.READY}) {
            assertTrue(rules.shouldDeny(normalPlayer(
                    Region.CORNUCOPIA, ActionType.BREAK, phase, "STONE")));
            assertTrue(rules.shouldDeny(normalPlayer(
                    Region.CORNUCOPIA, ActionType.PLACE, phase, "STONE")));
        }
    }

    @Test
    @DisplayName("Before the round, allowed-material chests stay usable")
    void chestsUsablePreGame() {
        assertFalse(rules.shouldDeny(normalPlayer(
                Region.CORNUCOPIA, ActionType.CONTAINER, GamePhase.LOBBY, "CHEST")));
        assertFalse(rules.shouldDeny(normalPlayer(
                Region.CORNUCOPIA, ActionType.INTERACT, GamePhase.LOBBY, "BARREL")));
        // But no breaking, not even of a chest.
        assertTrue(rules.shouldDeny(normalPlayer(
                Region.CORNUCOPIA, ActionType.BREAK, GamePhase.LOBBY, "CHEST")));
    }

    @Test
    @DisplayName("The arena region is never plugin-protected")
    void arenaNeverProtected() {
        for (GamePhase phase : GamePhase.values()) {
            assertFalse(rules.shouldDeny(normalPlayer(
                    Region.ARENA, ActionType.BREAK, phase, "STONE")));
        }
    }

    @Test
    @DisplayName("The bypass permission overrides every protection")
    void bypassAlwaysAllowed() {
        assertFalse(rules.shouldDeny(new Query(
                Region.CORNUCOPIA, ActionType.BREAK, GamePhase.LOBBY, true, "STONE")));
    }

    @Test
    @DisplayName("Configurable: protection during RUNNING can be switched on")
    void runningProtectionConfigurable() {
        ProtectionRules strict = new ProtectionRules(new Config(true, true, true,
                java.util.Set.of("CHEST")));

        assertTrue(strict.shouldDeny(normalPlayer(
                Region.CORNUCOPIA, ActionType.BREAK, GamePhase.RUNNING, "STONE")));
        // Chests stay usable even with protection switched on.
        assertFalse(strict.shouldDeny(normalPlayer(
                Region.CORNUCOPIA, ActionType.CONTAINER, GamePhase.RUNNING, "CHEST")));
    }

    @Test
    @DisplayName("Configurable: pre-game protection can be switched off")
    void preGameProtectionConfigurable() {
        ProtectionRules open = new ProtectionRules(new Config(false, false, false, java.util.Set.of()));

        assertFalse(open.shouldDeny(normalPlayer(
                Region.CORNUCOPIA, ActionType.BREAK, GamePhase.LOBBY, "STONE")));
    }
}
