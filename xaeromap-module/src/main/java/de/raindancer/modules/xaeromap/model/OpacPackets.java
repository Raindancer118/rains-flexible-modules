package de.raindancer.modules.xaeromap.model;

import de.raindancer.modules.xaeromap.util.NbtOut;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

/**
 * The wire format Xaero's Minimap and Xaero's World Map already read claims in.
 *
 * <h2>Why this protocol and not one of our own</h2>
 * Neither Xaero mod has a way for a server to hand it a coloured area. What both of them <em>do</em>
 * have is an implementation of Open Parties and Claims' client API — that is how a modded server draws
 * chunk claims on the minimap, and it is the only path onto that map that does not need a client mod
 * written and shipped for this plugin. So this speaks OPAC: a plugin message on
 * {@code openpartiesandclaims:main}, one leading byte naming the packet, then a nameless NBT tag.
 *
 * <p>The packet numbers and field names below are OPAC's own, at <b>network version 6</b> (the 1.21 /
 * 26.x line). They are a foreign protocol read out of that mod's source, not a contract anybody owes
 * us, so every one of them is named here once rather than spelled out where it is used — a renumbered
 * packet is then one line to change instead of a hunt.
 *
 * <h2>The one packet deliberately not sent</h2>
 * OPAC's own packet 0 is a version handshake, and its <em>client</em> handler disconnects the player
 * outright when the number does not match. A server guessing that number wrong therefore kicks
 * everybody running the mod, with a message about a mod they may not even know they have. So this
 * module never sends it. {@link #regionsStart()} is the probe instead: the client's handler for it
 * simply echoes it back, which tells us the mod is there and reading this numbering, and costs a
 * player nothing if it is not.
 *
 * <p>Every method here is pure — bytes in, bytes out, no server — because the failure mode of a wrong
 * byte is a client that silently shows nothing, which is not something a live test would notice either.
 */
public final class OpacPackets {

    /** The channel both Xaero mods listen to claims on, through the mod that feeds them. */
    public static final String CHANNEL = "openpartiesandclaims:main";

    /** Told the client whether this server has claims and parties at all. */
    public static final int DIMENSION_HANDSHAKE = 1;
    /** Claims are arriving / have all arrived. */
    public static final int LOADING = 9;
    /** Which dimension the regions that follow belong to. */
    public static final int DIMENSION = 10;
    /** A batch of claim identities the region palettes then refer to. */
    public static final int CLAIM_STATES = 12;
    /** One 32 × 32 region of chunks. */
    public static final int REGION = 13;
    /** One chunk changing hands, after the first full sync. */
    public static final int CLAIM_UPDATE = 14;
    /** The name and colour a claim is drawn with. */
    public static final int SUB_CLAIM_PROPERTIES = 15;
    /** Who a claim's owner is, by name. */
    public static final int OWNER_PROPERTIES = 26;
    /** Sent by us as a probe, echoed by the mod; also what OPAC uses to start a sync. */
    public static final int REGIONS_START = 19;
    /** Forget every claim you have from us. */
    public static final int CLAIMS_RESET = 48;

    /** The mod refuses a longer list than this, and drops the whole packet with it. */
    public static final int MAX_STATES_PER_PACKET = 128;
    /** Same, for either properties packet. */
    public static final int MAX_PROPERTIES_PER_PACKET = 32;

    private OpacPackets() {
    }

    /** Claims exist here; parties do not, because this server has no notion of an OPAC party. */
    public static byte[] handshake(boolean claimsEnabled) {
        return packet(DIMENSION_HANDSHAKE, new NbtOut()
                .putBoolean("c", claimsEnabled)
                .putBoolean("p", false)
                .toBytes());
    }

    /** {@code start} true opens a sync, false closes it. The mod hides claims while one is open. */
    public static byte[] loading(boolean start) {
        return packet(LOADING, new NbtOut()
                .putBoolean("s", start)
                .putBoolean("c", true)
                .toBytes());
    }

    /** Empty by design: this packet has no payload at all, only its leading byte. */
    public static byte[] claimsReset() {
        return new byte[] { (byte) CLAIMS_RESET };
    }

    /** The probe. An empty compound, which is what the mod's own copy of this packet carries. */
    public static byte[] regionsStart() {
        return packet(REGIONS_START, new NbtOut().toBytes());
    }

