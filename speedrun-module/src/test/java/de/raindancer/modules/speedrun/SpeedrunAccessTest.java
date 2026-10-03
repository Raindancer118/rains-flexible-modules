package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every way into an action asks for exactly the node the standalone command asks for — the
 * /speedrun word, the hub button, the chat button — and asks again at the moment of the click,
 * not only when the button was drawn.
 */
class SpeedrunAccessTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    @TempDir
    Path folder;

    private SettingsStore<SpeedrunSettings> settings;
    private SpeedrunLobby lobby;
    private Messages messages;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.isEnabled()).thenReturn(true);
        settings = new SettingsStore<>(SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                folder.resolve("speedrun.yml"));
        settings.load();
        settings.set("world-name", "world");
        lobby = new SpeedrunLobby(plugin, settings);
        messages = mock(Messages.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    /** A player holding no node at all. */
    private Player nobody() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ALICE);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(player.getWorld()).thenReturn(mock(World.class));
        return player;
    }

    private CommandSourceStack from(Player player) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        return source;
    }

    @Test
    @DisplayName("each action needs exactly the node of the command it duplicates")
    void nodes() {
        assertThat(SpeedrunAccess.SPECTATE.node()).isEqualTo(PermissionNodes.SPECTATE);
        assertThat(SpeedrunAccess.RELEASE_OTHERS.node()).isEqualTo(PermissionNodes.LEMMEMOVE_OTHERS);
        for (SpeedrunAccess admin : new SpeedrunAccess[]{SpeedrunAccess.RESUME, SpeedrunAccess.SET_CLOCK,
                SpeedrunAccess.RESET, SpeedrunAccess.SEEDS, SpeedrunAccess.SETTINGS, SpeedrunAccess.SETUP,
                SpeedrunAccess.FIX, SpeedrunAccess.ROSTER_OTHERS}) {
            assertThat(admin.node()).as(admin.name()).isEqualTo(PermissionNodes.ADMIN);
        }
        assertThat(SpeedrunAccess.START.node()).isEqualTo(PermissionNodes.START);
    }

    @Test
    @DisplayName("a player without the node is refused every action that has one — the start only when staff-only")
    void everyActionRefusesWithoutItsNode() {
        Player player = nobody();
        for (SpeedrunAccess access : SpeedrunAccess.values()) {
            boolean expected = access.node() == null
                    || (access == SpeedrunAccess.START && !lobby.config().startBlockStaffOnly());
            assertThat(access.allows(lobby, player)).as(access.name()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("every /speedrun word with a node refuses a player without it, and changes nothing")
    void everyWordRefuses() {
        SpeedrunJoinCommand command = new SpeedrunJoinCommand(() -> new SpeedrunAdminServices(lobby, messages));
        for (SpeedrunJoinCommand.Word word : SpeedrunJoinCommand.WORDS) {
            if (word.access().node() == null) {
                continue;
            }
            Player player = nobody();
            command.execute(from(player), new String[]{word.name()});
            verify(messages, atLeastOnce()).send(eq(player), anyString(), any(Object[].class));
        }
        assertThat(lobby.isSpectator(ALICE)).as("/speedrun spectate without the node").isFalse();
        assertThat(lobby.replayingSeed()).isFalse();
        assertThat(lobby.config().setupDone()).isFalse();
    }

    @Test
    @DisplayName("/speedrun spectate with the node toggles, like /speedrunspectate")
    void spectateWithTheNode() {
        SpeedrunJoinCommand command = new SpeedrunJoinCommand(() -> new SpeedrunAdminServices(lobby, messages));
        Player player = nobody();
        when(player.hasPermission(PermissionNodes.SPECTATE)).thenReturn(true);

        command.execute(from(player), new String[]{"spectate"});

        assertThat(lobby.isSpectator(ALICE)).isTrue();
    }

    @Test
    @DisplayName("a button drawn while somebody held the node does nothing once they no longer do")
    void clicksAreCheckedAgain() {
        Player player = nobody();
        when(player.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean();
        var handler = SpeedrunAccess.RESET.guard(lobby, player, click -> ran.set(true));

        when(player.hasPermission(PermissionNodes.ADMIN)).thenReturn(false);
        handler.accept(null);

        assertThat(ran).isFalse();
        verify(player).hasPermission(PermissionNodes.ADMIN);
    }

    @Test
    @DisplayName("…and does its thing while they still do")
    void clicksStillWork() {
        Player player = nobody();
        when(player.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean();

        SpeedrunAccess.RESET.guard(lobby, player, click -> ran.set(true)).accept(null);

        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("a chat button's fix is refused to somebody who lost the node since it was sent")
    void chatButtonFixesAreChecked() {
        settings.set("advancement-key", "");
        Player player = nobody();

        new SpeedrunActions(lobby, messages).applyIfAllowed(SpeedrunPreflight.Fix.DRAGON_GOAL, player, null);

        assertThat(lobby.config().hasAdvancementGoal()).isFalse();
        verify(messages, never()).send(player, "speedrun.fix.dragon-goal");
    }
}
