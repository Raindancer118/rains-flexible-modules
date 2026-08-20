package de.raindancer.modules.xaeromap.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That what this writes is what a Minecraft client reads.
 *
 * <p>The whole module rides on it: every claim packet is an NBT tag, and a tag a client's decoder
 * rejects is dropped in silence — no error on either side, the map simply stays empty. There is no
 * live test that would notice.
 */
class NbtOutTest {

    @Test
    @DisplayName("the root tag is nameless, which is what a client has expected since 1.20.2")
    void theRootHasNoName() {
        byte[] bytes = new NbtOut().putInt("x", 1).toBytes();

        assertThat(bytes[0])
                .as("the first byte is the compound's type")
                .isEqualTo((byte) 10);
        assertThat(bytes[1])
                .as("a root *name* would follow the type as a two-byte length — the pre-1.20.2 form, "
                        + "which decodes as a compound with none of these fields in it")
                .isEqualTo((byte) 3);
    }

    @Test
    @DisplayName("every type this module writes reads back as what went in")
    void itRoundTrips() {
        UUID owner = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
        byte[] bytes = new NbtOut()
                .putByte("b", 7)
                .putBoolean("t", true)
                .putBoolean("f", false)
                .putInt("i", -4242)
                .putString("s", "minecraft:the_nether")
                .putIntArray("p", new int[] { 1, 2, 3 })
                .putLongArray("d", new long[] { 1L, -1L })
                .putUuid("u", owner)
                .toBytes();

        Map<String, Object> read = Nbt.read(bytes);

        assertThat(read.get("b")).isEqualTo((byte) 7);
        assertThat(read.get("t")).isEqualTo((byte) 1);
        assertThat(read.get("f")).isEqualTo((byte) 0);
        assertThat(read.get("i")).isEqualTo(-4242);
        assertThat(read.get("s")).isEqualTo("minecraft:the_nether");
        assertThat((int[]) read.get("p")).containsExactly(1, 2, 3);
        assertThat((long[]) read.get("d")).containsExactly(1L, -1L);
        assertThat(Nbt.uuid(read.get("u"))).isEqualTo(owner);
    }

    @Test
    @DisplayName("a uuid is four ints, because that is the only form getUUID accepts")
    void uuidsAreFourInts() {
        UUID owner = UUID.randomUUID();

        Map<String, Object> read = Nbt.read(new NbtOut().putUuid("p", owner).toBytes());

        assertThat(read.get("p"))
                .as("written as a string or as two longs, the client's own getUUID throws and the "
                        + "whole packet is discarded")
                .isInstanceOf(int[].class);
        assertThat((int[]) read.get("p")).hasSize(4);
        assertThat(Nbt.uuid(read.get("p"))).isEqualTo(owner);
    }

    @Test
    @DisplayName("a list of compounds keeps its order and its element type")
    void listsCarryCompounds() {
        byte[] bytes = new NbtOut()
                .putCompoundList("l", List.of("first", "second"),
                        (name, entry) -> entry.putString("n", name).putInt("i", name.length()))
                .toBytes();

        List<Map<String, Object>> entries = Nbt.list(Nbt.read(bytes).get("l"));

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("n")).isEqualTo("first");
        assertThat(entries.get(0).get("i")).isEqualTo(5);
        assertThat(entries.get(1).get("n")).isEqualTo("second");
    }

    @Test
    @DisplayName("an empty list is still a typed list of compounds")
    void emptyListsAreStillTyped() {
        byte[] bytes = new NbtOut().putCompoundList("l", List.<String>of(),
                (name, entry) -> entry.putString("n", name)).toBytes();

        assertThat(Nbt.list(Nbt.read(bytes).get("l")))
                .as("getList(key, 10) on the other side asks for a list *of compounds*; an untyped "
                        + "empty list answers as absent instead")
                .isEmpty();
    }

    @Test
    @DisplayName("a tag that has been written out cannot be added to")
    void itIsFinishedOnce() {
        NbtOut tag = new NbtOut().putInt("x", 1);
        tag.toBytes();

        assertThatThrownBy(() -> tag.putInt("y", 2))
                .as("an entry after the end marker is invisible to any reader — worth failing over "
                        + "rather than shipping a packet that is silently missing a field")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("asking twice for the bytes gives the same bytes")
    void itCanBeReadTwice() {
        NbtOut tag = new NbtOut().putInt("x", 1);

        assertThat(tag.toBytes()).isEqualTo(tag.toBytes());
    }
}
