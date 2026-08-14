package de.raindancer.modules.mannequin.store;

import de.raindancer.modules.mannequin.model.ItemSpec;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.MannequinKind;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("mannequins, one file each, on disk")
class MannequinStoreTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("nothing on disk is an empty list, not an error")
    void loadingNothingIsEmpty() {
        assertThat(new MannequinStore(folder).loadAll()).isEmpty();
    }

    /**
     * Enchants are deliberately not exercised here: reading them back calls {@code
     * Registry.ENCHANTMENT.get(key)}, which — like constructing a real {@code ItemStack} — lazily
     * resolves a Paper server registry that does not exist outside a running server. The encoding
     * itself ({@code "<key>:<level>"} strings) is straightforward enough that this is a live-server
     * concern rather than a unit-testable one; see {@code MannequinEquipServiceTest}'s javadoc for
     * the same limitation elsewhere in this module.
     */
    @Test
    @DisplayName("a mannequin survives a round trip exactly — location, name, skin, flags, material, health")
    void roundTrip() {
        MannequinStore store = new MannequinStore(folder);
        UUID owner = UUID.randomUUID();
        UUID skin = UUID.randomUUID();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", owner, "world", 10, 64, -20)
                .withDisplayName("Dummy")
                .withSkinSource(skin)
                .withEmitsRedstoneSignal(true)
                .withMaxHealthOverride(150.0)
                .withSlot(EquipmentSlot.HEAD, new ItemSpec(Material.DIAMOND_HELMET, Map.of()));

        assertThat(store.save(mannequin)).isTrue();
        List<Mannequin> loaded = store.loadAll();

        assertThat(loaded).hasSize(1);
        Mannequin back = loaded.getFirst();
        assertThat(back.id()).isEqualTo("MQ1");
        assertThat(back.owner()).isEqualTo(owner);
        assertThat(back.world()).isEqualTo("world");
        assertThat(back.x()).isEqualTo(10);
        assertThat(back.y()).isEqualTo(64);
        assertThat(back.z()).isEqualTo(-20);
        assertThat(back.displayName()).isEqualTo("Dummy");
        assertThat(back.skinSource()).isEqualTo(skin);
        assertThat(back.emitsRedstoneSignal()).isTrue();
        assertThat(back.maxHealthOverride()).isEqualTo(150.0);
        assertThat(back.specFor(EquipmentSlot.HEAD).material()).isEqualTo(Material.DIAMOND_HELMET);
    }

    @Test
    @DisplayName("a mannequin with no health override round-trips as no override")
    void noHealthOverrideStaysNull() {
        MannequinStore store = new MannequinStore(folder);
        store.save(Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0));

        assertThat(store.loadAll().getFirst().maxHealthOverride()).isNull();
    }

    @Test
    @DisplayName("a mannequin with no skin round-trips as no skin, not a random default")
    void noSkinStaysNoSkin() {
        MannequinStore store = new MannequinStore(folder);
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);

        store.save(mannequin);

        assertThat(store.loadAll().getFirst().skinSource()).isNull();
    }

    @Test
    @DisplayName("removing deletes the file and nothing else on disk")
    void removeDeletesTheFile() {
        MannequinStore store = new MannequinStore(folder);
        Mannequin a = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0);
        Mannequin b = Mannequin.freshlyPlaced("MQ2", UUID.randomUUID(), "world", 5, 64, 5);
        store.save(a);
        store.save(b);

        assertThat(store.delete("MQ1")).isTrue();

        assertThat(store.loadAll()).extracting(Mannequin::id).containsExactly("MQ2");
    }

    @Test
    @DisplayName("a mannequin's kind round-trips exactly")
    void kindRoundTrips() {
        MannequinStore store = new MannequinStore(folder);
        Mannequin zombie = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0,
                MannequinKind.ZOMBIE);

        store.save(zombie);

        assertThat(store.loadAll().getFirst().kind()).isEqualTo(MannequinKind.ZOMBIE);
    }

    @Test
    @DisplayName("a file written before kinds existed loads as PLAYER, not a crash")
    void aFileWithNoKindDefaultsToPlayer() throws Exception {
        MannequinStore store = new MannequinStore(folder);
        Files.writeString(store.folder().resolve("MQ1.yml"),
                "id: MQ1\nowner: " + UUID.randomUUID() + "\nworld: world\nx: 0\ny: 64\nz: 0\n");

        List<Mannequin> loaded = store.loadAll();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().kind()).isEqualTo(MannequinKind.PLAYER);
    }

    @Test
    @DisplayName("a garbled kind value falls back to PLAYER rather than throwing")
    void aGarbledKindDefaultsToPlayer() throws Exception {
        MannequinStore store = new MannequinStore(folder);
        Files.writeString(store.folder().resolve("MQ1.yml"),
                "id: MQ1\nowner: " + UUID.randomUUID() + "\nworld: world\nx: 0\ny: 64\nz: 0\nkind: DRAGON\n");

        assertThat(store.loadAll().getFirst().kind()).isEqualTo(MannequinKind.PLAYER);
    }

    @Test
    @DisplayName("trusted players round-trip exactly")
    void trustedRoundTrips() {
        MannequinStore store = new MannequinStore(folder);
        UUID friend = UUID.randomUUID();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0)
                .withTrusted(friend);

        store.save(mannequin);

        assertThat(store.loadAll().getFirst().trusted()).containsExactly(friend);
    }

    @Test
    @DisplayName("a mannequin trusted with nobody round-trips as trusted with nobody")
    void noTrustedStaysEmpty() {
        MannequinStore store = new MannequinStore(folder);
        store.save(Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0));

        assertThat(store.loadAll().getFirst().trusted()).isEmpty();
    }

    @Test
    @DisplayName("the claim it belongs to round-trips exactly")
    void claimIdRoundTrips() {
        MannequinStore store = new MannequinStore(folder);
        UUID claimId = UUID.randomUUID();
        Mannequin mannequin = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0)
                .withClaimId(claimId);

        store.save(mannequin);

        assertThat(store.loadAll().getFirst().claimId()).isEqualTo(claimId);
    }

    @Test
    @DisplayName("a mannequin on no claim round-trips as on no claim")
    void noClaimStaysNull() {
        MannequinStore store = new MannequinStore(folder);
        store.save(Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 0, 64, 0));

        assertThat(store.loadAll().getFirst().claimId()).isNull();
    }

    @Test
    @DisplayName("a garbled claim id round-trips as no claim rather than throwing")
    void aGarbledClaimIdDefaultsToNone() throws Exception {
        MannequinStore store = new MannequinStore(folder);
        Files.writeString(store.folder().resolve("MQ1.yml"),
                "id: MQ1\nowner: " + UUID.randomUUID()
                        + "\nworld: world\nx: 0\ny: 64\nz: 0\nclaim-id: not-a-uuid\n");

        assertThat(store.loadAll().getFirst().claimId()).isNull();
    }

    @Test
    @DisplayName("an unreadable file is skipped, not thrown over every other mannequin")
    void anUnreadableFileIsSkipped() throws Exception {
        MannequinStore store = new MannequinStore(folder);
        Mannequin good = Mannequin.freshlyPlaced("MQ2", UUID.randomUUID(), "world", 0, 64, 0);
        store.save(good);
        Files.writeString(store.folder().resolve("MQ1.yml"), "id: MQ1\nowner: not-a-uuid\n");

        List<Mannequin> loaded = store.loadAll();

        assertThat(loaded).extracting(Mannequin::id).containsExactly("MQ2");
    }
}
