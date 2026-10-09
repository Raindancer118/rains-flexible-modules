package de.raindancer.modules.invsnap.store;

import de.raindancer.modules.invsnap.model.ItemPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItemPolicyStoreTest {

    @TempDir
    Path folder;

    private final UUID owner = UUID.randomUUID();

    private ItemPolicy policy(String id) {
        return new ItemPolicy(id, owner, "Diamond Sword", "DIAMOND_SWORD", 5_000, 500, 123_456L, "", false);
    }

    @Test
    @DisplayName("a policy survives a restart, with every field")
    void policiesPersist() {
        new ItemPolicyStore(folder).put(policy("p1"));

        ItemPolicy back = new ItemPolicyStore(folder).get("p1");

        assertThat(back).isEqualTo(policy("p1"));
    }

    @Test
    @DisplayName("an ended policy is told about once, and forgotten")
    void endedPolicies() {
        ItemPolicyStore store = new ItemPolicyStore(folder);
        store.put(policy("p1").endedBy(ItemPolicy.UNPAID));

        assertThat(store.inForceOf(owner)).isEmpty();
        assertThat(store.endedUntoldOf(owner)).hasSize(1);
        assertThat(new ItemPolicyStore(folder).endedUntoldOf(owner)).hasSize(1);
    }

    @Test
    @DisplayName("items waiting to be collected are on disk the moment they are added")
    void returnsPersistImmediately() {
        ItemPolicyStore store = new ItemPolicyStore(folder);

        assertThat(store.addReturn(owner, "AAAA")).isTrue();
        assertThat(store.addReturn(owner, "BBBB")).isTrue();

        assertThat(new ItemPolicyStore(folder).returnsOf(owner)).containsExactly("AAAA", "BBBB");

        store.setReturns(owner, List.of("BBBB"));
        assertThat(new ItemPolicyStore(folder).returnsOf(owner)).containsExactly("BBBB");
        store.setReturns(owner, List.of());
        assertThat(new ItemPolicyStore(folder).returnsOf(owner)).isEmpty();
    }
}
