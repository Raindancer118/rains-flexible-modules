package de.raindancer.modules.xaeromap.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Writes exactly the NBT a modern Minecraft client reads off a plugin message, and nothing else.
 *
 * <h2>Why this exists rather than a library</h2>
 * The payloads on the other side of this module are read by {@code FriendlyByteBuf.readAnySizeNbt},
 * which since 1.20.2 expects a <em>nameless</em> tag: the type byte, then the payload, with no root
 * name in between. Bukkit exposes no NBT writer at all, and every library that does either brings a
 * whole NMS mapping with it or still writes the pre-1.20.2 named form — which decodes as a compound
 * whose first key is the empty string, i.e. as a packet with none of the fields in it. That failure
 * is silent on both sides: the client logs nothing and simply shows no claims.
 *
 * <p>Six tag types, which is every type these packets use. Deliberately not a general NBT library:
 * anything more is a second copy of something Minecraft already has, kept in step by hand.
 *
 * <p>Not thread safe, and not meant to be — one instance builds one packet.
 */
public final class NbtOut {

    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG_ARRAY = 12;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);
    private boolean finished;

    /** A compound, open and ready for entries. The type byte is already written. */
    public NbtOut() {
        write(() -> out.writeByte(TAG_COMPOUND));
    }

    public NbtOut putByte(String key, int value) {
        return write(() -> {
            entry(TAG_BYTE, key);
            out.writeByte(value);
        });
    }

    public NbtOut putBoolean(String key, boolean value) {
        return putByte(key, value ? 1 : 0);
    }

    public NbtOut putInt(String key, int value) {
        return write(() -> {
            entry(TAG_INT, key);
            out.writeInt(value);
        });
    }

    public NbtOut putString(String key, String value) {
        return write(() -> {
            entry(TAG_STRING, key);
            out.writeUTF(value);
        });
    }

    public NbtOut putIntArray(String key, int[] values) {
        return write(() -> {
            entry(TAG_INT_ARRAY, key);
            out.writeInt(values.length);
            for (int value : values) {
                out.writeInt(value);
            }
        });
    }

    public NbtOut putLongArray(String key, long[] values) {
        return write(() -> {
            entry(TAG_LONG_ARRAY, key);
            out.writeInt(values.length);
            for (long value : values) {
                out.writeLong(value);
            }
        });
    }

    /**
     * A uuid, in the four-int form {@code NbtUtils} uses.
     *
     * <p>Written any other way — a string, two longs — {@code CompoundTag.getUUID} throws, and the
     * whole packet is dropped by the client's own decoder.
     */
    public NbtOut putUuid(String key, UUID value) {
        long most = value.getMostSignificantBits();
        long least = value.getLeastSignificantBits();
        return putIntArray(key, new int[] {
                (int) (most >> 32), (int) most, (int) (least >> 32), (int) least });
    }

    /**
     * A list of compounds, each written by the given filler into its own {@code NbtOut}.
     *
     * <p>A list carries the type of its elements once, at the front, so an empty list of compounds is
     * still typed — which is what {@code getList(key, 10)} on the other side asks for.
     */
    public <T> NbtOut putCompoundList(String key, Iterable<T> items,
                                      java.util.function.BiConsumer<T, NbtOut> filler) {
        return write(() -> {
            entry(TAG_LIST, key);
            out.writeByte(TAG_COMPOUND);
            java.util.List<byte[]> encoded = new java.util.ArrayList<>();
            for (T item : items) {
                NbtOut element = new NbtOut();
                filler.accept(item, element);
                encoded.add(element.compoundPayload());
            }
            out.writeInt(encoded.size());
            for (byte[] element : encoded) {
                out.write(element);
            }
        });
    }

    /** The finished tag: the type byte, the entries, and the end marker. */
    public byte[] toBytes() {
        finish();
        return bytes.toByteArray();
    }

    /** The entries and the end marker, without the leading type byte — what a list element is. */
    private byte[] compoundPayload() {
        byte[] whole = toBytes();
        byte[] withoutType = new byte[whole.length - 1];
        System.arraycopy(whole, 1, withoutType, 0, withoutType.length);
        return withoutType;
    }

    private void finish() {
        if (finished) {
            return;
        }
        write(() -> out.writeByte(TAG_END));
        finished = true;
    }

    private void entry(byte type, String key) throws IOException {
        out.writeByte(type);
        out.writeUTF(key);
    }

    private NbtOut write(Write what) {
        if (finished) {
            throw new IllegalStateException("this tag has already been written out");
        }
        try {
            what.run();
        } catch (IOException impossible) {
            // A ByteArrayOutputStream does not fail, but DataOutputStream is declared as if it could.
            throw new UncheckedIOException(impossible);
        }
        return this;
    }

    private interface Write {
        void run() throws IOException;
    }
}
