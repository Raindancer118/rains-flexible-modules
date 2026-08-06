package de.raindancer.modules.claims;

import de.raindancer.modules.claims.listener.PlayerSessionListener;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.service.EvictionService;
import de.raindancer.modules.claims.store.ClaimRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relogging while banned from the claim you are standing in — or right on its border — must not turn
 * into a de facto server ban.
 *
 * <h2>The bug this closes</h2>
 * Nothing checked a ban on join at all: {@code syncPosition} just recorded whichever claim the player's
 * stored location happened to be in, with no gate — so a banned player logging back in was simply left
 * standing there, unbanned in practice. The fix has to avoid a second trap on the way to fixing the
 * first one: refusing the login itself (a {@code PlayerLoginEvent} veto) looks like the obvious way to
 * keep a banned player out, but their stored position does not move either way, so every future login
 * attempt would fail exactly the same way — a claim ban that nobody, not even an admin lifting it from
 * the claim's ban list, could ever undo, because the player can never get back in to be walked out
 * normally. Letting them join and immediately walking them back out — the same eviction {@code /claim ban}
 * already runs on someone caught inside — is the one that cannot lock anybody out for good.
 */
@ExtendWith(MockitoExtension.class)
class RejoinBanEvictionTest {

    private final World world = FakeServices.world();
    private final ClaimRegistry registry = new ClaimRegistry();
    private final EvictionService eviction = mock(EvictionService.class);
    private final UUID banned = UUID.randomUUID();

    private Claim claimWithBan(UUID owner) {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 9, 9, 0, 20);
        Claim claim = new Claim(UUID.randomUUID(), "manor", FakeServices.WORLD, "world", shape, owner);
        claim.ban(ClaimBan.permanent(banned, UUID.randomUUID(), "trouble"));
        registry.add(claim);
        return claim;
    }

    private PlayerSessionListener listener() {
        ClaimServices services = FakeServices.builder().claims(registry).eviction(eviction).build();
        return new PlayerSessionListener(services);
    }

    @Test
    @DisplayName("logging back in inside the claim you are banned from walks you back out, not refuses the login")
    void rejoiningInsideABannedClaimEvictsRatherThanBlocks() {
        Claim claim = claimWithBan(UUID.randomUUID());
        Player player = FakeServices.player(banned);
        Location here = FakeServices.at(world, 5, 5, 5);
        when(player.getLocation()).thenReturn(here);
        PlayerJoinEvent event = new PlayerJoinEvent(player, "");

        listener().onJoin(event);

        // The eviction runs through the player's own (Folia) entity scheduler, same as production —
        // FakeServices makes that synchronous, so this proves the call actually happened rather than
        // merely being scheduled.
        verify(eviction, timeout(1000)).evict(eq(player), eq(claim), eq("protection.evicted-banned"));
    }

    @Test
    @DisplayName("logging back in right on the claim's border is treated the same as being inside")
    void rejoiningOnTheBorderAlsoEvicts() {
        Claim claim = claimWithBan(UUID.randomUUID());
        Player player = FakeServices.player(banned);
        // x=9 is the last column still inside a 0..9 rectangle — the border itself, not past it.
        Location border = FakeServices.at(world, 9, 5, 9);
        when(player.getLocation()).thenReturn(border);
        PlayerJoinEvent event = new PlayerJoinEvent(player, "");

        listener().onJoin(event);

        verify(eviction, timeout(1000)).evict(eq(player), eq(claim), eq("protection.evicted-banned"));
    }

    @Test
    @DisplayName("an owner is never evicted from their own claim, ban list or not")
    void anOwnerIsNeverEvicted() {
        claimWithBan(banned);
        Player player = FakeServices.player(banned);
        Location here = FakeServices.at(world, 5, 5, 5);
        when(player.getLocation()).thenReturn(here);
        PlayerJoinEvent event = new PlayerJoinEvent(player, "");

        listener().onJoin(event);

        verify(eviction, never()).evict(any(), any(), any());
    }

    @Test
    @DisplayName("logging in outside any claim never touches eviction")
    void rejoiningOutsideAnyClaimDoesNothing() {
        claimWithBan(UUID.randomUUID());
        Player player = FakeServices.player(banned);
        Location wilderness = FakeServices.at(world, 50, 5, 50);
        when(player.getLocation()).thenReturn(wilderness);
        PlayerJoinEvent event = new PlayerJoinEvent(player, "");

        listener().onJoin(event);

        verify(eviction, never()).evict(any(), any(), any());
    }

    @Test
    @DisplayName("the fix does not reach for a login veto, which is what would turn the ban permanent")
    void thereIsNoLoginEventInThisListener() {
        String body;
        try {
            body = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/de/raindancer/modules/claims/listener/PlayerSessionListener.java"));
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("could not read PlayerSessionListener", unreadable);
        }
        org.assertj.core.api.Assertions.assertThat(body)
                .as("see the class javadoc: vetoing the login itself never lets a banned player's "
                        + "position move, so every future attempt would fail exactly the same way")
                .doesNotContain("PlayerLoginEvent")
                .doesNotContain("AsyncPlayerPreLoginEvent")
                .doesNotContain("event.disallow(");
    }
}
