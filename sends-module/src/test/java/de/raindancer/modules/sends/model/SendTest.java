package de.raindancer.modules.sends.model;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SendTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static ItemStack stack(int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(amount);
        return item;
    }

    private static Send send(long now, long lifetime) {
        return Send.create(ALICE, "Alice", BOB, "Bob", List.of(stack(3), stack(64)), now, lifetime);
    }

    @Test
    @DisplayName("a new send is pending and expires one lifetime after it was made")
    void expiresAfterTheLifetime() {
        Send send = send(1_000, 600_000);
        assertThat(send.state()).isEqualTo(Send.State.PENDING);
        assertThat(send.expiresAt()).isEqualTo(601_000);
        assertThat(send.isExpiredAt(601_000 - 1)).isFalse();
        assertThat(send.isExpiredAt(601_000)).isTrue();
        assertThat(send.millisLeft(1_000)).isEqualTo(600_000);
        assertThat(send.millisLeft(700_000)).isZero();
    }

    @Test
    @DisplayName("only a pending send can expire")
    void settledNeverExpires() {
        Send done = send(0, 10).withState(Send.State.DELIVERED, 5);
        assertThat(done.isExpiredAt(1_000_000)).isFalse();
    }

    @Test
    @DisplayName("a state change keeps the items and records when it happened")
    void withStateKeepsItems() {
        Send send = send(0, 10);
        Send returning = send.withState(Send.State.RETURNING, 42);
        assertThat(returning.items()).isEqualTo(send.items());
        assertThat(returning.settledAt()).isEqualTo(42);
        assertThat(returning.id()).isEqualTo(send.id());
    }

    @Test
    @DisplayName("the items are counted, not the stacks")
    void totalItems() {
        assertThat(send(0, 10).totalItems()).isEqualTo(67);
    }

    @Test
    @DisplayName("the short id is the first eight characters and is what a command can match")
    void shortId() {
        Send send = send(0, 10);
        assertThat(send.shortId()).hasSize(8).isEqualTo(send.id().toString().substring(0, 8));
    }

    @Test
    @DisplayName("a send to yourself or of nothing cannot be made")
    void refusesNonsense() {
        assertThatThrownBy(() -> Send.create(ALICE, "A", ALICE, "A", List.of(stack(1)), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Send.create(ALICE, "A", BOB, "B", List.of(), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("open means somebody still has a claim on the items")
    void openStates() {
        assertThat(Send.State.PENDING.isOpen()).isTrue();
        assertThat(Send.State.RETURNING.isOpen()).isTrue();
        assertThat(Send.State.DELIVERED.isOpen()).isFalse();
        assertThat(Send.State.RETURNED.isOpen()).isFalse();
    }
}
