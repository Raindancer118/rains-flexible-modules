package de.raindancer.modules.moderation.model;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerOverviewTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = ZonedDateTime.of(2026, 10, 11, 15, 0, 0, 0, BERLIN).toInstant();
    private static final UUID MUSHY = UUID.fromString("e392eece-9274-4560-8672-32185a9b2f55");

    private static Punishment active(PunishmentKind kind, String reason, Duration length) {
        return new Punishment(kind.name(), MUSHY, kind, null, reason, NOW.minusSeconds(3600),
                length == null ? null : NOW.plus(length), null, null, null);
    }

    private static PlayerOverview.Builder mushy() {
        return PlayerOverview.of(MUSHY, "MushyTheFish")
                .seen(ZonedDateTime.of(2026, 10, 1, 18, 0, 0, 0, BERLIN).toInstant(),
                        ZonedDateTime.of(2026, 10, 11, 12, 54, 0, 0, BERLIN).toInstant())
                .playtime(Duration.ofMinutes(754))
                .oldNames(List.of("MushyTheFish", "OldMushy"));
    }

    @Test
    @DisplayName("green when online, orange when away, red when offline, grey when never here")
    void presence() {
        assertThat(PlayerOverview.Presence.of(true, false, true)).isEqualTo(PlayerOverview.Presence.ONLINE);
        assertThat(PlayerOverview.Presence.of(true, true, true)).isEqualTo(PlayerOverview.Presence.AWAY);
        assertThat(PlayerOverview.Presence.of(false, false, true)).isEqualTo(PlayerOverview.Presence.OFFLINE);
        assertThat(PlayerOverview.Presence.of(false, false, false)).isEqualTo(PlayerOverview.Presence.NEVER);
        assertThat(PlayerOverview.Presence.ONLINE.icon()).isEqualTo(Material.LIME_CONCRETE);
        assertThat(PlayerOverview.Presence.AWAY.icon()).isEqualTo(Material.ORANGE_CONCRETE);
        assertThat(PlayerOverview.Presence.OFFLINE.icon()).isEqualTo(Material.RED_CONCRETE);
    }

    @Test
    @DisplayName("offline: last seen in Berlin time and how long ago")
    void offlineLore() {
        PlayerOverview overview = mushy().presence(PlayerOverview.Presence.OFFLINE).build();
        assertThat(String.join("\n", overview.presenceLore(BERLIN, NOW, OverviewAccess.EVERYTHING)))
                .contains("Last seen <white>2026-10-11 12:54").contains("2h 6m ago");
    }

    @Test
    @DisplayName("online: where they stand, ping, client and edition")
    void onlineLore() {
        PlayerOverview overview = mushy().presence(PlayerOverview.Presence.ONLINE)
                .where(new PlayerOverview.Where("world", 120, 64, -30, 45, "26.3")).build();
        String lore = String.join("\n", overview.presenceLore(BERLIN, NOW, OverviewAccess.EVERYTHING));
        assertThat(lore).contains("world").contains("120 64 -30").contains("45 ms").contains("26.3");
    }

    @Test
    @DisplayName("the standing block is the worst thing in force: banned red, muted yellow, frozen blue, nothing grey")
    void standing() {
        assertThat(mushy().build().standing()).isEqualTo(PlayerOverview.Standing.CLEAR);
        assertThat(mushy().inForce(List.of(active(PunishmentKind.MUTE, "spam", Duration.ofHours(2)),
                active(PunishmentKind.BAN, "griefing", null))).build().standing())
                .isEqualTo(PlayerOverview.Standing.BANNED);
        assertThat(PlayerOverview.Standing.BANNED.icon()).isEqualTo(Material.RED_BANNER);
        assertThat(PlayerOverview.Standing.MUTED.icon()).isEqualTo(Material.YELLOW_BANNER);
        assertThat(PlayerOverview.Standing.FROZEN.icon()).isEqualTo(Material.LIGHT_BLUE_BANNER);
        assertThat(PlayerOverview.Standing.CLEAR.icon()).isEqualTo(Material.LIGHT_GRAY_BANNER);

        PlayerOverview banned = mushy().inForce(List.of(active(PunishmentKind.BAN, "griefing", null),
                active(PunishmentKind.MUTE, "spam", Duration.ofMinutes(90)))).build();
        String lore = String.join("\n", banned.standingLore(NOW));
        assertThat(lore).contains("Banned").contains("for ever").contains("griefing")
                .contains("Muted").contains("1h 30m left");
        assertThat(String.join("\n", mushy().build().standingLore(NOW))).contains("Nothing in force");
    }

    @Test
    @DisplayName("who: id, edition, old names without the current one, first joined, playtime")
    void identity() {
        String lore = String.join("\n", mushy().build().identityLore(BERLIN, OverviewAccess.EVERYTHING));
        assertThat(lore).contains(MUSHY.toString()).contains("Java").contains("OldMushy")
                .doesNotContain("MushyTheFish,").contains("2026-10-01").contains("12h 34m");
        assertThat(PlayerOverview.isBedrock(new UUID(0, 2535412345678L), ".Steve")).isTrue();
        assertThat(PlayerOverview.isBedrock(MUSHY, "MushyTheFish")).isFalse();
        assertThat(String.join("\n", PlayerOverview.of(new UUID(0, 1), ".Steve").build().identityLore(BERLIN, OverviewAccess.EVERYTHING)))
                .contains("Bedrock");
    }

    @Test
    @DisplayName("the record card counts warnings per rule, reports, notes and attempts; x-ray shows bait and odds")
    void recordAndXray() {
        PlayerOverview overview = mushy().record(7, 3, Map.of("No griefing", 2, "Be kind", 1))
                .reports(1, 4).notes(2).attempts(5).money("⛃120", "⛃0")
                .xray(new PlayerOverview.Xray(87, 6.0, 1.5, 9)).build();
        String record = String.join("\n", overview.recordLore(OverviewAccess.EVERYTHING));
        assertThat(record).contains("7").contains("No griefing: <white>2×").contains("1</white> open of <white>4")
                .contains("Attempts while punished: <white>5").contains("⛃120");
        assertThat(record.lines().filter(line -> line.contains("×")).toList()).first().asString().contains("No griefing");
        String xray = String.join("\n", overview.xrayLore(OverviewAccess.EVERYTHING));
        assertThat(xray).contains("<red>87%").contains("6 of 1.5").contains("9");
        assertThat(String.join("\n", mushy().xray(new PlayerOverview.Xray(10, 0, 0, 0)).build().xrayLore(OverviewAccess.EVERYTHING)))
                .contains("<green>10%");
    }

    @Test
    @DisplayName("a viewer without the right sees none of what that right guards elsewhere: evidence, attempts, chat, location, money")
    void lowerRanksSeeLess() {
        PlayerOverview overview = mushy().presence(PlayerOverview.Presence.ONLINE)
                .where(new PlayerOverview.Where("world", 120, 64, -30, 45, "26.3"))
                .record(7, 3, Map.of("No griefing", 2)).reports(1, 4).notes(2).attempts(5).money("⛃120", "⛃0")
                .xray(new PlayerOverview.Xray(87, 6.0, 1.5, 9)).build();
        java.util.Set<ModerationPermission> held = java.util.EnumSet.of(ModerationPermission.WARN);
        OverviewAccess trial = OverviewAccess.of(held::contains, false, false);

        String everything = String.join("\n", overview.presenceLore(BERLIN, NOW, trial))
                + String.join("\n", overview.identityLore(BERLIN, trial))
                + String.join("\n", overview.recordLore(trial))
                + String.join("\n", overview.xrayLore(trial));
        assertThat(everything).doesNotContain("120 64 -30").doesNotContain("45 ms").doesNotContain("26.3")
                .doesNotContain("OldMushy").doesNotContain("87%").doesNotContain("Baits")
                .doesNotContain("Attempts").doesNotContain("open of").doesNotContain("Notes")
                .doesNotContain("⛃120").doesNotContain("No griefing").doesNotContain("On record");
        assertThat(trial.addresses()).isFalse();

        OverviewAccess mod = OverviewAccess.of(java.util.EnumSet.of(ModerationPermission.REPORTS,
                ModerationPermission.HISTORY, ModerationPermission.ATTEMPTS)::contains, true, false);
        String more = String.join("\n", overview.recordLore(mod)) + String.join("\n", overview.xrayLore(mod))
                + String.join("\n", overview.presenceLore(BERLIN, NOW, mod));
        assertThat(more).contains("87%").contains("Attempts while punished: <white>5").contains("1</white> open of")
                .contains("120 64 -30").doesNotContain("Notes");
        assertThat(OverviewAccess.of(held::contains, false, true).addresses()).as("operators see address hashes").isTrue();
    }
}
