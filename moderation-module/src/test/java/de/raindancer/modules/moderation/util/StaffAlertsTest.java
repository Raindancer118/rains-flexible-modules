package de.raindancer.modules.moderation.util;

import de.raindancer.core.platform.network.LocalBus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What staff on one server hear, staff on the others hear too. */
class StaffAlertsTest {

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<StaffAlerts.Alert> smpHeard = new CopyOnWriteArrayList<>();
    private final List<StaffAlerts.Alert> arenaHeard = new CopyOnWriteArrayList<>();
    private final StaffAlerts smp = new StaffAlerts(hub.bus("smp"), smpHeard::add);
    private final StaffAlerts arena = new StaffAlerts(hub.bus("arena"), arenaHeard::add);

    private final UUID subject = UUID.randomUUID();
    private final StaffAlerts.Alert report = new StaffAlerts.Alert("moderation.reports", subject,
            "<white><reporter></white> reported <white><subject></white>",
            Map.of("reporter", "Ayla", "subject", "Bram <3 & co"));

    @Test
    @DisplayName("an alert reaches this server's staff once and the other server's staff once, whole")
    void reachesEveryServerOnce() {
        smp.tell(report);
        hub.drain();

        assertThat(smpHeard).containsExactly(report);
        assertThat(arenaHeard).singleElement().satisfies(heard -> {
            assertThat(heard).isEqualTo(withOrigin(report, "smp"));
            assertThat(heard.origin()).isEqualTo("smp");
        });
    }

    @Test
    @DisplayName("the player a report is about is still excluded on the receiving server")
    void exclusionTravels() {
        arena.tell(report);
        hub.drain();

        assertThat(smpHeard).singleElement().extracting(StaffAlerts.Alert::exclude).isEqualTo(subject);
    }

    @Test
    @DisplayName("an alert with nobody excluded survives the trip")
    void noExclusion() {
        StaffAlerts.Alert promotion = new StaffAlerts.Alert("moderation.history", null, "<player> is now staff",
                Map.of("player", "Ayla"));
        smp.tell(promotion);
        hub.drain();

        assertThat(arenaHeard).containsExactly(withOrigin(promotion, "smp"));
    }

    @Test
    @DisplayName("a closed subscription hears nothing more, and a mangled payload is ignored")
    void closedAndMangled() {
        arena.close();
        smp.tell(report);
        hub.drain();
        assertThat(arenaHeard).isEmpty();
        assertThat(StaffAlerts.Alert.decode("not: [valid", "smp")).isEmpty();
        assertThat(StaffAlerts.Alert.decode("line: only a line", "smp")).isEmpty();
    }

    @Test
    @DisplayName("the opt-out, the bare and console wishes travel; an alert of this server's own has no origin")
    void wishesTravel() {
        StaffAlerts.Alert attempt = new StaffAlerts.Alert("moderation.attempts", null, "<player> tried",
                Map.of("player", "Mushy"), "attempt-alerts", true, true, null);
        smp.tell(attempt);
        hub.drain();

        assertThat(smpHeard).containsExactly(attempt);
        assertThat(smpHeard.get(0).origin()).isNull();
        assertThat(arenaHeard).containsExactly(withOrigin(attempt, "smp"));
    }

    @Test
    @DisplayName("staff chat from elsewhere: the server is named, and what was typed stays text, not markup")
    void typedTextIsNotMarkup() {
        StaffAlerts.Alert chat = new StaffAlerts.Alert("moderation.staffchat", null,
                "<white><who></white>: <what>", Map.of("who", "Ayla", "what", "<red>hi</red> <click:run_command:'/op me'>x"));
        smp.tell(chat);
        hub.drain();

        StaffAlerts.Alert heard = arenaHeard.get(0);
        var rendered = MiniMessage.miniMessage().deserialize(heard.shownAs("<dark_gray>[<origin-server>] "), heard.resolvers());
        assertThat(PlainTextComponentSerializer.plainText().serialize(rendered))
                .isEqualTo("[smp] Ayla: <red>hi</red> <click:run_command:'/op me'>x");
        assertThat(rendered.children().stream().map(Component::clickEvent)).containsOnlyNulls();
    }

    @Test
    @DisplayName("an origin that looks like markup is shown as text too")
    void originIsText() {
        StaffAlerts.Alert hostile = withOrigin(report, "<red>evil");
        var rendered = MiniMessage.miniMessage().deserialize(hostile.shownAs("[<origin-server>] "), hostile.resolvers());
        assertThat(PlainTextComponentSerializer.plainText().serialize(rendered).startsWith("[<red>evil] ")).isTrue();
    }

    @Test
    @DisplayName("an empty permission is everybody, otherwise the permission is asked, and the excluded one is never told")
    void whoIsTold() {
        Player mod = player("moderation.history", true);
        Player guest = player("moderation.history", false);
        Player subjectPlayer = player("moderation.history", true);
        when(subjectPlayer.getUniqueId()).thenReturn(subject);

        assertThat(StaffAlerts.whoToTell(List.of(mod, guest, subjectPlayer), subject, "moderation.history"))
                .containsExactly(mod);
        assertThat(StaffAlerts.whoToTell(List.of(mod, guest, subjectPlayer), null, ""))
                .containsExactly(mod, guest, subjectPlayer);
    }

    private static Player player(String node, boolean holds) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.hasPermission(node)).thenReturn(holds);
        return who;
    }

    private static StaffAlerts.Alert withOrigin(StaffAlerts.Alert alert, String origin) {
        return new StaffAlerts.Alert(alert.permission(), alert.exclude(), alert.line(), alert.args(), alert.optOut(),
                alert.bare(), alert.console(), origin);
    }
}
