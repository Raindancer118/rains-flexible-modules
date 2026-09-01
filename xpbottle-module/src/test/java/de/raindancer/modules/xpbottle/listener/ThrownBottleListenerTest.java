package de.raindancer.modules.xpbottle.listener;

import de.raindancer.modules.xpbottle.XpBottleServices;
import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.store.BottleTags;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That a thrown bottle pays out what went into it.
 *
 * <h2>Why {@code BottleTags} is stubbed rather than fed a real stack</h2>
 * Reading a tag means reading an {@code ItemStack}'s persistent data, and {@code ItemStack} answers
 * that through the item factory of a running server — a mock of one runs the real method body and
 * fails before any tag is reached. The codec is a seam here for exactly that reason, and what is
 * under test is the listener's own decision: whether it overrides vanilla's roll, with what, and
 * when it leaves the event alone.
 *
 * <p>What no unit test can reach is whether Paper hands the tagged stack to the projectile at all.
 * That is the half verified on a live server.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThrownBottleListenerTest {

    @Mock
    private XpBottleServices services;
    @Mock
    private ThrownExpBottle projectile;
    @Mock
    private ExpBottleEvent event;

    private ThrownBottleListener listener;

    @BeforeEach
    void setUp() {
        listener = new ThrownBottleListener(services);
        when(services.config()).thenReturn(XpBottleSettings.DEFAULTS);
        when(event.getEntity()).thenReturn(projectile);
        when(projectile.getItem()).thenReturn(mock(ItemStack.class));
    }

    @Test
    @DisplayName("a bottle that took 137 points gives back 137, not vanilla's three to eleven")
    void itPaysOutWhatWentIn() {
        try (MockedStatic<BottleTags> tags = mockStatic(BottleTags.class)) {
            tags.when(() -> BottleTags.read(any(), any()))
                    .thenReturn(Optional.of(new Bottle(0, 137, 200)));

            listener.onSplash(event);
        }

        verify(event).setExperience(137);
        verify(event).setShowEffect(true);
    }

    @Test
    @DisplayName("an untagged vanilla bottle is left entirely alone")
    void vanillaBottlesAreNotTouched() {
        try (MockedStatic<BottleTags> tags = mockStatic(BottleTags.class)) {
            tags.when(() -> BottleTags.read(any(), any())).thenReturn(Optional.empty());

            listener.onSplash(event);
        }

        verify(event, never()).setExperience(anyInt());
        verify(event, never()).setShowEffect(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("an empty bottle pays out nothing rather than falling through to vanilla's roll")
    void anEmptyBottlePaysNothing() {
        try (MockedStatic<BottleTags> tags = mockStatic(BottleTags.class)) {
            tags.when(() -> BottleTags.read(any(), any()))
                    .thenReturn(Optional.of(new Bottle(0, 0, 200)));

            listener.onSplash(event);
        }

        verify(event).setExperience(0);
        verify(event).setShowEffect(false);
    }

    @Test
    @DisplayName("nothing is remembered about anybody, so there is nothing to forget")
    void itRemembersNobody() {
        listener.forget(UUID.randomUUID());

        assertThat(listener.describe()).contains("exactly what went into it");
    }
}
