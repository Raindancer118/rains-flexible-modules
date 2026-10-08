package de.raindancer.modules.moderation;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.testkit.TestItems;
import de.raindancer.modules.moderation.model.ArmourPiece;
import de.raindancer.modules.moderation.model.Vault;
import de.raindancer.modules.moderation.store.VaultStorage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A vault survives a restart — including the items this server cannot read today. */
class VaultStorageTest {

    /** "MATERIAL:amount" as bytes, which is all these tests need an item to be. */
    private static final class PlainBytes implements ItemBytes {

        @Override
        public int dataVersion() {
            return 1;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return (item.getType().name() + ":" + item.getAmount()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isNothing(ItemStack item) {
            return item == null || item.isEmpty();
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] bytes) {
            String[] parts = new String[0];
            try {
                parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                return Optional.of(TestItems.of(Material.valueOf(parts[0]), Integer.parseInt(parts[1])));
            } catch (RuntimeException notOurs) {
                return Optional.empty();
            }
        }
    }

    private final UUID owner = UUID.randomUUID();

    private VaultStorage storage(Path folder) {
        return new VaultStorage(folder, new ItemText(new PlainBytes()));
    }

    @Test
    @DisplayName("items and armour come back as they went in")
    void roundTrip(@TempDir Path folder) {
        Vault vault = new Vault(10);
        vault.deposit(TestItems.of(Material.DIRT, 40));
        vault.deposit(TestItems.of(Material.MACE));
        vault.deposit(TestItems.of(Material.NETHERITE_LEGGINGS));

        assertThat(storage(folder).save(owner, vault.contents())).isTrue();
        Vault back = storage(folder).load(owner, 10);

        assertThat(back.items()).extracting(ItemStack::getType).containsExactly(Material.DIRT, Material.MACE);
        assertThat(back.items().getFirst().getAmount()).isEqualTo(40);
        assertThat(back.armour(ArmourPiece.LEGS)).get().extracting(ItemStack::getType)
                .isEqualTo(Material.NETHERITE_LEGGINGS);
    }

    @Test
    @DisplayName("nobody's vault yet is an empty one, not an error")
    void missingIsEmpty(@TempDir Path folder) {
        assertThat(storage(folder).load(owner, 10).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an item this server cannot read is kept on disk rather than dropped at the next save")
    void unreadableSurvives(@TempDir Path folder) throws Exception {
        String unreadable = Base64.getEncoder().encodeToString("MODDED_THING:1".getBytes(StandardCharsets.UTF_8));
        Path file = folder.resolve("vaults").resolve(owner + ".yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "items:\n- " + unreadable + "\n- "
                + Base64.getEncoder().encodeToString("STONE:3".getBytes(StandardCharsets.UTF_8)) + "\n");

        Vault loaded = storage(folder).load(owner, 10);
        loaded.deposit(TestItems.of(Material.DIRT, 1));
        storage(folder).save(owner, loaded.contents());

        assertThat(loaded.items()).extracting(ItemStack::getType).containsExactly(Material.STONE, Material.DIRT);
        assertThat(Files.readString(file)).contains(unreadable);
    }

    @Test
    @DisplayName("an older picture landing after a newer one does not overwrite it")
    void staleWritesAreSkipped(@TempDir Path folder) {
        VaultStorage storage = storage(folder);
        Vault vault = new Vault(10);
        vault.deposit(TestItems.of(Material.DIRT, 1));
        Vault.Contents older = vault.contents();
        vault.deposit(TestItems.of(Material.STONE, 1));

        storage.save(owner, vault.contents());
        storage.save(owner, older);

        assertThat(storage.load(owner, 10).items()).extracting(ItemStack::getType)
                .containsExactly(Material.DIRT, Material.STONE);
    }
}
