package de.raindancer.modules.tpa.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.model.TpaPrefs;
import de.raindancer.modules.tpa.store.TpaPrefsFile;
import de.raindancer.modules.tpa.store.TpaRequests;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A choice that could not be saved is never reported as made: a block that is not there protects nobody. */
class TpaPrefsServiceTest {

    private final TpaPrefsFile file = mock(TpaPrefsFile.class);
    private final Messages messages = mock(Messages.class);
    private final TpaPrefsService service = new TpaPrefsService(file, new TpaRequests(), messages, TpaSettings.DEFAULTS);
    private final Player alex = mock(Player.class);
    private final OfflinePlayer steve = mock(OfflinePlayer.class);

    TpaPrefsServiceTest() {
        when(alex.getUniqueId()).thenReturn(UUID.randomUUID());
        when(alex.getName()).thenReturn("Alex");
        when(steve.getUniqueId()).thenReturn(UUID.randomUUID());
        when(steve.getName()).thenReturn("Steve");
        when(file.of(any())).thenReturn(TpaPrefs.untouched());
        when(file.change(any(), anyString(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("a block that could not be saved says so, and is not reported as done")
    void blockNotSaved() {
        assertThat(service.block(alex, steve)).isFalse();
        verify(messages).send(eq(alex), eq("tpa.not-saved"), any(Object[].class));
        verify(messages, never()).send(eq(alex), eq("tpa.blocked"), any(Object[].class));
    }

    @Test
    @DisplayName("switching requests off that could not be saved says so")
    void refusingNotSaved() {
        service.set(alex, false);
        verify(messages).send(eq(alex), eq("tpa.not-saved"), any(Object[].class));
        verify(messages, never()).send(eq(alex), eq("tpa.now-refusing"), any(Object[].class));
    }
}
