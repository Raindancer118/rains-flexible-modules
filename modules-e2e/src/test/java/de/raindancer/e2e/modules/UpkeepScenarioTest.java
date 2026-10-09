package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Claim upkeep billed by staff on a real server: by command, from the ledger, and seen on the claim. */
@Tag("e2e")
class UpkeepScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static void expect(Bot bot, String what, String text) {
        try {
            Await.until(what, WAIT, () -> said(bot, text));
        } catch (AssertionError failed) {
            throw new AssertionError(failed.getMessage() + " — " + bot.name() + " saw " + bot.chatText(), failed);
        }
    }

    private static void set(Server server, String key, String value) {
        assertThat(server.console("settings set " + key + " " + value)).contains("is now");
        Await.ticks(20);
    }

    private static int slotNamed(Bot bot, String text) {
        return Await.value("a button named " + text, WAIT, () -> bot.window().flatMap(window -> window.top()
                .entrySet().stream().filter(entry -> entry.getValue().name().contains(text))
                .map(Map.Entry::getKey).findFirst()).orElse(null));
    }

    private static Bot.Item itemNamed(Bot bot, String text) {
        return bot.window().orElseThrow().top().get(slotNamed(bot, text));
    }

    @Test
    @DisplayName("an op bills one player now, then picks them in the ledger, and sees their upkeep on the claim")
    void staffBillNow() {
        try (Server server = Server.start("upkeep", List.of("economy-standalone:RainsEconomy-.*",
                "claims-standalone:RainsExtendedClaims-.*"), List.of("The economy is up"))) {
            Await.ticks(60);
            set(server, "limits.min-area-blocks", "1");
            set(server, "upkeep.enabled", "true");
            set(server, "upkeep.per-chunk", "10");
            Bot bo = server.player("Bo");
            Bot op = server.admin("Op");
            Await.ticks(20);

            // ---- Bo claims a small square around where he stands
            bo.run("claim new");
            expect(bo, "the selection starts", "Marking out");
            bo.hold(bo.hotbarSlotOf(item -> item.material().toLowerCase().contains("stick")
                    || item.material().toLowerCase().contains("hoe")));
            var at = bo.position();
            int x = (int) Math.floor(at.getX());
            int y = (int) Math.floor(at.getY()) - 1;
            int z = (int) Math.floor(at.getZ());
            // Kept inside his own chunk, so the bill is one chunk's worth wherever he spawned.
            int chunkX = Math.floorDiv(x, 16) * 16;
            int chunkZ = Math.floorDiv(z, 16) * 16;
            bo.useOn(Math.max(chunkX, x - 2), y, Math.max(chunkZ, z - 2));
            expect(bo, "the first corner", "Corner");
            bo.useOn(Math.min(chunkX + 15, x + 2), y, Math.min(chunkZ + 15, z + 2));
            expect(bo, "a name is asked for", "What should it be called?");
            bo.say("home");
            expect(bo, "the claim is made", "Claimed");

            // ---- upkeep is off until due: the op bills Bo alone, now
            bo.forgetChat();
            op.forgetChat();
            op.run("claimadmin upkeep billnow Bo");
            expect(op, "the op hears what it came to", "Billed 1 owner(s): 1 paid ⛃10");
            expect(bo, "Bo is told about his bill", "Claim upkeep: ⛃10 paid");
            bo.forgetChat();
            bo.run("balance");
            expect(bo, "10 left his account", "You have ⛃990");

            // ---- the ledger: Bo is listed with his bill, picked with a right click and billed again
            op.run("claimadmin upkeep");
            op.awaitWindow("All upkeep");
            assertThat(String.join(" ", itemNamed(op, "Bo").lore())).contains("⛃10");
            op.rightClickSlot(slotNamed(op, "Bo"));
            op.clickSlot(slotNamed(op, "Bill the 1 picked"));
            op.awaitWindow("Bill");
            op.forgetChat();
            op.clickSlot(slotNamed(op, "Yes"));
            expect(op, "the picked owner is billed", "Billed 1 owner(s): 1 paid ⛃10");
            bo.forgetChat();
            bo.run("balance");
            expect(bo, "another 10 left his account", "You have ⛃980");
            op.closeWindow();

            // ---- staff see the owner's upkeep on the claim itself
            op.run("claimadmin");
            op.awaitWindow("Server");
            assertThat(String.join(" ", itemNamed(op, "What everyone pays").lore())).contains("1 owner(s)");
            op.clickSlot(slotNamed(op, "Browse every claim"));
            op.awaitWindow("every clai");
            op.clickSlot(slotNamed(op, "home"));
            op.awaitWindow("staff view");
            assertThat(String.join(" ", itemNamed(op, "Upkeep").lore())).contains("Bo pays ⛃10");

            // ---- and the console gets the list as text
            assertThat(server.console("claimadmin upkeep")).contains("1 owner(s)");
        }
    }
}
