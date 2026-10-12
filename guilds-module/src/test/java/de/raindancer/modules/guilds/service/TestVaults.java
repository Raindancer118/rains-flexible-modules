package de.raindancer.modules.guilds.service;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.data.stash.StashStore;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.Optional;

/** A real vault store in a test folder, with a codec that never runs: these tests never need an item on disk. */
final class TestVaults {

    private static final ItemBytes NO_BYTES = new ItemBytes() {
        @Override
        public int dataVersion() {
            return 0;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return new byte[0];
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] bytes) {
            return Optional.empty();
        }
    };

    private TestVaults() {
    }

    static StashStore in(Path folder) {
        return new StashStore(folder.resolve("vaults"), new ItemText(NO_BYTES));
    }
}
