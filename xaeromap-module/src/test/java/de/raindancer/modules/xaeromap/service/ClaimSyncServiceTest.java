package de.raindancer.modules.xaeromap.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.xaeromap.Facts;
import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.claims.ClaimSource;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.MapAudience;
import de.raindancer.modules.xaeromap.model.OpacPackets;
import de.raindancer.modules.xaeromap.store.ClaimMirror;
import de.raindancer.modules.xaeromap.store.SyncIndexTable;
import de.raindancer.modules.xaeromap.util.Nbt;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually goes down the wire, and in what order.
 *
 * <p>Every one of these is a rule of somebody else's protocol. Broken, the packets still send, the
 * client still accepts them, and the map draws the wrong thing or nothing — there is no error anywhere
 * for anybody to notice, which is exactly why this is tested at the level of bytes.
 */
class ClaimSyncServiceTest {

    private static final UUID OWNER = UUID.randomUUID();

    private final FakeWire wire = new FakeWire();
    private final List<ClaimFacts> claims = new ArrayList<>();
    private final SyncIndexTable indices = new SyncIndexTable();
    private final ClaimMirror mirror = new ClaimMirror();

    private ClaimSyncService sync;
    private Player player;

    private final ClaimSource source = new ClaimSource() {

        @Override
        public String name() {
            return "a test";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<ClaimFacts> claims() {
            return List.copyOf(claims);
        }
    };

    @BeforeEach
    void setUp() {
        sync = new ClaimSyncService(wire, () -> source, indices, mirror,
                mockedLog(), XaeroMapSettings.DEFAULTS);
        player = playerCalled("Rain");
    }

    /** A log that goes nowhere: this module's log lines are not what any of these tests are about. */
    private static LogChannel mockedLog() {
        return Mockito.mock(LogChannel.class);
    }

    private static Player playerCalled(String name) {
        Player mock = Mockito.mock(Player.class);
        Mockito.when(mock.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(mock.getName()).thenReturn(name);
        return mock;
    }

    /** The client's side of the handshake: it registered the channel, then echoed the probe. */
    private void clientIsReady(Player who) {
        sync.offer(who);
        sync.onClientMessage(who, OpacPackets.regionsStart());
    }

    @Test
    @DisplayName("nothing is sent to a client that has not answered the probe")
    void silenceUntilTheClientAnswers() {
        claims.add(Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));

        sync.refresh(List.of(player));

        assertThat(wire.isEmpty())
                .as("a channel nothing is listening on is a channel another plugin may be using; "
                        + "shouting claims down it is not ours to do")
                .isTrue();
        assertThat(sync.isReady(player.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("a client that registers the channel is offered a handshake and a probe")
    void theOfferIsAHandshakeAndAProbe() {
        sync.offer(player);

        assertThat(wire.packetOrder())
                .containsExactly(OpacPackets.DIMENSION_HANDSHAKE, OpacPackets.REGIONS_START);
        assertThat(wire.all()).allMatch(one -> one.channel().equals(OpacPackets.CHANNEL));
    }

    @Test
    @DisplayName("only the echo of the probe counts as an answer")
    void otherMessagesAreIgnored() {
        sync.offer(player);
        wire.clear();

        // Whatever else a mod sends — a claim request, a config change — is not an answer, and acting
        // on one would be answering a request this server has no business answering.
        sync.onClientMessage(player, new byte[] { (byte) OpacPackets.CLAIM_UPDATE, 0 });
        sync.onClientMessage(player, new byte[0]);

        assertThat(sync.isReady(player.getUniqueId())).isFalse();
        assertThat(wire.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a first sync resets the client, then sends names before the chunks that use them")
    void thefirstSyncIsInTheRightOrder() {
        claims.add(Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));
        sync.refresh(List.of());

        // The client answering the probe *is* the first sync — everything below goes out on the spot,
        // rather than waiting for the next tick of the refresh clock.
        clientIsReady(player);
        List<Integer> order = wire.packetOrder().subList(2, wire.packetOrder().size());

        assertThat(order).startsWith(OpacPackets.CLAIMS_RESET, OpacPackets.LOADING);
        assertThat(order).endsWith(OpacPackets.LOADING);
        assertThat(order.indexOf(OpacPackets.OWNER_PROPERTIES))
                .as("the mod files a claim's name under its owner; a claim whose owner it has never "
                        + "heard of is dropped")
                .isLessThan(order.indexOf(OpacPackets.SUB_CLAIM_PROPERTIES));
        assertThat(order.indexOf(OpacPackets.SUB_CLAIM_PROPERTIES))
                .as("and a claim identity is filed under (owner, sub-index), which is what the "
                        + "properties packet establishes")
                .isLessThan(order.indexOf(OpacPackets.CLAIM_STATES));
    }

    @Test
    @DisplayName("a client that answers on a quiet server still gets its map at once")
    void theFirstClientDoesNotWaitForTheClock() {
        // The refresh clock builds the picture, and before this was fixed a client that answered
        // before the first tick was reset and then sent nothing — a blank map for up to one interval,
        // which on a server whose claims never arrived looks exactly like this module being broken.
        claims.add(Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));

        clientIsReady(player);

        assertThat(wire.count(OpacPackets.CLAIM_UPDATE) + wire.count(OpacPackets.REGION))
                .isPositive();
        assertThat(sync.current().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a small change is sent chunk by chunk rather than as whole regions")
    void smallChangesAreCheap() {
        claims.add(Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));
        clientIsReady(player);
        sync.refresh(List.of(player));
        wire.clear();

        claims.add(Facts.claim("Shed", OWNER, Facts.OVERWORLD, Facts.chunk(5, 5)));
        sync.refresh(List.of(player));

        assertThat(wire.count(OpacPackets.CLAIM_UPDATE)).isEqualTo(1);
        assertThat(wire.count(OpacPackets.REGION))
                .as("a whole region for one new chunk is 1024 entries to say one thing")
                .isZero();

        Map<String, Object> update = Nbt.readPayload(wire.ofPacket(OpacPackets.CLAIM_UPDATE).get(0).message());
        assertThat(update.get("x")).isEqualTo(5);
        assertThat(update.get("z")).isEqualTo(5);
        assertThat(update.get("d")).isEqualTo(Facts.OVERWORLD);
    }

    @Test
    @DisplayName("a large sync goes as regions, and the run of them is closed afterwards")
    void largeSyncsGoAsRegions() {
        long[] many = new long[200];
        for (int i = 0; i < many.length; i++) {
            many[i] = Facts.chunk(i % 20, i / 20);
        }
        claims.add(Facts.claim("Kingdom", OWNER, Facts.OVERWORLD, many));
        clientIsReady(player);

        sync.refresh(List.of(player));

        assertThat(wire.count(OpacPackets.REGION)).isPositive();
        assertThat(wire.count(OpacPackets.CLAIM_UPDATE)).isZero();

        List<FakeWire.Sent> dimensions = wire.ofPacket(OpacPackets.DIMENSION);
        assertThat(dimensions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(Nbt.readPayload(dimensions.get(0).message()).get("d")).isEqualTo(Facts.OVERWORLD);
        assertThat(Nbt.readPayload(dimensions.get(dimensions.size() - 1).message()))
                .as("left open, the mod files whatever comes next under the last dimension named — "
                        + "which draws the nether's claims onto the overworld")
                .doesNotContainKey("d");
    }

    @Test
    @DisplayName("a region carries every claim in it, not only the ones that just changed")
    void regionsAreBuiltFromTheWholePicture() {
        long[] many = new long[200];
        for (int i = 0; i < many.length; i++) {
            many[i] = Facts.chunk(i % 20, i / 20);
        }
        ClaimFacts kingdom = Facts.claim("Kingdom", OWNER, Facts.OVERWORLD, many);
        claims.add(kingdom);
        clientIsReady(player);
        sync.refresh(List.of(player));
        wire.clear();

        // A second big claim in the same region. Its region packet replaces all 1024 entries at once,
        // so built from the difference alone it would blank the first claim.
        long[] more = new long[200];
        for (int i = 0; i < more.length; i++) {
            more[i] = Facts.chunk(i % 20, 10 + i / 20);
        }
        claims.add(Facts.claim("Duchy", UUID.randomUUID(), Facts.OVERWORLD, more));
        sync.refresh(List.of(player));

        List<FakeWire.Sent> regions = wire.ofPacket(OpacPackets.REGION);
        assertThat(regions).isNotEmpty();
        int kingdomsHandle = indices.syncIndexOf(kingdom.id());
        boolean stillThere = regions.stream().anyMatch(region -> {
            int[] palette = (int[]) Nbt.readPayload(region.message()).get("p");
            for (int entry : palette) {
                if (entry == kingdomsHandle) {
                    return true;
                }
            }
            return false;
        });
        assertThat(stillThere)
                .as("a region packet overwrites all 1024 of its chunks, so it has to carry every "
                        + "claim that reaches into it")
                .isTrue();
    }

    @Test
    @DisplayName("a claim that is deleted frees its chunks on the client")
    void deletingAClaimClearsItsChunks() {
        ClaimFacts home = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        claims.add(home);
        clientIsReady(player);
        sync.refresh(List.of(player));
        wire.clear();

        claims.clear();
        sync.refresh(List.of(player));

        assertThat(wire.count(OpacPackets.CLAIM_UPDATE)).isEqualTo(1);
        assertThat(Nbt.readPayload(wire.ofPacket(OpacPackets.CLAIM_UPDATE).get(0).message()))
                .as("without an unclaim the mod keeps drawing the claim over ground nobody owns")
                .doesNotContainKey("p");
    }

    @Test
    @DisplayName("nothing changing means nothing is sent")
    void aQuietServerIsQuiet() {
        claims.add(Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));
        clientIsReady(player);
        sync.refresh(List.of(player));
        wire.clear();

        sync.refresh(List.of(player));
        sync.refresh(List.of(player));

        assertThat(wire.isEmpty())
                .as("a five-second refresh on a server that has not changed must cost nothing at all")
                .isTrue();
    }

    @Test
    @DisplayName("a player is only sent the claims they may see")
    void privateClaimsAreNeverSent() {
        sync.settings(XaeroMapSettings.DEFAULTS.withShownTo(MapAudience.MINE_AND_SHARED));
        Player stranger = playerCalled("Stranger");
        claims.add(Facts.claim("Theirs", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));
        clientIsReady(stranger);

        assertThat(wire.count(OpacPackets.CLAIM_UPDATE)).isZero();
        assertThat(wire.count(OpacPackets.REGION)).isZero();
        assertThat(wire.count(OpacPackets.SUB_CLAIM_PROPERTIES))
                .as("a claim sent and then hidden by the client is still sitting in that client's "
                        + "memory — not sending it is the only way it stays private")
                .isZero();
    }

    @Test
    @DisplayName("a player is sent a claim they are trusted on, in the shared colour")
    void trustedClaimsArriveInTheirOwnColour() {
        sync.settings(XaeroMapSettings.DEFAULTS.withShownTo(MapAudience.MINE_AND_SHARED));
        Player trusted = playerCalled("Trusted");
        claims.add(Facts.claim("Theirs", OWNER, Facts.OVERWORLD, 0L,
                Set.of(trusted.getUniqueId()), Facts.chunk(0, 0)));
        clientIsReady(trusted);

        Map<String, Object> properties = Nbt.list(Nbt.readPayload(
                wire.ofPacket(OpacPackets.SUB_CLAIM_PROPERTIES).get(0).message()).get("l")).get(0);
        assertThat(properties.get("n")).isEqualTo("Theirs");
        assertThat(properties.get("c"))
                .isEqualTo(XaeroMapSettings.DEFAULTS.sharedColour().value());
    }

    @Test
    @DisplayName("claims in two worlds are kept in two worlds")
    void worldsDoNotBleedIntoEachOther() {
        claims.add(Facts.claim("Above", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));
        claims.add(Facts.claim("Below", OWNER, Facts.NETHER, Facts.chunk(0, 0)));
        clientIsReady(player);

        List<String> named = wire.ofPacket(OpacPackets.CLAIM_UPDATE).stream()
                .map(one -> (String) Nbt.readPayload(one.message()).get("d"))
                .toList();
        assertThat(named)
                .as("the same chunk coordinates in two worlds are two different chunks; sent "
                        + "without the world they overwrite each other")
                .containsExactlyInAnyOrder(Facts.OVERWORLD, Facts.NETHER);
    }

    @Test
    @DisplayName("a huge paste is spread over several refreshes rather than sent at once")
    void thebudgetIsRespected() {
        sync.settings(XaeroMapSettings.DEFAULTS.withChunksPerRefresh(16));
        // One claim per region, deliberately: a region packet replaces all 1024 of its chunks at once
        // and cannot be sent in halves, so the budget is spent a whole region at a time. A claim
        // spread across one region would go out in one packet however small the budget was, which is
        // right — and is why this test spreads it across twenty.
        for (int region = 0; region < 20; region++) {
            long[] chunks = new long[20];
            for (int i = 0; i < chunks.length; i++) {
                chunks[i] = Facts.chunk(region * 32 + i, 0);
            }
            claims.add(Facts.claim("Region " + region, OWNER, Facts.OVERWORLD, chunks));
        }
        clientIsReady(player);
        int afterOne = mirror.chunksKnownBy(player.getUniqueId());
        sync.refresh(List.of(player));
        int afterTwo = mirror.chunksKnownBy(player.getUniqueId());

        assertThat(afterOne)
                .as("something has to go out on every refresh, or a claim larger than the budget "
                        + "never arrives at all")
                .isPositive();
        assertThat(afterOne)
                .as("400 chunks over 20 regions cannot all go out under a budget of 16")
                .isLessThan(400);
        assertThat(afterTwo)
                .as("whatever the budget cut short comes on the next refresh")
                .isGreaterThan(afterOne);
    }

    @Test
    @DisplayName("a player who leaves is forgotten, and starts from nothing when they come back")
    void leavingIsForgetting() {
        claims.add(Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));
        clientIsReady(player);
        sync.refresh(List.of(player));

        sync.forget(player.getUniqueId());
        wire.clear();
        sync.refresh(List.of(player));

        assertThat(sync.isReady(player.getUniqueId())).isFalse();
        assertThat(wire.isEmpty()).isTrue();

        clientIsReady(player);
        assertThat(wire.count(OpacPackets.CLAIMS_RESET))
                .as("a client that reconnects has whatever its own cache held; a reset is what "
                        + "makes a fresh sync actually fresh")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("claims switched off means nothing about claims is ever sent")
    void theFeatureCanBeTurnedOff() {
        sync.settings(XaeroMapSettings.DEFAULTS.withClaims(false));
        claims.add(Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0)));

        sync.offer(player);
        sync.refresh(List.of(player));
        sync.begin(player);

        assertThat(wire.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("no claims plugin is not an error, it is an empty map")
    void noClaimsPluginIsFine() {
        ClaimSyncService alone = new ClaimSyncService(wire, () -> ClaimSource.NONE, indices, mirror,
                mockedLog(), XaeroMapSettings.DEFAULTS);
        clientIsReady(player);
        wire.clear();

        alone.onClientMessage(player, OpacPackets.regionsStart());
        alone.refresh(List.of(player));

        assertThat(alone.current().isEmpty()).isTrue();
        assertThat(wire.ofPacket(OpacPackets.REGION)).isEmpty();
        assertThat(wire.ofPacket(OpacPackets.CLAIM_UPDATE)).isEmpty();
    }

    @Test
    @DisplayName("a batch never exceeds what the mod will accept")
    void batchesStayUnderTheModsCeiling() {
        for (int i = 0; i < 300; i++) {
            claims.add(Facts.claim("Claim " + i, UUID.randomUUID(), Facts.OVERWORLD,
                    Facts.chunk(i, 0)));
        }
        clientIsReady(player);

        for (FakeWire.Sent states : wire.ofPacket(OpacPackets.CLAIM_STATES)) {
            assertThat(Nbt.list(Nbt.readPayload(states.message()).get("l")))
                    .as("past 128 the mod drops the whole packet, so an over-long batch loses every "
                            + "claim in it rather than one")
                    .hasSizeLessThanOrEqualTo(OpacPackets.MAX_STATES_PER_PACKET);
        }
        for (FakeWire.Sent properties : wire.ofPacket(OpacPackets.SUB_CLAIM_PROPERTIES)) {
            assertThat(Nbt.list(Nbt.readPayload(properties.message()).get("l")))
                    .hasSizeLessThanOrEqualTo(OpacPackets.MAX_PROPERTIES_PER_PACKET);
        }
        for (FakeWire.Sent owners : wire.ofPacket(OpacPackets.OWNER_PROPERTIES)) {
            assertThat(Nbt.list(Nbt.readPayload(owners.message()).get("l")))
                    .hasSizeLessThanOrEqualTo(OpacPackets.MAX_PROPERTIES_PER_PACKET);
        }
        assertThat(wire.count(OpacPackets.CLAIM_STATES))
                .as("300 claims cannot fit in one batch of 128")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("every claim in a region packet has an identity the client was already given")
    void nothingIsDrawnBeforeItIsNamed() {
        long[] many = new long[200];
        for (int i = 0; i < many.length; i++) {
            many[i] = Facts.chunk(i % 20, i / 20);
        }
        claims.add(Facts.claim("Kingdom", OWNER, Facts.OVERWORLD, many));
        clientIsReady(player);

        Set<Integer> named = new java.util.HashSet<>();
        for (FakeWire.Sent states : wire.ofPacket(OpacPackets.CLAIM_STATES)) {
            Nbt.list(Nbt.readPayload(states.message()).get("l"))
                    .forEach(entry -> named.add((Integer) entry.get("i")));
        }
        for (FakeWire.Sent region : wire.ofPacket(OpacPackets.REGION)) {
            for (int handle : (int[]) Nbt.readPayload(region.message()).get("p")) {
                assertThat(named)
                        .as("a palette entry the client has no identity for is drawn as an empty "
                                + "chunk — the claim is simply missing")
                        .contains(handle);
            }
        }
    }
}
