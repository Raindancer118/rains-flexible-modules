package de.raindancer.modules.moderation.util;

import de.raindancer.core.platform.network.LocalBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(arenaHeard).containsExactly(report);
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

        assertThat(arenaHeard).containsExactly(promotion);
    }

    @Test
    @DisplayName("a closed subscription hears nothing more, and a mangled payload is ignored")
    void closedAndMangled() {
        arena.close();
        smp.tell(report);
        hub.drain();
        assertThat(arenaHeard).isEmpty();
        assertThat(StaffAlerts.Alert.decode("not: [valid")).isEmpty();
        assertThat(StaffAlerts.Alert.decode("line: only a line")).isEmpty();
    }
}
