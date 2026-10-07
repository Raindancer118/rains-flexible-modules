package de.raindancer.modules.voicebridge.service;

import de.raindancer.modules.voicebridge.rules.LinkCodeRule;
import de.raindancer.modules.voicebridge.store.LinkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LinkServiceTest {

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(0);
    private final UUID alex = UUID.randomUUID();
    private LinkStore store;
    private LinkService links;

    @BeforeEach
    void setUp() {
        store = new LinkStore(folder.resolve("links.yml"));
        links = new LinkService(store, new LinkCodeRule(), new Random(7), now::get);
    }

    @Test
    @DisplayName("a code is six characters nobody confuses with each other (no 0/O, 1/I/L)")
    void codesAreReadable() {
        String code = links.codeFor(alex);

        assertThat(code).hasSize(6).matches("[A-HJKMNP-Z2-9]{6}");
    }

    @Test
    @DisplayName("redeemed from Discord, the code links that account to the player who asked")
    void redeem() {
        String code = links.codeFor(alex);

        assertThat(links.redeem(code, 99L)).contains(alex);
        assertThat(store.playerOf(99L)).contains(alex);
        assertThat(links.redeem(code, 98L)).as("a code works once").isEmpty();
    }

    @Test
    @DisplayName("a code runs out after ten minutes")
    void expires() {
        String code = links.codeFor(alex);
        now.set(LinkService.CODE_LIFETIME_MILLIS);

        assertThat(links.redeem(code, 99L)).isEmpty();
    }

    @Test
    @DisplayName("asking again replaces the old code, so only the newest one is ever valid")
    void newestWins() {
        String first = links.codeFor(alex);
        String second = links.codeFor(alex);

        assertThat(links.redeem(first, 99L)).isEmpty();
        assertThat(links.redeem(second, 99L)).contains(alex);
    }

    @Test
    @DisplayName("five wrong guesses lock that Discord account out for a while, so codes cannot be brute-forced")
    void guessingIsLimited() {
        String code = links.codeFor(alex);
        for (int i = 0; i < LinkService.MOST_WRONG_GUESSES; i++) {
            links.redeem("AAAAAA", 99L);
        }

        assertThat(links.redeem(code, 99L)).as("even the right code, while locked").isEmpty();
        assertThat(links.redeem(code, 98L)).as("another account is not punished").contains(alex);
    }

    @Test
    @DisplayName("guesses from many accounts together are capped too, so alt accounts do not multiply the tries")
    void guessingIsLimitedAcrossAccounts() {
        String code = links.codeFor(alex);
        for (long account = 1; account <= LinkService.MOST_WRONG_GUESSES_OVERALL; account++) {
            links.redeem("AAAAAA", 1000 + account);
        }

        assertThat(links.redeem(code, 99L)).as("nobody links while the server-wide cap is hit").isEmpty();

        now.set(LinkService.CODE_LIFETIME_MILLIS);
        String fresh = links.codeFor(alex);
        assertThat(links.redeem(fresh, 99L)).as("the cap lifts after the window").contains(alex);
    }
}
