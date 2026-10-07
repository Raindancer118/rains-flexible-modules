package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RainsPlayerUtils beside RainsEssentials on a real server: an admin gives somebody a nickname, then points
 * every kind of action at them by that nickname — and the server's prefix is changed under all of it.
 */
@Tag("e2e")
class PlayerUtilsScenarioTest {

    @Test
    @DisplayName("actions by nickname, flight that outlasts a gamemode change, sudo's limits, a shared prefix")
    void everythingByNickname() {
        try (Server server = Server.start("playerutils",
                List.of("essentials-standalone:RainsEssentials-.*", "playerutils-standalone:RainsPlayerUtils-.*"),
                List.of("Player utils are up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            // An admin names somebody else; from then on every command knows them by it.
            ada.runAndExpect("nick Bo Bobby the Brave", "Bobby the Brave");

            ada.runAndExpect("damage Bobby_the_Brave 3", "lost 3 heart");
            Await.until("Bo is hurt", Duration.ofSeconds(5), () -> bo.health() <= 14.01f);
            ada.runAndExpect("heal Bobby_the_Brave", "patched up");
            Await.until("Bo is healed", Duration.ofSeconds(5), () -> bo.health() >= 19.99f);

            // Given flight survives the game taking it away on a gamemode change.
            ada.runAndExpect("fly Bobby_the_Brave on", "can fly");
            server.console("gamemode creative Bo");
            server.console("gamemode survival Bo");
            Await.ticks(5);
            assertThat(server.console("data get entity Bo abilities.mayfly")).contains("1b");

            ada.runAndExpect("scale Bobby_the_Brave 2x", "2");
            assertThat(server.console("attribute Bo minecraft:scale base get")).contains("2");

            // The headless bot applies no velocity packets, so only the server's answer is checked here.
            ada.runAndExpect("launch Bobby_the_Brave up 2", "airborne");

            // A command somebody may only point at themselves says so when aimed at another (one they lack entirely is hidden, like vanilla).
            bo.runAndExpect("status Ada", "but not");

            // Sudo speaks for them, but never hands out power.
            ada.run("sudo Bobby_the_Brave c:hello from the other side");
            Await.until("Bo said it", Duration.ofSeconds(5),
                    () -> !server.paper.logLines(line -> line.contains("hello from the other side")).isEmpty());
            ada.runAndExpect("sudo Bobby_the_Brave op Bo", "never run as somebody else");
            assertThat(server.console("data get entity Bo abilities")).doesNotContain("op");

            // A wipe takes the inventory — typed with confirm, since the admin is a player who could also
            // have used the window.
            server.console("give Bo dirt 5");
            Await.until("Bo has dirt", Duration.ofSeconds(5), () -> !bo.items().isEmpty());
            ada.runAndExpect("wipe Bobby_the_Brave confirm", "are gone");
            Await.until("Bo's inventory is empty", Duration.ofSeconds(5), () -> bo.items().isEmpty());

            ada.runAndExpect("ping Bobby_the_Brave", "ms");

            // Selectors reach everybody they match.
            ada.runAndExpect("feed @a", "done to 2 of 2");

            ada.runAndExpect("hunger Bobby_the_Brave", "drumsticks");

            // One tag for every plugin, styled, from the command — and it shows on the next line.
            ada.runAndExpect("prefix mode shared", "Prefix changed");
            ada.runAndExpect("prefix tag Lilly SMP", "Prefix changed");
            ada.runAndExpect("prefix style #ff8800,#ffee00|bold", "Prefix changed");
            ada.forgetChat();
            ada.runAndExpect("feed Bobby_the_Brave", "Lilly SMP");

            // Somebody offline, named by nickname: "not online", not "nobody is called that".
            bo.leave();
            Await.ticks(10);
            ada.runAndExpect("heal Bobby_the_Brave", "not online");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials", "RainsPlayerUtils")).isEmpty();
        }
    }
}
