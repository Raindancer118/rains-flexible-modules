package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autopilot on a real server: a home picked in the menu is flown to by a mannequin of the player while they watch as a
 * spectator, and they land there in their own game mode; a player picked is asked first and flown to on a yes; a quit
 * mid-flight puts the player back at the start, never a spectator.
 */
@Tag("e2e")
class AutopilotScenarioTest {

    private static final int Y = 64;
    private static final Duration FLIGHT = Duration.ofSeconds(40);

    private static boolean near(Bot bot, double x, double z) {
        return Math.abs(bot.position().getX() - x) < 2 && Math.abs(bot.position().getZ() - z) < 2;
    }

    private static double cameraYaw(Server server) {
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("(-?[0-9.]+)f").matcher(
                server.console("data get entity @e[type=item_display,limit=1] Rotation[0]"));
        return found.find() ? Double.parseDouble(found.group(1)) : Double.NaN;
    }

    /** From one yaw to another, the short way round. */
    private static double turn(double from, double to) {
        double turned = (to - from) % 360;
        return turned > 180 ? turned - 360 : turned < -180 ? turned + 360 : turned;
    }

    private static boolean mannequin(Server server) {
        return server.console("execute if entity @e[type=mannequin]").contains("passed");
    }

    @Test
    @DisplayName("fly to a home, to a player who says yes, and back at the start after a quit mid-flight")
    void autopilot() {
        try (Server server = Server.start("autopilot", List.of("enchants-standalone:RainsEnchants-.*",
                "homes-standalone:RainsHomes-.*"), List.of("Enchants are up"))) {
            server.console("gamerule spawn_mobs false");
            server.console("forceload add -64 -16 208 16");
            server.console("fill -64 " + (Y - 1) + " -8 200 " + (Y - 1) + " 8 minecraft:stone");
            server.console("fill -64 " + Y + " -8 200 " + (Y + 6) + " 8 minecraft:air");
            // Ten minutes at I — Autopilot III flies again the moment it lands, which the flights below rely on.
            assertThat(server.console("settings set autopilot-cooldown-seconds 600")).contains("is now");
            Bot pilot = server.player("Pilot");
            server.console("gamemode survival Pilot");
            server.console("item replace entity Pilot armor.chest with elytra[enchantments={\"rainsenchants:autopilot\":3}]");

            // --- a home of one's own -------------------------------------------------------------------------------
            server.console("tp Pilot 180.5 " + Y + " 0.5");
            Await.ticks(20);
            pilot.run("sethome summit");
            Await.ticks(20);
            server.console("tp Pilot 0.5 " + Y + " 0.5");
            Await.ticks(20);
            pilot.run("autopilot");
            pilot.awaitWindow("Autopilot");
            pilot.forgetEffects();
            pilot.click("summit");
            Await.until("a spectator, watching", Duration.ofSeconds(10), () -> pilot.gameMode() == GameMode.SPECTATOR);
            Await.until("a mannequin of the pilot flies", Duration.ofSeconds(5), () -> mannequin(server));
            // The orbit camera, which a flight starts with: the mouse swings it round the flier, and it stays there.
            pilot.look(10, 0);
            Await.ticks(3);
            pilot.look(20, 0);
            Await.ticks(4);
            double swungFrom = cameraYaw(server);
            pilot.look(110, 0);
            Await.ticks(4);
            double swungTo = cameraYaw(server);
            assertThat(turn(swungFrom, swungTo)).as("swung round by the mouse's 90 degrees").isCloseTo(90,
                    org.assertj.core.api.Assertions.within(6.0));
            Await.ticks(10);
            assertThat(turn(swungTo, cameraYaw(server))).as("and held there, not circling on").isCloseTo(0,
                    org.assertj.core.api.Assertions.within(3.0));

            // D for the next camera: from the orbit camera to the chase one; A back.
            pilot.keys(false, false, false, true, false, false, false);
            Await.ticks(2);
            pilot.keys(false, false, false, false, false, false, false);
            Await.until("the chase camera", Duration.ofSeconds(3),
                    () -> pilot.actionBars().stream().anyMatch(line -> line.contains("Camera: Chase")));
            pilot.keys(false, false, true, false, false, false, false);
            Await.ticks(2);
            pilot.keys(false, false, false, false, false, false, false);
            Await.until("A goes back to the orbit camera", Duration.ofSeconds(3),
                    () -> pilot.actionBars().stream().anyMatch(line -> line.contains("Camera: Orbit")));
            Await.until("gliding on the elytra, not swimming", Duration.ofSeconds(5), () -> server.console(
                    "execute if entity @e[type=mannequin,nbt={FallFlying:1b}]").contains("passed"));
            assertThat(pilot.soundsHeard()).as("off like a rocket").contains("entity.firework_rocket.launch")
                    .noneMatch(sound -> sound.contains("trident"));
            Await.ticks(20);
            assertThat(server.console("execute if entity @e[type=mannequin,nbt={FallFlying:1b}]")).as("still gliding")
                    .contains("passed");
            // A spectator in flight jumps to nobody: the spectator menu's teleport is refused.
            server.console("execute as Pilot run tp Pilot -60.5 " + Y + " 0.5");
            Await.ticks(5);
            assertThat(near(pilot, -60.5, 0.5)).as("no teleport away mid-flight").isFalse();
            Await.until("landed and handed back", FLIGHT, () -> pilot.gameMode() == GameMode.SURVIVAL);
            Await.until("at the home", Duration.ofSeconds(5), () -> near(pilot, 180.5, 0.5));
            assertThat(mannequin(server)).as("the mannequin is gone").isFalse();
            assertThat(server.console("advancement test Pilot rainsenchants:autopilot")).doesNotContain("not");

            // --- a player, who is asked -------------------------------------------------------------------------------
            Bot host = server.player("Host");
            server.console("gamemode survival Host");
            server.console("tp Host -40.5 " + Y + " 0.5");
            Await.ticks(20);
            pilot.run("autopilot");
            pilot.awaitWindow("Autopilot");
            Await.until("Host is listed", Duration.ofSeconds(5), () -> pilot.window().orElseThrow().items().values()
                    .stream().anyMatch(item -> item.name().contains("Host")));
            assertThat(pilot.window().orElseThrow().items().values().stream().filter(item -> item.name().contains("Host"))
                    .flatMap(item -> item.lore().stream()).noneMatch(line -> line.contains("blocks")))
                    .as("no player's distance given away").isTrue();
            pilot.click("Host");
            Await.until("Host is asked", Duration.ofSeconds(10),
                    () -> host.chatText().stream().anyMatch(line -> line.contains("wants to fly to you")));
            host.say("yes");
            Await.until("off to Host", Duration.ofSeconds(10), () -> pilot.gameMode() == GameMode.SPECTATOR);
            Await.until("landed beside Host", FLIGHT, () -> pilot.gameMode() == GameMode.SURVIVAL && near(pilot, -40.5, 0.5));

            // --- a quit mid-flight: back at the start, in survival ---------------------------------------------------
            pilot.run("autopilot");
            pilot.awaitWindow("Autopilot");
            pilot.click("summit");
            Await.until("off again", Duration.ofSeconds(10), () -> pilot.gameMode() == GameMode.SPECTATOR);
            Await.ticks(20);
            pilot.leave();
            Await.ticks(20);
            assertThat(mannequin(server)).as("no mannequin left behind").isFalse();
            pilot.rejoin();
            Await.ticks(40);
            assertThat(pilot.gameMode()).isEqualTo(GameMode.SURVIVAL);
            assertThat(near(pilot, -40.5, 0.5)).as("back where the flight began").isTrue();
        }
    }
}
