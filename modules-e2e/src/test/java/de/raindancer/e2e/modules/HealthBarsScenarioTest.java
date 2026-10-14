package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Health bars on a real server, as each player sees them: the other player's bar but never their own, gone for
 * whoever switches them off, in the style each picked — hearts or a bar — and over a mob once it is hit.
 */
@Tag("e2e")
class HealthBarsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Pattern INTS = Pattern.compile("\\[I;\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)]");

    /**
     * The nearest text display to {@code at} (an {@code execute} position) whose text contains {@code marker}:
     * looked at nearest first, each one passed over tagged so the next look finds the next.
     */
    private static UUID display(Server server, String at, String marker) {
        String nearest = "@e[type=minecraft:text_display,distance=..3,sort=nearest,limit=1,tag=!seen]";
        try {
            return Await.value(() -> "a health display near " + at + " reading " + marker, WAIT, () -> {
                server.console("tag @e[type=minecraft:text_display] remove seen");
                for (int tries = 0; tries < 6; tries++) {
                    String text = server.console("execute " + at + " run data get entity " + nearest + " text");
                    if (text.contains(marker)) {
                        Matcher found = INTS.matcher(server.console("execute " + at + " run data get entity " + nearest + " UUID"));
                        if (found.find()) {
                            long most = ((long) Integer.parseInt(found.group(1)) << 32) | (Integer.parseInt(found.group(2)) & 0xffffffffL);
                            long least = ((long) Integer.parseInt(found.group(3)) << 32) | (Integer.parseInt(found.group(4)) & 0xffffffffL);
                            return new UUID(most, least);
                        }
                    }
                    server.console("execute " + at + " run tag " + nearest + " add seen");
                }
                return null;
            });
        } finally {
            server.console("tag @e[type=minecraft:text_display] remove seen");
        }
    }

    @Test
    @DisplayName("seen by others not oneself, switched off for one, hearts or bar each, and over a hit mob")
    void healthBars() {
        try (Server server = Server.start("healthbars", List.of("healthbars-standalone:RainsHealthBars-.*"),
                List.of("Health bars are up"))) {
            Bot ada = server.player("Ada");
            Bot bo = server.player("Bo");
            server.console("forceload add -16 -16 16 16");
            server.console("fill -8 63 -8 16 63 16 minecraft:stone");
            server.console("fill -8 64 -8 16 70 16 minecraft:air");
            server.console("gamemode survival @a");
            server.console("tp Ada 0.5 64 0.5");
            server.console("tp Bo 6.5 64 0.5");
            Await.ticks(40);

            // --- Ada's hearts: Bo sees them, Ada does not see her own ---------------------------------------
            UUID adaHearts = display(server, "at Ada", "❤");
            Await.until("Bo sees Ada's health", WAIT, () -> bo.sees(adaHearts));
            assertThat(ada.sees(adaHearts)).as("nobody sees their own").isFalse();

            // --- Bo switches them off: gone for Bo only ------------------------------------------------------
            bo.run("hp off");
            Await.until("hidden for Bo", WAIT, () -> !bo.sees(adaHearts));
            UUID boHearts = display(server, "at Bo", "❤");
            Await.until("Ada still sees Bo's", WAIT, () -> ada.sees(boHearts));

            // --- Bo wants the bar: a display in that style, for Bo alone ---------------------------------------
            bo.run("hp on");
            bo.run("hp style bar");
            UUID adaBar = display(server, "at Ada", "█");
            Await.until("Bo sees Ada's bar", WAIT, () -> bo.sees(adaBar));
            Await.until("and not her hearts", WAIT, () -> !bo.sees(adaHearts));
            assertThat(ada.sees(boHearts)).as("Ada keeps hearts").isTrue();

            // --- an invisible player's bar gives nothing away -------------------------------------------------
            server.console("effect give Bo minecraft:invisibility 30 0 true");
            Await.until("Bo's bar is gone for Ada while Bo is invisible", WAIT, () -> !ada.sees(boHearts));
            server.console("effect clear Bo");
            Await.until("and back once Bo is seen again", WAIT, () -> ada.sees(boHearts));

            // --- a mob gets one once it is hit ---------------------------------------------------------------
            server.console("summon minecraft:zombie 3.5 64 4.5 {NoAI:1b,PersistenceRequired:1b}");
            Await.ticks(10);
            server.console("damage @e[type=minecraft:zombie,limit=1] 4");
            UUID zombie = display(server, "positioned 3.5 64 4.5", "❤");
            Await.until("Ada sees the zombie's health", WAIT, () -> ada.sees(zombie));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsHealthBars")).isEmpty();
        }
    }
}
