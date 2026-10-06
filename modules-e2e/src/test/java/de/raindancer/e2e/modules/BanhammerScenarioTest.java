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
 * The Banhammer on a real server: a real player kill ({@code /damage … by}, which vanilla records as
 * the killer's own melee blow) with a mace whose name is coloured in two parts.
 */
@Tag("e2e")
class BanhammerScenarioTest {

    private static final String COLOURED_BANHAMMER =
            "minecraft:mace[minecraft:custom_name=[{text:'Ban',color:'red'},{text:'hammer',color:'gold',bold:true}]]";
    private static final String PLAIN_HAMMER = "minecraft:mace[minecraft:custom_name='Hammer']";

    private static void arm(Server server, String who, String item) {
        server.console("item replace entity " + who + " weapon.mainhand with " + item);
        Await.ticks(5);
    }

    private static void kill(Server server, String victim, String killer) {
        server.console("damage " + victim + " 1000 minecraft:player_attack by " + killer);
    }

    @Test
    @DisplayName("an op killing with a mace named Banhammer bans; nobody else, and nothing else, does")
    void banhammer() {
        try (Server server = Server.start("banhammer",
                List.of("moderation-standalone:RainsModeration-.*"), List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");
            Bot dee = server.player("Dee");

            // A plain mace with another name is just a mace.
            arm(server, "Ada", PLAIN_HAMMER);
            kill(server, "Cy", "Ada");
            Await.until("Cy is dead", Duration.ofSeconds(10), cy::isDead);
            Await.ticks(20);
            assertThat(cy.isOnline()).as("Cy was killed by a mace called Hammer and must still be here").isTrue();
            cy.respawn();
            Await.until("Cy is back", Duration.ofSeconds(10), () -> !cy.isDead());

            // An ordinary player swinging the real thing bans nobody.
            arm(server, "Dee", COLOURED_BANHAMMER);
            kill(server, "Cy", "Dee");
            Await.ticks(20);
            assertThat(cy.isOnline()).as("Dee holds no Banhammer node").isTrue();

            // The op with the coloured Banhammer bans on the spot.
            arm(server, "Ada", COLOURED_BANHAMMER);
            ada.forgetChat();
            kill(server, "Bo", "Ada");
            Await.until("Bo is thrown off", Duration.ofSeconds(15), () -> !bo.isOnline());
            assertThat(bo.disconnectReason()).contains("YOU'VE BEEN HIT WITH THE BANHAMMER BY Ada");
            Await.until("Ada is told", Duration.ofSeconds(10),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("hit with the Banhammer")));
            assertThat(server.console("banlist players")).contains("Bo");

            Bot again = server.paper.bot("Bo");
            assertThat(again.joinRefused()).contains("BANHAMMER");

            // Switched off, the hammer is a mace.
            assertThat(server.console("settings set punishments.banhammer false")).contains("is now");
            kill(server, "Dee", "Ada");
            Await.ticks(20);
            assertThat(dee.isOnline()).isTrue();

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
