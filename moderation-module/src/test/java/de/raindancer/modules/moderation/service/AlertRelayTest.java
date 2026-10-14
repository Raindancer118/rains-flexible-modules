package de.raindancer.modules.moderation.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.store.AlertMutes;
import de.raindancer.modules.moderation.util.CheatPermissions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the staff of one server are told, the staff of the other server are told too — and the flagged player's server is named. */
class AlertRelayTest {

    @TempDir
    Path folder;

    private static final CheckType CHECK = Arrays.stream(CheckType.values())
            .filter(check -> check.alertAt() > 1).findFirst().orElseThrow();

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<AlertService> services = new ArrayList<>();

    private static final class Staff {
        final UUID id = UUID.randomUUID();
        final List<String> heard = new CopyOnWriteArrayList<>();
        final Player player = mock(Player.class);

        Staff(boolean permitted) {
            when(player.getUniqueId()).thenReturn(id);
            when(player.hasPermission(CheatPermissions.ALERTS)).thenReturn(permitted);
            org.mockito.Mockito.doAnswer(call -> {
                heard.add(PlainTextComponentSerializer.plainText().serialize(call.getArgument(0, Component.class)));
                return null;
            }).when(player).sendMessage(any(Component.class));
        }
    }

    private AlertService server(String id, Staff... online) {
        Server server = mock(Server.class);
        when(server.getOnlinePlayers()).thenAnswer(call -> Arrays.stream(online).map(each -> each.player).toList());
        Messages messages = wording();
        AlertService alerts = new AlertService(server, messages, Log.of("test"), System::currentTimeMillis, hub.bus(id));
        for (Staff each : online) {
            alerts.listening(each.id, true);
        }
        services.add(alerts);
        return alerts;
    }

    /** A line is its key and its values, in order, so a test can see what was worded. */
    private static Messages wording() {
        Messages messages = mock(Messages.class);
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(call -> Component.text(
                Arrays.stream(call.getArguments()).map(String::valueOf).collect(java.util.stream.Collectors.joining(" "))));
        return messages;
    }

    private static Player cheater() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Cheater");
        return player;
    }

    @AfterEach
    void close() {
        services.forEach(AlertService::close);
    }

    @Test
    @DisplayName("an alert reaches staff here and staff on the other server, which names the server the player is on")
    void alertReachesEveryServer() {
        Staff here = new Staff(true);
        Staff there = new Staff(true);
        AlertService smp = server("smp", here);
        server("arena", there);

        smp.alert(cheater(), CHECK, 4.0, "too fast", 50);
        hub.drain();

        assertThat(here.heard).singleElement().asString().startsWith("anticheat.alert").doesNotContain("[smp]");
        assertThat(there.heard).singleElement().asString().startsWith("anticheat.on-server server smp")
                .contains("anticheat.alert").contains("Cheater").contains("too fast");
    }

    @Test
    @DisplayName("staff without the permission, or who muted the check, are not told on the other server")
    void filtersApplyWhereTheyAreRead() {
        Staff muted = new Staff(true);
        Staff unpermitted = new Staff(false);
        Staff told = new Staff(true);
        AlertService smp = server("smp");
        AlertService arena = server("arena", muted, unpermitted, told);
        AlertMutes mutes = new AlertMutes(folder);
        mutes.mute(muted.id, CHECK);
        arena.mutesFrom(mutes);

        smp.alert(cheater(), CHECK, 4.0, "too fast", 50);
        hub.drain();

        assertThat(muted.heard).isEmpty();
        assertThat(unpermitted.heard).isEmpty();
        assertThat(told.heard).hasSize(1);
    }

    @Test
    @DisplayName("staff who switched alerts off hear nothing from the other server either")
    void switchedOff() {
        Staff off = new Staff(true);
        server("smp");
        AlertService arena = server("arena", off);
        arena.listening(off.id, false);

        services.getFirst().alert(cheater(), CHECK, 4.0, "too fast", 50);
        hub.drain();

        assertThat(off.heard).isEmpty();
    }

    @Test
    @DisplayName("a gathered line crosses the bus with its counts, and the other server's own mutes take checks out of it")
    void digestCrosses() {
        long[] now = {1_000_000};
        Staff there = new Staff(true);
        Server smpServer = mock(Server.class);
        when(smpServer.getOnlinePlayers()).thenReturn(List.of());
        Messages messages = wording();
        AlertService smp = new AlertService(smpServer, messages, Log.of("test"), () -> now[0], hub.bus("smp"));
        services.add(smp);
        server("arena", there);
        Player subject = cheater();

        smp.alert(subject, CHECK, 4.0, "first", 50);
        smp.alert(subject, CHECK, 5.0, "second", 50);
        smp.alert(subject, CHECK, 6.0, "third", 50);
        now[0] += 6_000;
        smp.flushDigests();
        hub.drain();

        assertThat(there.heard).hasSize(2);
        assertThat(there.heard.getLast()).startsWith("anticheat.on-server server smp").contains("anticheat.alert-digest")
                .contains(CHECK.title() + " ×2");
    }

    @Test
    @DisplayName("a line for all staff (a kick, a ban) crosses too; a key outside anticheat.* and a mangled payload are ignored")
    void staffLinesAndGarbage() {
        Staff there = new Staff(true);
        AlertService smp = server("smp");
        server("arena", there);

        smp.staff("anticheat.kicked", "player", "Cheater", "check", "Fly");
        hub.bus("intruder").publish(AlertService.TOPIC, "staff", "kind: staff\nkey: moderation.secret\n");
        hub.bus("intruder").publish(AlertService.TOPIC, "x", "not: [valid");
        hub.drain();

        assertThat(there.heard).singleElement().asString().startsWith("anticheat.on-server server smp")
                .contains("anticheat.kicked").contains("Cheater");
    }
}
