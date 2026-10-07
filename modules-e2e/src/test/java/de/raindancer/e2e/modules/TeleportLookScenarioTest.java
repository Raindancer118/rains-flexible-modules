package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Teleport effects on a real server: Core's by default, a player's own from /cosmetics teleport, for /spawn. */
@Tag("e2e")
class TeleportLookScenarioTest {

    @Test
    @DisplayName("/spawn sounds and sparkles like Core says, then like the player chose, then like Core again")
    void ownTeleportLook() {
        try (Server server = Server.start("teleport-look",
                List.of("essentials-standalone:RainsEssentials-.*", "cosmetics-standalone:RainsCosmetics-.*"),
                List.of("Essentials are up", "Cosmetics is up"))) {
            Bot ada = server.admin("Ada");
            ada.run("setspawn");
            Await.ticks(10);
            Bot bo = server.player("Bo");

            goToSpawn(server, bo);
            assertThat(bo.soundsHeard()).as("Core's departure and arrival")
                    .contains("block.beacon.power_select", "entity.enderman.teleport");
            assertThat(bo.soundsHeard().stream().filter("block.note_block.bell"::equals).count())
                    .as("Core's ding, once a second of the three-second wait").isGreaterThanOrEqualTo(2);
            assertThat(bo.particlesSeen()).as("Core's waiting particles").contains("PORTAL");

            bo.runAndExpect("cosmetics teleport arrive block.bell.use", "Landing sound");
            bo.runAndExpect("cosmetics teleport wait heart", "Waiting particles");
            bo.runAndExpect("cosmetics teleport depart none", "nothing at all");
            bo.runAndExpect("cosmetics teleport tick block.note_block.pling", "Countdown ding");
            bo.runAndExpect("cosmetics teleport arrive entity.ender_dragon.death", "not on this server's list");

            goToSpawn(server, bo);
            assertThat(bo.soundsHeard()).contains("block.bell.use", "block.note_block.pling")
                    .doesNotContain("block.beacon.power_select", "entity.enderman.teleport", "block.note_block.bell");
            assertThat(bo.particlesSeen()).as("their own while waiting, the server's burst where they land")
                    .contains("HEART", "PORTAL");

            assertThat(server.console("settings set teleport-arrival-particles 0")).contains("is now");
            goToSpawn(server, bo);
            assertThat(bo.particlesSeen()).contains("HEART").doesNotContain("PORTAL");

            // Every sound the server offers by default is one this version of the game has.
            for (String sound : List.of("entity.enderman.teleport", "item.chorus_fruit.teleport",
                    "block.beacon.power_select", "block.amethyst_block.chime", "block.bell.use",
                    "entity.player.levelup", "block.note_block.pling", "block.note_block.chime",
                    "entity.firework_rocket.twinkle", "entity.breeze.wind_burst",
                    "block.bubble_column.upwards_inside", "entity.allay.item_given",
                    "entity.experience_orb.pickup", "block.respawn_anchor.charge", "item.trident.return",
                    "entity.cat.ambient", "entity.chicken.egg", "entity.villager.celebrate")) {
                bo.runAndExpect("cosmetics teleport arrive " + sound, "Landing sound");
            }

            // The owner takes the bell off the list: Bo's arrival sound goes back to the server's, and Bo is told.
            bo.runAndExpect("cosmetics teleport arrive block.bell.use", "Landing sound");
            bo.forgetChat();
            assertThat(server.console("settings set teleport-sounds entity.enderman.teleport,block.note_block.pling"))
                    .contains("is now");
            bo.expectChat("no longer available");

            assertThat(server.console("settings set teleport-looks false")).contains("is now");
            goToSpawn(server, bo);
            assertThat(bo.soundsHeard()).contains("entity.enderman.teleport").doesNotContain("block.bell.use");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials", "RainsCosmetics")).isEmpty();
        }
    }

    /** Far out, landed, then /spawn with its warm-up — and only what that trip played is kept. */
    private static void goToSpawn(Server server, Bot bo) {
        server.console("tp Bo 400 120 400");
        Await.until("Bo is far out", Duration.ofSeconds(10), () -> bo.position().getX() > 390);
        Await.until("Bo has landed", Duration.ofSeconds(20), () -> {
            double before = bo.position().getY();
            Await.ticks(10);
            return Math.abs(bo.position().getY() - before) < 0.01;
        });
        bo.forgetEffects();
        bo.run("spawn");
        Await.until("Bo arrives at spawn", Duration.ofSeconds(15), () -> bo.position().getX() < 300);
        Await.ticks(10);
    }
}
