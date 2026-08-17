package de.raindancer.modules.chat.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.chat.ChatSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ChatQualityServiceTest {

    private final AtomicLong now = new AtomicLong(0L);
    private final UUID player = UUID.randomUUID();

    private ChatQualityService service(ChatSettings settings) {
        return new ChatQualityService(settings, now::get);
    }

    @Nested
    @DisplayName("a first message")
    class FirstMessage {

        @Test
        @DisplayName("is never blocked by cooldown or repeat — there is nothing to compare against")
        void alwaysAllowed() {
            ChatSettings strict = new ChatSettings("<name>: <message>", true, true, true, true, 70,
                    8, true, 10, 0, true, 200, true);
            ChatQualityService service = service(strict);

            Verdict verdict = service.check(player, "hello there", false);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("caps")
    class Caps {

        @Test
        @DisplayName("refuses shouting when the filter is on")
        void refusesWhenEnabled() {
            ChatQualityService service = service(ChatSettings.DEFAULTS);

            Verdict verdict = service.check(player, "THIS IS SHOUTING AT EVERYBODY", false);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.caps");
        }

        @Test
        @DisplayName("allows shouting when the filter is off")
        void allowsWhenDisabled() {
            ChatSettings off = new ChatSettings("<name>: <message>", true, true, true, false, 70, 8,
                    false, 0, 0, true, 200, true);
            ChatQualityService service = service(off);

            Verdict verdict = service.check(player, "THIS IS SHOUTING AT EVERYBODY", false);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("repeat")
    class Repeat {

        @Test
        @DisplayName("refuses sending the same message twice in a row")
        void refusesRepeat() {
            ChatQualityService service = service(ChatSettings.DEFAULTS);
            service.check(player, "hello there", false);
            service.recordSent(player, "hello there");

            Verdict verdict = service.check(player, "hello there", false);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.repeat");
        }

        @Test
        @DisplayName("a different message after is allowed")
        void allowsDifferentMessage() {
            ChatQualityService service = service(ChatSettings.DEFAULTS);
            service.recordSent(player, "hello there");

            Verdict verdict = service.check(player, "goodbye", false);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("cooldown")
    class Cooldown {

        @Test
        @DisplayName("refuses a second message inside the window")
        void refusesTooSoon() {
            ChatSettings withCooldown = new ChatSettings("<name>: <message>", true, true, false,
                    false, 70, 8, false, 5, 0, true, 200, true);
            ChatQualityService service = service(withCooldown);
            service.recordSent(player, "first");
            now.set(2_000L);

            Verdict verdict = service.check(player, "second", false);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.cooldown");
        }

        @Test
        @DisplayName("allows once the window has passed")
        void allowsAfterWindow() {
            ChatSettings withCooldown = new ChatSettings("<name>: <message>", true, true, false,
                    false, 70, 8, false, 5, 0, true, 200, true);
            ChatQualityService service = service(withCooldown);
            service.recordSent(player, "first");
            now.set(6_000L);

            Verdict verdict = service.check(player, "second", false);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("bypass")
    class Bypass {

        @Test
        @DisplayName("skips every check, whatever the settings say")
        void bypassesEverything() {
            ChatQualityService service = service(ChatSettings.DEFAULTS);
            service.recordSent(player, "SHOUTING SHOUTING SHOUTING");

            Verdict verdict = service.check(player, "SHOUTING SHOUTING SHOUTING", true);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("slowmode override")
    class SlowmodeOverride {

        @Test
        @DisplayName("uses the settings default until /chat slowmode overrides it")
        void defaultsFromSettings() {
            ChatSettings withDefault = new ChatSettings("<name>: <message>", true, true, false,
                    false, 70, 8, false, 0, 15, true, 200, true);
            ChatQualityService service = service(withDefault);

            assertThat(service.effectiveSlowmode()).isEqualTo(15);
            assertThat(service.isSlowmodeOverridden()).isFalse();
        }

        @Test
        @DisplayName("an override replaces the default until cleared")
        void overrideThenClear() {
            ChatQualityService service = service(ChatSettings.DEFAULTS);

            service.overrideSlowmode(30);
            assertThat(service.effectiveSlowmode()).isEqualTo(30);
            assertThat(service.isSlowmodeOverridden()).isTrue();

            service.clearSlowmodeOverride();
            assertThat(service.isSlowmodeOverridden()).isFalse();
            assertThat(service.effectiveSlowmode()).isEqualTo(ChatSettings.DEFAULTS.defaultSlowmode());
        }

        @Test
        @DisplayName("an active slowmode override blocks a second message inside its window")
        void overrideBlocksLikeACooldown() {
            ChatQualityService service = service(ChatSettings.DEFAULTS);
            service.overrideSlowmode(10);
            service.recordSent(player, "first");
            now.set(2_000L);

            Verdict verdict = service.check(player, "second", false);

            assertThat(verdict.isRefused()).isTrue();
            assertThat(verdict.reason()).isEqualTo("chat.quality.slowmode");
        }
    }

    @Nested
    @DisplayName("forgetting")
    class Forgetting {

        @Test
        @DisplayName("drops the remembered last message, so the next one starts fresh")
        void forgetDropsState() {
            ChatSettings withCooldown = new ChatSettings("<name>: <message>", true, true, false,
                    false, 70, 8, false, 5, 0, true, 200, true);
            ChatQualityService service = service(withCooldown);
            service.recordSent(player, "first");

            service.forget(player);
            Verdict verdict = service.check(player, "second", false);

            assertThat(verdict.isAllowed()).isTrue();
        }
    }
}
