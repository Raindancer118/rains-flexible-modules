package de.raindancer.modules.essentials.service;

import de.raindancer.core.data.loadout.Loadout;
import de.raindancer.core.data.loadout.LoadoutStore;
import de.raindancer.core.data.loadout.Loadouts;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminModeServiceTest {

    @TempDir
    Path folder;

    private final Loadouts loadouts = mock(Loadouts.class);
    private final Messages messages = mock(Messages.class);
    private final ActionBars actionBars = mock(ActionBars.class);
    private final Vanish vanish = mock(Vanish.class);
    private final de.raindancer.core.moderation.players.PlayerPowers powers =
            mock(de.raindancer.core.moderation.players.PlayerPowers.class);
    private final List<Loadout.Place> movedTo = new ArrayList<>();
    private final UUID id = UUID.randomUUID();
    private final Player player = mock(Player.class);

    private LoadoutStore store;
    private AdminModeService service;

    private static final Loadout SURVIVAL = new Loadout(List.of("c3dvcmQ="), List.of(), 12, 0.5f, 18, 15, 2f,
            "SURVIVAL", false, false, List.of(), new Loadout.Place("world", 1, 70, 2, 0, 0));
    private static final Loadout ADMIN_NOW = new Loadout(List.of("", "d2FuZA=="), List.of(), 0, 0f, 20, 20, 5f,
            "CREATIVE", true, true, List.of(), new Loadout.Place("world", 500, 120, 500, 0, 0));

    @BeforeEach
    void setUp() {
        store = new LoadoutStore(folder);
        service = new AdminModeService(store, loadouts, messages, actionBars, vanish, powers,
                (who, place) -> movedTo.add(place), EssentialsSettings.DEFAULTS);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Tom");
        when(player.hasPermission(PermissionNodes.ADMIN_MODE)).thenReturn(true);
        when(loadouts.apply(any(), any())).thenReturn(true);
        when(powers.god(id, true)).thenReturn(true);
    }

    @Test
    @DisplayName("going in keeps the survival side on disk and puts on the admin side, empty the first time")
    void enter() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL);

        assertThat(service.toggle(player)).isTrue();

        assertThat(service.isInAdminMode(id)).isTrue();
        assertThat(store.load(id, AdminModeService.SURVIVAL)).contains(SURVIVAL);
        verify(loadouts).apply(player, Loadout.empty(GameMode.SURVIVAL.name()));
        verify(messages).send(player, "essentials.admin.entered");
    }

    @Test
    @DisplayName("admin mode is survival with flight and god mode — every time, whatever the admin side was left in")
    void survivalFlyGod() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW, SURVIVAL);
        service.toggle(player);
        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player).setAllowFlight(true);
        verify(powers).god(id, true);

        service.toggle(player);
        verify(powers).god(id, false);

        // ADMIN_NOW was left in creative; it comes back as survival all the same.
        org.mockito.Mockito.clearInvocations(player);
        service.toggle(player);
        verify(player).setGameMode(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("god mode somebody had before going in is not taken away on the way out")
    void godFromBefore() {
        when(powers.isInvulnerable(id)).thenReturn(true);
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW);
        service.toggle(player);
        service.toggle(player);
        verify(powers, never()).god(id, false);
    }

    @Test
    @DisplayName("flight and god can each be switched off by the owner")
    void ownerSwitches() {
        service.settings(EssentialsSettings.DEFAULTS.withAdminFly(false).withAdminGod(false));
        when(loadouts.capture(player)).thenReturn(SURVIVAL);
        service.toggle(player);
        verify(player, never()).setAllowFlight(true);
        verify(powers, never()).god(any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    @DisplayName("coming out puts the survival side back where it was and keeps the admin side for next time")
    void leave() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW);
        service.toggle(player);

        assertThat(service.toggle(player)).isTrue();

        assertThat(service.isInAdminMode(id)).isFalse();
        verify(loadouts).apply(player, SURVIVAL);
        assertThat(store.has(id, AdminModeService.SURVIVAL)).isFalse();
        // Kept without a place: the admin side starts wherever its owner is, next time.
        assertThat(store.load(id, AdminModeService.ADMIN)).contains(ADMIN_NOW.withPlace(null));
        assertThat(movedTo).containsExactly(SURVIVAL.place());
        verify(messages).send(player, "essentials.admin.left");
    }

    @Test
    @DisplayName("the second time in, the admin side comes back as it was left")
    void adminSideIsRemembered() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW, SURVIVAL);
        service.toggle(player);
        service.toggle(player);
        service.toggle(player);

        verify(loadouts).apply(player, ADMIN_NOW.withPlace(null));
    }

    @Test
    @DisplayName("nobody without the permission gets in — but anybody in admin mode can always get out")
    void permission() {
        when(player.hasPermission(PermissionNodes.ADMIN_MODE)).thenReturn(false);
        assertThat(service.toggle(player)).isFalse();
        verify(messages).send(player, "essentials.no-permission");
        assertThat(service.isInAdminMode(id)).isFalse();

        when(player.hasPermission(PermissionNodes.ADMIN_MODE)).thenReturn(true);
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW);
        service.toggle(player);
        when(player.hasPermission(PermissionNodes.ADMIN_MODE)).thenReturn(false);
        assertThat(service.toggle(player)).isTrue();
        assertThat(service.isInAdminMode(id)).isFalse();
    }

    @Test
    @DisplayName("when the admin side cannot be put on, the survival side stays on and nothing is marked")
    void enterRefused() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL);
        when(loadouts.apply(any(), any())).thenReturn(false);

        assertThat(service.toggle(player)).isFalse();

        assertThat(service.isInAdminMode(id)).isFalse();
        assertThat(store.has(id, AdminModeService.SURVIVAL)).isFalse();
        verify(messages).send(eq(player), eq("essentials.admin.unreadable"), any(Object[].class));
    }

    @Test
    @DisplayName("when the survival side cannot be read back, they stay in admin mode rather than lose it")
    void leaveRefused() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW);
        service.toggle(player);
        when(loadouts.unreadable(SURVIVAL)).thenReturn(1);

        assertThat(service.toggle(player)).isFalse();

        assertThat(service.isInAdminMode(id)).isTrue();
        assertThat(store.load(id, AdminModeService.SURVIVAL)).contains(SURVIVAL);
        assertThat(store.has(id, AdminModeService.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("admin mode outlives a restart: somebody who joins with their survival side on disk is still in it")
    void survivesARestart() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL);
        service.toggle(player);

        AdminModeService afterRestart = new AdminModeService(store, loadouts, messages, actionBars, vanish, powers,
                (who, place) -> movedTo.add(place), EssentialsSettings.DEFAULTS);
        afterRestart.joined(player);

        assertThat(afterRestart.isInAdminMode(id)).isTrue();
        verify(actionBars, org.mockito.Mockito.atLeastOnce()).show(eq(id), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("somebody who lost the permission while away is taken out of admin mode as they join")
    void lostPermissionWhileAway() {
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW);
        service.toggle(player);
        when(player.hasPermission(PermissionNodes.ADMIN_MODE)).thenReturn(false);

        service.joined(player);

        assertThat(service.isInAdminMode(id)).isFalse();
        verify(loadouts).apply(player, SURVIVAL);
    }

    @Test
    @DisplayName("switched off by the owner: nobody gets in")
    void switchedOff() {
        service.settings(EssentialsSettings.DEFAULTS.withAdminModeEnabled(false));
        assertThat(service.toggle(player)).isFalse();
        verify(messages).send(player, "essentials.admin.switched-off");
        verify(loadouts, never()).capture(any());
    }

    @Test
    @DisplayName("vanishing with admin mode is undone on the way out — but never somebody who was vanished already")
    void vanish() {
        service.settings(EssentialsSettings.DEFAULTS.withAdminVanish(true));
        when(loadouts.capture(player)).thenReturn(SURVIVAL, ADMIN_NOW);
        when(vanish.isVanished(id)).thenReturn(false);
        when(vanish.vanish(id)).thenReturn(true);
        service.toggle(player);
        verify(vanish).vanish(id);
        service.toggle(player);
        verify(vanish).reveal(id);

        Player other = mock(Player.class);
        UUID otherId = UUID.randomUUID();
        when(other.getUniqueId()).thenReturn(otherId);
        when(other.hasPermission(PermissionNodes.ADMIN_MODE)).thenReturn(true);
        when(loadouts.capture(other)).thenReturn(SURVIVAL, ADMIN_NOW);
        when(vanish.isVanished(otherId)).thenReturn(true);
        service.toggle(other);
        service.toggle(other);
        verify(vanish, never()).reveal(otherId);
    }

    @Test
    @DisplayName("a broken admin-mode file counts as being in admin mode — never as an empty one")
    void brokenFileFailsClosed() throws Exception {
        java.nio.file.Files.writeString(folder.resolve(id + ".yml"), "survival: [unclosed\n  : :");

        service.joined(player);

        assertThat(service.isInAdminMode(id)).isTrue();
        verify(messages, org.mockito.Mockito.atLeastOnce()).send(player, "essentials.admin.file-broken");
        assertThat(service.toggle(player)).as("cannot come out: that would make the admin side theirs").isFalse();
        assertThat(service.enter(player)).isFalse();
        verify(loadouts, never()).apply(any(), any());
        verify(loadouts, never()).capture(any());
    }

    @Test
    @DisplayName("losing the permission with a broken file still keeps them marked, not freed")
    void brokenFileWithoutPermission() throws Exception {
        java.nio.file.Files.writeString(folder.resolve(id + ".yml"), "survival: [unclosed\n  : :");
        when(player.hasPermission(PermissionNodes.ADMIN_MODE)).thenReturn(false);

        service.joined(player);

        assertThat(service.isInAdminMode(id)).isTrue();
        verify(loadouts, never()).apply(any(), any());
    }
}
