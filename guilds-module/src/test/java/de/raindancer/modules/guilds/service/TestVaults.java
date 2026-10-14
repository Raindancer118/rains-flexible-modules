package de.raindancer.modules.guilds.service;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.guilds.store.VaultStore;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real vault store over a test database, with a codec that writes "DIRT:5" for a stack of five dirt and reads it
 * back as a mock — items without a server. Stacks of the same material are similar and merge, as real ones do.
 */
final class TestVaults {

    private TestVaults() {
    }

    static ItemStack stack(Material type, int amount) {
        ItemStack item = mock(ItemStack.class);
        int[] now = {amount};
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenAnswer(call -> now[0]);
        org.mockito.Mockito.doAnswer(call -> {
            now[0] = call.getArgument(0);
            return null;
        }).when(item).setAmount(anyInt());
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.isEmpty()).thenAnswer(call -> now[0] <= 0);
        when(item.asQuantity(anyInt())).thenAnswer(call -> stack(type, call.getArgument(0)));
        when(item.clone()).thenAnswer(call -> stack(type, now[0]));
        when(item.isSimilar(any())).thenAnswer(call -> {
            ItemStack other = call.getArgument(0);
            return other != null && other.getType() == type;
        });
        return item;
    }

    static ItemText codec() {
        return new ItemText(new ItemBytes() {
            @Override
            public int dataVersion() {
                return 0;
            }

            @Override
            public byte[] toBytes(ItemStack item) {
                return (item.getType().name() + ":" + item.getAmount()).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Optional<ItemStack> fromBytes(byte[] bytes) {
                String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                try {
                    return Optional.of(stack(Material.valueOf(parts[0]), Integer.parseInt(parts[1])));
                } catch (RuntimeException unreadable) {
                    return Optional.empty();
                }
            }
        });
    }

    static VaultStore in(Database database) {
        return new VaultStore(database, codec());
    }
}