    /**
     * Which dimension the regions after this one are in; {@code null} closes the run.
     *
     * <p>A Bukkit world's key ({@code minecraft:world_nether} and the like) is exactly what the client
     * knows its own level as, so it is sent verbatim rather than translated.
     */
    public static byte[] dimension(String dimensionKey) {
        NbtOut tag = new NbtOut();
        if (dimensionKey != null) {
            tag.putString("d", dimensionKey);
        }
        return packet(DIMENSION, tag.toBytes());
    }

    /** A batch of claim identities. Split by the caller to {@link #MAX_STATES_PER_PACKET}. */
    public static byte[] claimStates(List<MapClaim> claims) {
        return packet(CLAIM_STATES, new NbtOut()
                .putCompoundList("l", claims, (claim, entry) -> entry
                        .putUuid("p", claim.owner())
                        .putInt("s", claim.subIndex())
                        // Never forceloadable: this server's claims do not keep chunks loaded, and
                        // saying they do would show a forceload marker on every one of them.
                        .putBoolean("f", false)
                        .putInt("i", claim.syncIndex()))
                .toBytes());
    }

    /** A batch of owner names. Split by the caller to {@link #MAX_PROPERTIES_PER_PACKET}. */
    public static byte[] ownerProperties(List<ClaimOwner> owners) {
        return packet(OWNER_PROPERTIES, new NbtOut()
                .putCompoundList("l", owners, (owner, entry) -> entry
                        .putUuid("p", owner.id())
                        .putString("u", owner.name())
                        .putBoolean("po", false))
                .toBytes());
    }

    /** A batch of claim names and colours. Split by the caller to {@link #MAX_PROPERTIES_PER_PACKET}. */
    public static byte[] subClaimProperties(List<MapClaim> claims) {
        return packet(SUB_CLAIM_PROPERTIES, new NbtOut()
                .putCompoundList("l", claims, (claim, entry) -> entry
                        .putUuid("p", claim.owner())
                        .putInt("i", claim.subIndex())
                        .putString("n", claim.name())
                        .putInt("c", claim.colour()))
                .toBytes());
    }

    /**
     * One region of 1024 chunks.
     *
     * @param palette the claim sync indices this region's entries point at; entry 0 always means
     *                "no claim", so a palette slot {@code i} is stored as {@code i + 1}
     */
    public static byte[] region(int regionX, int regionZ, int[] palette, int bits, long[] data) {
        return packet(REGION, new NbtOut()
                .putInt("x", regionX)
                .putInt("z", regionZ)
                .putIntArray("p", palette)
                .putByte("b", bits)
                .putLongArray("d", data)
                .toBytes());
    }

    /**
     * One chunk claimed, after the first sync.
     *
     * <p>The mod creates the claim identity itself from the fields here if it has not seen the sync
     * index before, which is why a new claim can be announced chunk by chunk — but its name and colour
     * still have to arrive separately, so {@link #subClaimProperties} goes first or the claim shows up
     * grey and nameless.
     */
    public static byte[] claimed(String dimensionKey, int chunkX, int chunkZ, MapClaim claim) {
        return packet(CLAIM_UPDATE, new NbtOut()
                .putString("d", dimensionKey)
                .putInt("x", chunkX)
                .putInt("z", chunkZ)
                .putInt("i", claim.syncIndex())
                .putUuid("p", claim.owner())
                .putInt("s", claim.subIndex())
                .putBoolean("f", false)
                .toBytes());
    }

    /** One chunk no longer claimed — the same packet with the owner left out, which is how it reads. */
    public static byte[] unclaimed(String dimensionKey, int chunkX, int chunkZ) {
        return packet(CLAIM_UPDATE, new NbtOut()
                .putString("d", dimensionKey)
                .putInt("x", chunkX)
                .putInt("z", chunkZ)
                .toBytes());
    }

    private static byte[] packet(int index, byte[] payload) {
        ByteArrayOutputStream whole = new ByteArrayOutputStream(payload.length + 1);
        whole.write(index);
        whole.write(payload, 0, payload.length);
        return whole.toByteArray();
    }

    /** Which packet a message the client sent us is, or {@code -1} for an empty one. */
    public static int indexOf(byte[] message) {
        return message == null || message.length == 0 ? -1 : message[0] & 0xFF;
    }

    /** An owner, as the map needs them: a uuid and something to write under the claim. */
    public record ClaimOwner(UUID id, String name) {
    }
}
