package de.raindancer.modules.xaeromap.util;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A reader for the tags {@link NbtOut} writes, for the tests to check them with.
 *
 * <h2>Why a second implementation rather than reusing the writer</h2>
 * Because a test that encodes and decodes with the same code proves only that the code agrees with
 * itself, and the thing at risk here is agreement with somebody else's decoder — {@code
 * FriendlyByteBuf.readAnySizeNbt} inside a client we cannot run. This reader is written from the NBT
 * format as Minecraft defines it (a nameless root since 1.20.2, big-endian, modified-UTF-8 keys),
 * independently of the writer, so the two agreeing is evidence rather than a tautology.
 */
public final class Nbt {

    private Nbt() {
    }

    /** The root compound of a nameless tag, as a map. */
    public static Map<String, Object> read(byte[] bytes) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            int type = in.readByte();
            if (type != 10) {
                throw new AssertionError("the root tag is type " + type + ", not a compound — a "
                        + "client's readAnySizeNbt would reject this outright");
            }
            return compound(in);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The same, for a packet whose first byte names the packet type. */
    public static Map<String, Object> readPayload(byte[] packet) {
        byte[] payload = new byte[packet.length - 1];
        System.arraycopy(packet, 1, payload, 0, payload.length);
        return read(payload);
    }

    /** A uuid stored the way {@code NbtUtils} stores one: four ints, most significant first. */
    public static UUID uuid(Object value) {
        int[] parts = (int[]) value;
        long most = (long) parts[0] << 32 | parts[1] & 0xFFFFFFFFL;
        long least = (long) parts[2] << 32 | parts[3] & 0xFFFFFFFFL;
        return new UUID(most, least);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Object> compound(DataInputStream in) throws IOException {
        Map<String, Object> entries = new LinkedHashMap<>();
        while (true) {
            int type = in.readByte();
            if (type == 0) {
                return entries;
            }
            String key = in.readUTF();
            entries.put(key, value(in, type));
        }
    }

    private static Object value(DataInputStream in, int type) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();
            case 3 -> in.readInt();
            case 8 -> in.readUTF();
            case 9 -> {
                int elementType = in.readByte();
                int size = in.readInt();
                List<Object> items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    items.add(elementType == 10 ? compound(in) : value(in, elementType));
                }
                yield items;
            }
            case 10 -> compound(in);
            case 11 -> {
                int[] values = new int[in.readInt()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readInt();
                }
                yield values;
            }
            case 12 -> {
                long[] values = new long[in.readInt()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readLong();
                }
                yield values;
            }
            default -> throw new AssertionError("tag type " + type + " is not one this module writes");
        };
    }
}
