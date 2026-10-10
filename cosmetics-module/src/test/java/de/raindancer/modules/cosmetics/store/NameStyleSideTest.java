package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.text.NameStyle;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NameStyleSideTest {

    private final Identities identities = mock(Identities.class);
    private final Player player = mock(Player.class);
    private final UUID id = UUID.randomUUID();
    private final NameStyleSide side = new NameStyleSide(identities);

    @Test
    @DisplayName("a worn name style is captured as written and put back the same; none is captured as none")
    void roundTrip() {
        when(player.getUniqueId()).thenReturn(id);
        NameStyle gold = NameStyle.parse(NameStyle.parse("#ffaa00").encode());
        when(identities.hasNameStyle(id)).thenReturn(true);
        when(identities.nameStyle(id)).thenReturn(gold);
        String captured = side.capture(player);
        assertThat(captured).isNotBlank();
        side.apply(player, captured);
        verify(identities).setNameStyle(eq(id), eq(NameStyle.parse(captured)));

        when(identities.hasNameStyle(id)).thenReturn(false);
        assertThat(side.capture(player)).isEmpty();
        side.apply(player, null);
        verify(identities).setNameStyle(id, NameStyle.NONE);
    }
}
