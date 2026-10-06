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
 * The Banhammer on a real server: one real player hit ({@code /damage … by}, which vanilla records as
 * the attacker's own melee blow) with a mace whose name is coloured in two parts.
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

    /** One ordinary hit, as vanilla records an attacker's own melee swing. */
    private static void hit(Server server, String victim, String attacker) {
        server.console("damage " + victim + " 1 minecraft:player_attack by " + attacker);
    }

    @Test
    @DisplayName("one hit from an op's mace named Banhammer bans; nobody else, and nothing else, does")
    void banhammer() {
        try (Server server = Server.start("banhammer",
                List.of("moderation-standalone:RainsModeration-.*"), List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");
            Bot dee = server.player("Dee");

            // A plain mace with another name is just a mace.
            arm(server, "Ada", PLAIN_HAMMER);
            hit(server, "Cy", "Ada");
            Await.ticks(20);
            assertThat(cy.isOnline()).as("Cy was hit by a mace called Hammer and must still be here").isTrue();

            // An ordinary player swinging the real thing bans nobody.
            arm(server, "Dee", COLOURED_BANHAMMER);
            hit(server, "Cy", "Dee");
            Await.ticks(20);
            assertThat(cy.isOnline()).as("Dee holds no Banhammer node").isTrue();

            // Thorns on the op's armour is booked to the op, but is not a swing: nobody is banned by it.
            arm(server, "Ada", COLOURED_BANHAMMER);
            server.console("damage Cy 1 minecraft:thorns by Ada");
            Await.ticks(20);
            assertThat(cy.isOnline()).as("thorns from an op holding the Banhammer bans nobody").isTrue();

            // The op with the coloured Banhammer bans on the spot.
            arm(server, "Ada", COLOURED_BANHAMMER);
            ada.forgetChat();
            hit(server, "Bo", "Ada");
            Await.until("Bo is thrown off", Duration.ofSeconds(15), () -> !bo.isOnline());
            assertThat(bo.disconnectReason()).contains("YOU'VE BEEN HIT WITH THE BANHAMMER BY Ada");
            Await.until("Ada is told", Duration.ofSeconds(10),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("hit with the Banhammer")));
            assertThat(server.console("banlist players")).contains("Bo");

            Bot again = server.paper.bot("Bo");
            assertThat(again.joinRefused()).contains("BANHAMMER");

            // Switched off, the hammer is a mace.
            assertThat(server.console("settings set punishments.banhammer false")).contains("is now");
            hit(server, "Dee", "Ada");
            Await.ticks(20);
            assertThat(dee.isOnline()).isTrue();

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
