package de.raindancer.modules.xaeromap.model;

import de.raindancer.modules.xaeromap.util.Nbt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That each packet is the packet the mod thinks it is.
 *
 * <p>This is a foreign protocol read out of another mod's source, and every field name in it is one
 * letter long — {@code p}, {@code s}, {@code f}, {@code i}. A typo in one is a packet that decodes
 * cleanly with a field missing, which the mod fills in with a zero and draws. So the names and the
 * leading packet byte are pinned here rather than only where they are written.
 */
class OpacPacketsTest {

    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static MapClaim claim(int syncIndex) {
        return new MapClaim(UUID.randomUUID(), OWNER, 0, syncIndex, "Sunset Hill", 0x33CC66);
    }

    @Test
    @DisplayName("the version handshake is never sent, because guessing it kicks everybody")
    void theHandshakeIsNotOursToSend() {
        // OPAC's packet 0 is a network-version check whose *client* handler disconnects the player
        // outright on a mismatch. A server that guesses the number wrong therefore kicks every player
        // running the mod. Nothing in this module may produce packet 0.
        List<byte[]> everything = List.of(
                OpacPackets.handshake(true), OpacPackets.loading(true), OpacPackets.loading(false),
                OpacPackets.claimsReset(), OpacPackets.regionsStart(),
                OpacPackets.dimension("minecraft:overworld"), OpacPackets.dimension(null),
                OpacPackets.claimStates(List.of(claim(1))),
                OpacPackets.subClaimProperties(List.of(claim(1))),
                OpacPackets.ownerProperties(List.of(new OpacPackets.ClaimOwner(OWNER, "Rain"))),
                OpacPackets.claimed("minecraft:overworld", 0, 0, claim(1)),
                OpacPackets.unclaimed("minecraft:overworld", 0, 0),
                new RegionPage(0, 0).encode());

        for (byte[] packet : everything) {
            assertThat(OpacPackets.indexOf(packet))
                    .as("packet 0 is the version handshake, and sending it wrong disconnects players")
                    .isNotZero();
        }
    }

    @Test
    @DisplayName("the handshake says claims yes, parties no")
    void theDimensionHandshakeSaysWhatThisServerHas() {
        Map<String, Object> read = Nbt.readPayload(OpacPackets.handshake(true));

        assertThat(OpacPackets.indexOf(OpacPackets.handshake(true)))
                .isEqualTo(OpacPackets.DIMENSION_HANDSHAKE);
        assertThat(read.get("c")).isEqualTo((byte) 1);
        assertThat(read.get("p"))
                .as("this server has no notion of an OPAC party, and saying it does offers the "
                        + "player a party UI that nothing answers")
                .isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("loading is flagged as being about claims, not parties")
    void loadingIsAboutClaims() {
        Map<String, Object> start = Nbt.readPayload(OpacPackets.loading(true));
        Map<String, Object> end = Nbt.readPayload(OpacPackets.loading(false));

        assertThat(start.get("s")).isEqualTo((byte) 1);
        assertThat(end.get("s")).isEqualTo((byte) 0);
        assertThat(start.get("c"))
                .as("with c false the mod applies this to its party storage, and the claim sync is "
                        + "never marked as finished — claims stay hidden behind a loading flag")
                .isEqualTo((byte) 1);
    }

    @Test
    @DisplayName("the reset packet carries nothing at all")
    void theResetIsBare() {
        assertThat(OpacPackets.claimsReset())
                .as("its decoder reads no tag; a payload would be left in the buffer")
                .containsExactly((byte) OpacPackets.CLAIMS_RESET);
    }

    @Test
    @DisplayName("a dimension is named by its key, and a null one closes the run")
    void dimensionsTravelAsTheirKey() {
        assertThat(Nbt.readPayload(OpacPackets.dimension("minecraft:the_nether")).get("d"))
                .isEqualTo("minecraft:the_nether");
        assertThat(Nbt.readPayload(OpacPackets.dimension(null)))
                .as("the absence of 'd' is what ends a run of regions; an empty string is a "
                        + "dimension called nothing")
                .doesNotContainKey("d");
    }

    @Test
    @DisplayName("a claim state carries the identity a region palette refers to")
    void claimStatesCarryTheirIdentity() {
        MapClaim one = claim(7);

        Map<String, Object> read = Nbt.readPayload(OpacPackets.claimStates(List.of(one)));
        Map<String, Object> entry = Nbt.list(read.get("l")).get(0);

        assertThat(Nbt.uuid(entry.get("p"))).isEqualTo(OWNER);
        assertThat(entry.get("s")).isEqualTo(0);
        assertThat(entry.get("i")).isEqualTo(7);
        assertThat(entry.get("f"))
                .as("this server's claims do not force-load chunks, and saying they do puts a "
                        + "forceload marker on every one of them")
                .isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("a claim's name and colour travel together, keyed on owner and sub-index")
    void propertiesCarryNameAndColour() {
        MapClaim one = new MapClaim(UUID.randomUUID(), OWNER, 3, 9, "Sunset Hill", 0x33CC66);

        Map<String, Object> entry = Nbt.list(
                Nbt.readPayload(OpacPackets.subClaimProperties(List.of(one))).get("l")).get(0);

        assertThat(Nbt.uuid(entry.get("p"))).isEqualTo(OWNER);
        assertThat(entry.get("i")).isEqualTo(3);
        assertThat(entry.get("n")).isEqualTo("Sunset Hill");
        assertThat(entry.get("c")).isEqualTo(0x33CC66);
    }

    @Test
    @DisplayName("an owner travels with a name, because a uuid is not something to read on a map")
    void ownersCarryTheirName() {
        Map<String, Object> entry = Nbt.list(Nbt.readPayload(
                OpacPackets.ownerProperties(List.of(new OpacPackets.ClaimOwner(OWNER, "Rain")))).get("l")).get(0);

        assertThat(Nbt.uuid(entry.get("p"))).isEqualTo(OWNER);
        assertThat(entry.get("u")).isEqualTo("Rain");
        assertThat(entry.get("po")).isEqualTo((byte) 0);
    }

    @Test
    @DisplayName("a chunk being claimed says who; one being freed says nobody by omission")
    void claimUpdatesSayWhoOrNobody() {
        Map<String, Object> claimed = Nbt.readPayload(
                OpacPackets.claimed("minecraft:overworld", -3, 4, claim(2)));
        Map<String, Object> freed = Nbt.readPayload(
                OpacPackets.unclaimed("minecraft:overworld", -3, 4));

        assertThat(claimed.get("x")).isEqualTo(-3);
        assertThat(claimed.get("z")).isEqualTo(4);
        assertThat(Nbt.uuid(claimed.get("p"))).isEqualTo(OWNER);
        assertThat(claimed.get("i")).isEqualTo(2);

        assertThat(freed.get("d")).isEqualTo("minecraft:overworld");
        assertThat(freed)
                .as("the mod reads a missing owner as 'unclaim this chunk'; an owner of all zeroes "
                        + "would be a real claim belonging to nobody")
                .doesNotContainKey("p");
    }

    @Test
    @DisplayName("the batch ceilings are the ones the mod refuses to go past")
    void theBatchLimitsAreTheirs() {
        // Past either of these the mod logs "list is too large" and drops the *whole* packet, so a
        // batch of 129 claims does not lose one claim — it loses all 129.
        assertThat(OpacPackets.MAX_STATES_PER_PACKET).isEqualTo(128);
        assertThat(OpacPackets.MAX_PROPERTIES_PER_PACKET).isEqualTo(32);
    }
}
