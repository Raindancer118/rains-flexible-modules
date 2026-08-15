package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.store.EssentialsStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The blocklist half of setting a nickname.
 *
 * <h2>Why the ban assertions do not mock {@code ModerationIntegration}</h2>
 * They do not need to: {@code ModerationIntegration.banOneDay} tries {@code Bukkit.getServicesManager()}
 * first and catches everything that lookup can throw, falling back to the {@link Punishments} passed
 * in here — and a unit test JVM has no Bukkit server at all, so that lookup always fails and the
 * fallback always runs. That is the real behaviour a server with no moderation plugin installed gets
 * too, which is exactly the case worth pinning.
 */
class NicknameServiceTest {

    private final EssentialsStore store = new EssentialsStore(Path.of("target", "test-nick-service"));
    private final Identities identities = mock(Identities.class);
    private final Messages messages = mock(Messages.class);
    private final Chat chat = mock(Chat.class);
    private final Server server = mock(Server.class);
    private final Punishments punishments = mock(Punishments.class);
    private final Audit audit = mock(Audit.class);

    private NicknameService serviceWith(EssentialsSettings settings) {
        return new NicknameService(store, identities, messages, chat, server, punishments, audit,
                settings);
    }

    private Player player(String name) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.getName()).thenReturn(name);
        when(identities.nametag(any(), any())).thenReturn(Component.empty());
        when(identities.chatName(any(), any())).thenReturn(Component.empty());
        return who;
    }

    @Nested
    @DisplayName("a reported-only blocklist match")
    class ReportedOnly {

        @Test
        @DisplayName("is refused, and never bans")
        void refusedButNotBanned() {
            EssentialsSettings settings = new EssentialsSettings(3, true, 300, true, true, true,
                    true, 16, List.of("forbidden name"), List.of());
            NicknameService service = serviceWith(settings);
            Player who = player("Tom");
            when(server.getOnlinePlayers()).thenReturn(List.of());

            boolean set = service.set(who, "Forbidden Name", false);

            assertThat(set).isFalse();
            assertThat(store.nicknameOf(who.getUniqueId())).isEmpty();
            verify(punishments, never()).punish(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("still writes down an audit entry")
        void stillAudited() {
            EssentialsSettings settings = new EssentialsSettings(3, true, 300, true, true, true,
                    true, 16, List.of("forbidden name"), List.of());
            NicknameService service = serviceWith(settings);
            Player who = player("Tom");
            when(server.getOnlinePlayers()).thenReturn(List.of());

            service.set(who, "Forbidden Name", false);

            verify(audit).record(org.mockito.ArgumentMatchers
                    .<de.raindancer.core.moderation.audit.AuditEntry.Builder>any());
        }
    }

    @Nested
    @DisplayName("a report-and-ban blocklist match")
    class ReportAndBan {

        @Test
        @DisplayName("is refused and bans for exactly one day")
        void refusedAndBanned() {
            EssentialsSettings settings = new EssentialsSettings(3, true, 300, true, true, true,
                    true, 16, List.of(), List.of("severe name"));
            NicknameService service = serviceWith(settings);
            Player who = player("Tom");
            when(server.getOnlinePlayers()).thenReturn(List.of());

            boolean set = service.set(who, "Severe Name", false);

            assertThat(set).isFalse();
            verify(punishments).punish(eq(who.getUniqueId()), eq(PunishmentKind.BAN), eq(null),
                    any(String.class), eq(Duration.ofDays(1)));
        }

        @Test
        @DisplayName("matches case-insensitively and ignores colour markup")
        void matchesRegardlessOfCaseOrColour() {
            EssentialsSettings settings = new EssentialsSettings(3, true, 300, true, true, true,
                    true, 16, List.of(), List.of("blocked"));
            NicknameService service = serviceWith(settings);
            Player who = player("Tom");
            when(server.getOnlinePlayers()).thenReturn(List.of());

            boolean set = service.set(who, "<red>BLOCKED</red>", false);

            assertThat(set).isFalse();
            verify(punishments).punish(any(), eq(PunishmentKind.BAN), any(), any(), any());
        }
    }

    @Test
    @DisplayName("a name on neither blocklist is never reported or banned")
    void unblockedNameIsLeftAlone() {
        NicknameService service = serviceWith(EssentialsSettings.DEFAULTS);
        Player who = player("Tom");

        boolean set = service.set(who, "Foxy", false);

        assertThat(set).isTrue();
        assertThat(store.nicknameOf(who.getUniqueId())).contains("Foxy");
        verify(punishments, never()).punish(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("blocked takes priority over the length limit — still banned even though it is also too long")
    void blockedBeatsTooLong() {
        EssentialsSettings settings = new EssentialsSettings(3, true, 300, true, true, true, true,
                4, List.of(), List.of("waytoolongname"));
        NicknameService service = serviceWith(settings);
        Player who = player("Tom");
        when(server.getOnlinePlayers()).thenReturn(List.of());

        service.set(who, "waytoolongname", false);

        verify(punishments).punish(any(), eq(PunishmentKind.BAN), any(), any(), any());
    }
}
