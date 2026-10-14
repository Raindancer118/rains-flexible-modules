package de.raindancer.modules.enchants.service;

import de.raindancer.core.RainsCore;
import de.raindancer.modules.enchants.model.AirState;
import de.raindancer.modules.enchants.model.FlightBudget;
import de.raindancer.modules.enchants.rules.AirRule;
import de.raindancer.modules.enchants.store.AirStore;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AirSharingTest {

    private final AirStore store = new AirStore();

    @SuppressWarnings("unchecked")
    private static Player playerWith(Map<NamespacedKey, Object> data) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(call -> data.get(call.<NamespacedKey>getArgument(0)));
        when(pdc.getOrDefault(any(NamespacedKey.class), any(PersistentDataType.class), any()))
                .thenAnswer(call -> data.getOrDefault(call.<NamespacedKey>getArgument(0), call.getArgument(2)));
        org.mockito.Mockito.doAnswer(call -> data.put(call.<NamespacedKey>getArgument(0), call.getArgument(2)))
                .when(pdc).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        return player;
    }

    @Test
    @DisplayName("what one server saves is what the next one reads once Core has put the namespace back")
    void chargeFollows() {
        Map<NamespacedKey, Object> serverA = new HashMap<>();
        AirState state = new AirState();
        state.budget = FlightBudget.of(7_000);
        state.flownMillis = 90_000;
        store.save(playerWith(serverA), state);

        Map<NamespacedKey, Object> serverB = new HashMap<>(serverA);
        AirState arrived = store.load(playerWith(serverB));
        assertThat(arrived.budget.remainingMillis()).isEqualTo(7_000);
        assertThat(arrived.flownMillis).isEqualTo(90_000);
    }

    @Test
    @DisplayName("the namespace the charge lives in is the one handed to Core")
    void sharesItsNamespace() {
        RainsCore core = mock(RainsCore.class);
        new AirService(new AirRule(), store, null, null, null, null).follow(core);
        verify(core).sharePlayerData(AirStore.NAMESPACE);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("a charge that arrives after the player joined replaces the one read too early")
    void arrivalRefreshesCache() {
        RainsCore core = mock(RainsCore.class);
        AirService air = new AirService(new AirRule(), store, null, null, null, null);
        air.follow(core);
        ArgumentCaptor<BiConsumer<Player, Set<String>>> listener = ArgumentCaptor.forClass(BiConsumer.class);
        verify(core).onSharedDataApplied(listener.capture());

        Map<NamespacedKey, Object> data = new HashMap<>();
        Player player = playerWith(data);
        AirState early = new AirState();
        early.budget = FlightBudget.of(60_000);
        air.adopt(player, early);

        data.put(new NamespacedKey(AirStore.NAMESPACE, "flight_ms"), 3_000L);
        data.put(new NamespacedKey(AirStore.NAMESPACE, "flown_ms"), 120_000L);
        listener.getValue().accept(player, Set.of("something-else"));
        assertThat(early.budget.remainingMillis()).isEqualTo(60_000);

        listener.getValue().accept(player, Set.of(AirStore.NAMESPACE));
        assertThat(early.budget.remainingMillis()).isEqualTo(3_000);
        assertThat(early.flownMillis).isEqualTo(120_000);
    }
}
