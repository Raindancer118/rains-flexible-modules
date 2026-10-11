package de.raindancer.modules.sends.rules;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RulesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    @DisplayName("you cannot send to yourself")
    void notToYourself() {
        assertThat(new RecipientRule().refusal(A, A, true)).contains("sends.refuse.self");
    }

    @Test
    @DisplayName("the other player has to be here to be asked")
    void recipientMustBeOnline() {
        assertThat(new RecipientRule().refusal(A, B, false)).contains("sends.refuse.offline");
        assertThat(new RecipientRule().refusal(A, B, true)).isEmpty();
    }

    @Test
    @DisplayName("somebody at their limit may not open another")
    void limit() {
        SendLimitRule rule = new SendLimitRule();
        assertThat(rule.refusal(4, 5)).isEmpty();
        assertThat(rule.refusal(5, 5)).contains("sends.refuse.limit");
        assertThat(rule.refusal(9, 5)).contains("sends.refuse.limit");
    }

    @Test
    @DisplayName("an empty window is not a send")
    void empty() {
        assertThat(new SendableItemsRule(item -> false).refusal(List.of())).contains("sends.refuse.empty");
    }

    @Test
    @DisplayName("an item the server keeps with its holder is refused, and the rest of the window is not judged")
    void unsendable() {
        ItemStack fine = mock(ItemStack.class);
        ItemStack bound = mock(ItemStack.class);
        SendableItemsRule rule = new SendableItemsRule(item -> item == bound);
        assertThat(rule.refusal(List.of(fine))).isEmpty();
        assertThat(rule.refusal(List.of(fine, bound))).contains("sends.refuse.unsendable");
    }

    @Test
    @DisplayName("every rule can say what it decides")
    void describes() {
        assertThat(new RecipientRule().describe()).isNotBlank();
        assertThat(new SendLimitRule().describe()).isNotBlank();
        assertThat(new SendableItemsRule(item -> false).describe()).isNotBlank();
    }
}
