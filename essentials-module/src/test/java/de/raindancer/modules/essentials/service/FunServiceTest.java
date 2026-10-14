package de.raindancer.modules.essentials.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkCooldowns;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Player#chat runs anything starting with a slash as a command, as the player. A roast that opens with
 * its target's name, and a target nicknamed "/op Bo", would otherwise make the roaster op somebody.
 */
class FunServiceTest {

    @Test
    @DisplayName("a line can never reach chat as a command")
    void neverACommand() {
        assertThat(FunService.chatSafe("/op Bo, you mine straight down.")).isEqualTo("op Bo, you mine straight down.");
        assertThat(FunService.chatSafe("  //  /stop")).isEqualTo("stop");
        assertThat(FunService.chatSafe("Bo, you mine straight down.")).isEqualTo("Bo, you mine straight down.");
        assertThat(FunService.chatSafe("Why? Because/so.")).isEqualTo("Why? Because/so.");
    }

    @Test
    @DisplayName("a joke told on one server keeps the player waiting on the next")
    @Tag("database")
    void waitFollowsThePlayer(@TempDir Path folder) {
        List<Database> databases = new ArrayList<>();
        try {
            Plugin plugin = mock(Plugin.class);
            Messages messages = mock(Messages.class);
            when(messages.fresh(anyString(), any(Object[].class))).thenReturn(Component.text("A joke."));
            Player ayla = mock(Player.class);
            when(ayla.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Ayla".getBytes()));
            EssentialsSettings settings = EssentialsSettings.DEFAULTS;

            FunService[] servers = new FunService[2];
            NetworkCooldowns[] networks = new NetworkCooldowns[2];
            for (int at = 0; at < 2; at++) {
                Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
                databases.add(database);
                networks[at] = new NetworkCooldowns(database, System::currentTimeMillis, Runnable::run);
                NetworkCooldowns.install(networks[at]);
                servers[at] = new FunService(messages, plugin, settings);
            }

            servers[0].joke(ayla);
            verify(ayla).chat("A joke.");
            networks[1].arrived(ayla.getUniqueId());
            servers[1].joke(ayla);

            verify(ayla, times(1)).chat(anyString());
            verify(messages).send(eq(ayla), anyString(), any(Object[].class));
        } finally {
            NetworkCooldowns.install(null);
            databases.forEach(Database::close);
        }
    }
}
