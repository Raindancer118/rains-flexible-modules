package de.raindancer.modules.claims;

import de.raindancer.core.social.economy.BuyableSlots;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.claims.service.ClaimSlotService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimSlotServiceTest {

    @TempDir
    Path dir;

    private final UUID id = UUID.randomUUID();
    private final Messages messages = mock(Messages.class);

    private ClaimSlotService service(ClaimSettings settings) {
        BuyableSlots slots = new BuyableSlots(dir.resolve("bought-slots.yml"), ClaimSlotService.SOURCE, "slot");
        slots.load();
        return new ClaimSlotService(slots, messages, settings);
    }

    @Test
    @DisplayName("by default buying claim slots is off, says so, and needs no economy")
    void offByDefault() {
        ClaimSlotService slots = service(ClaimSettings.DEFAULTS);
        assertThat(slots.switchedOn()).isFalse();
        assertThat(slots.whyNot(id)).contains("Buying claim slots is switched off on this server.");
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        assertThat(slots.buy(player)).isFalse();
        verify(messages).send(eq(player), eq("slot.off"), any(Object[].class));
    }

    @Test
    @DisplayName("a price without the switch sells nothing; the switch without a price says no price is set")
    void switchAndPrice() {
        assertThat(service(ClaimSettings.DEFAULTS.withClaimSlots(false, "500", 0, 0)).whyNot(id))
                .contains("Buying claim slots is switched off on this server.");
        assertThat(service(ClaimSettings.DEFAULTS.withClaimSlots(true, "0", 0, 0)).whyNot(id))
                .contains("No price is set for a claim slot.");
        assertThat(service(ClaimSettings.DEFAULTS.withClaimSlots(true, "500", 0, 0)).whyNot(id)).isEmpty();
    }
}
