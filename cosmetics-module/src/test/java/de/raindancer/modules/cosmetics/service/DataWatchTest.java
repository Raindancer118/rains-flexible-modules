package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.TravelLook;
import de.raindancer.core.world.teleport.TravelShow;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The teleport look Core is handed is what the player's data holds, also when the data changes after they joined. */
class DataWatchTest {

    private final Map<NamespacedKey, Object> data = new HashMap<>();
    private final Player player = player();
    private final ParticleService particles = mock(ParticleService.class);
    private final TeleportLookService teleports = new TeleportLookService(mock(Plugin.class), mock(Messages.class),
            mock(TravelShow.class), particles, CosmeticsSettings.DEFAULTS);

    @SuppressWarnings("unchecked")
    private Player player() {
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(container.get(any(NamespacedKey.class), any(PersistentDataType.class)))
                .thenAnswer(ask -> data.get(ask.<NamespacedKey>getArgument(0)));
        org.mockito.Mockito.doAnswer(ask -> data.put(ask.getArgument(0), ask.getArgument(2)))
                .when(container).set(any(NamespacedKey.class), any(PersistentDataType.class), any());
        org.mockito.Mockito.doAnswer(ask -> data.remove(ask.<NamespacedKey>getArgument(0)))
                .when(container).remove(any(NamespacedKey.class));
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.getPersistentDataContainer()).thenReturn(container);
        when(who.hasPermission(any(String.class))).thenReturn(true);
        return who;
    }

    @Test
    @DisplayName("a look adopted from another server after the join is played, and one taken away is dropped")
    void followsTheData() {
        DataWatch watch = new DataWatch(particles, teleports, () -> false);
        watch.check(player);
        assertThat(teleports.lookFor(player.getUniqueId())).isEqualTo(TravelLook.SERVERS);

        data.put(NamespacedKey.fromString("rainscosmetics:teleport-depart"), "block.bell.use");
        watch.check(player);

        assertThat(teleports.lookFor(player.getUniqueId()).depart().key()).isEqualTo("block.bell.use");

        data.remove(NamespacedKey.fromString("rainscosmetics:teleport-depart"));
        watch.check(player);

        assertThat(teleports.lookFor(player.getUniqueId())).isEqualTo(TravelLook.SERVERS);
    }

    @Test
    @DisplayName("an adopted look the player may not use here is dropped, not played")
    void adoptedLookIsChecked() {
        when(player.hasPermission(any(String.class))).thenReturn(false);
        DataWatch watch = new DataWatch(particles, teleports, () -> false);

        data.put(NamespacedKey.fromString("rainscosmetics:teleport-depart"), "block.bell.use");
        watch.check(player);

        assertThat(teleports.lookFor(player.getUniqueId())).isEqualTo(TravelLook.SERVERS);
        assertThat(data).doesNotContainKey(NamespacedKey.fromString("rainscosmetics:teleport-depart"));
    }

    @Test
    @DisplayName("following another server's data, the look is taken as it is")
    void followingTakesItAsItIs() {
        when(player.hasPermission(any(String.class))).thenReturn(false);
        DataWatch watch = new DataWatch(particles, teleports, () -> true);

        data.put(NamespacedKey.fromString("rainscosmetics:teleport-depart"), "block.bell.use");
        watch.check(player);

        assertThat(teleports.lookFor(player.getUniqueId()).depart().key()).isEqualTo("block.bell.use");
    }
}
