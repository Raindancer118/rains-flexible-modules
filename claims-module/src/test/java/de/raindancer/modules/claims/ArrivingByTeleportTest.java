package de.raindancer.modules.claims;

import de.raindancer.modules.claims.listener.MovementListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The way-in flags are about coming <em>in</em>, not about moving around once you are.
 *
 * <h2>The bug this is about</h2>
 * An admin standing inside a claim could not ender-pearl to break a fall once the owner switched ender pearls
 * off — with the bypass on. Toggling the bypass appeared to fix it, which made it look as though the bypass
 * were being broken. It was not: the teleport gate never looked at where the teleport came <em>from</em>, so a
 * pearl thrown from inside the claim to inside the same claim was judged as an arrival and refused.
 *
 * <p>Core says the opposite rule out loud for {@link de.raindancer.core.world.protection.LandFlag#WALK_IN}:
 * moving within an area is never refused, and neither is leaving, because somebody who was already inside when
 * the flag was switched off has to be able to move and to get out. The teleport gate simply did not follow it.
 *
 * <p>So the bug hit everybody, not only admins: an owner who switched ender pearls off to keep strangers out
 * also stopped their own trusted friends pearling around inside. The bypass hid it for whoever had one.
 */
class ArrivingByTeleportTest {

    private static final UUID HOUSE = UUID.randomUUID();
    private static final UUID GARDEN = UUID.randomUUID();

    /** The shorthand the gate is written in: is this refused? */
    private static boolean refused(UUID from, UUID to, boolean allowed, boolean bypassing) {
        return MovementListener.refusesArrival(from, to, true, allowed, bypassing);
    }

    @Test
    @DisplayName("a pearl inside the claim is not an arrival")
    void movingAboutInsideIsNeverRefused() {
        assertThat(refused(HOUSE, HOUSE, false, false))
                .as("this is the reported bug: a clutch inside your own claim, refused because the gate only "
                        + "looked at where the pearl landed")
                .isFalse();
    }

    @Test
    @DisplayName("a pearl out of the claim is not an arrival either")
    void leavingIsNeverRefused() {
        assertThat(refused(HOUSE, null, false, false))
                .as("a flag that stops you leaving pins you where you stand")
                .isFalse();
    }

    @Test
    @DisplayName("a pearl in from outside is refused when the flag says so")
    void arrivingFromOutsideIsRefused() {
        assertThat(refused(null, HOUSE, false, false))
                .as("this is what the flag is for; without it the flag does nothing at all")
                .isTrue();
    }

    @Test
    @DisplayName("a pearl from one claim straight into another is an arrival at the second")
    void crossingBetweenClaimsIsRefused() {
        assertThat(refused(GARDEN, HOUSE, false, false))
                .as("being inside somewhere else is not permission to arrive here")
                .isTrue();
    }

    @Test
    @DisplayName("the bypass wins, and cannot be broken by a flag")
    void bypassAlwaysPasses() {
        assertThat(refused(null, HOUSE, false, true))
                .as("an admin's bypass is the whole point of having one — no flag any owner sets may take it "
                        + "away, or an admin cannot get to the thing they were called in to fix")
                .isFalse();
        assertThat(refused(GARDEN, HOUSE, false, true)).isFalse();
    }

    @Test
    @DisplayName("an allowed flag lets anybody in, bypass or not")
    void anAllowedFlagIsNotRefused() {
        assertThat(refused(null, HOUSE, true, false)).isFalse();
    }

    @Test
    @DisplayName("a flag the server does not enforce refuses nothing")
    void anUnenforcedFlagIsNotConsulted() {
        assertThat(MovementListener.refusesArrival(null, HOUSE, false, false, false))
                .as("a flag switched off server-wide must behave as though it did not exist")
                .isFalse();
    }

    @Test
    @DisplayName("a teleport that touches no claim is nobody's business")
    void wildernessIsNotGuarded() {
        assertThat(refused(null, null, false, false)).isFalse();
        assertThat(refused(HOUSE, null, false, false)).isFalse();
    }

    @Test
    @DisplayName("the whole arrival gate is skipped for somebody already here")
    void nothingAboutArrivingIsAskedOfSomebodyWhoNeverLeft() {
        // Not only the flag. The gate behind it checks the ban list, the ENTER permission and the entry fee,
        // and running it on a teleport within one claim meant a pearl could ask a guest to pay the toll again
        // for ground they never left — while walking the same distance is free, because the move handler
        // returns early when the claim has not changed. Two answers to one question is the bug, not the toll.
        java.nio.file.Path listener = java.nio.file.Path.of(
                "src/main/java/de/raindancer/modules/claims/listener/MovementListener.java");
        String body;
        try {
            body = java.nio.file.Files.readString(listener);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("could not read MovementListener", unreadable);
        }

        int at = body.indexOf("public void onTeleport(");
        assertThat(at).isNotNegative();
        String handler = body.substring(at, body.indexOf("\n    @EventHandler", at + 1));

        assertThat(handler)
                .as("the teleport handler has to know whether they were already in this claim")
                .contains("alreadyHere");
        int guard = handler.indexOf("alreadyHere");
        int gate = handler.indexOf("checkGate(");
        assertThat(guard)
                .as("the gate must sit behind that question, not in front of it")
                .isLessThan(gate);
    }

    @Test
    @DisplayName("a refused pearl is given back")
    void therefusedPearlIsRefunded() {
        // The pearl is spent when it is thrown; the teleport is a separate event a second later, and cancelling
        // that does not unthrow anything. So refusing the arrival charged the player for a journey they were not
        // allowed to make — an ender pearl gone every time somebody misjudges a border.
        //
        // Only the pearl. A refused /warp or chorus fruit costs nothing to begin with, and handing something
        // back for those would be inventing an item.
        java.nio.file.Path listener = java.nio.file.Path.of(
                "src/main/java/de/raindancer/modules/claims/listener/MovementListener.java");
        String body;
        try {
            body = java.nio.file.Files.readString(listener);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("could not read MovementListener", unreadable);
        }

        assertThat(body)
                .as("a refusal that still charges the player is worse than either allowing or refusing cleanly")
                .contains("refundPearl(");

        int refund = body.indexOf("private void refundPearl(");
        assertThat(refund).isNotNegative();
        String method = body.substring(refund, body.indexOf("\n    private", refund + 10));
        assertThat(method)
                .as("a teleport event can arrive on a region thread that does not own the inventory")
                .contains("Scheduling.entity(");
        assertThat(method)
                .as("a full inventory must not swallow the refund — that is charging them for being full")
                .contains("dropItemNaturally");

        // Guarded by the cause, so only a pearl is refunded.
        int cancel = body.indexOf("refundPearl(player)");
        assertThat(body.substring(Math.max(0, cancel - 200), cancel))
                .as("refunding every refused teleport would conjure pearls out of warps")
                .contains("if (pearl)");
    }
}
