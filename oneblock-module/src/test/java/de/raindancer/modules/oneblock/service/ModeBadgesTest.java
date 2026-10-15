package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.OneBlockModule;
import de.raindancer.modules.oneblock.model.EconomyMode;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModeBadgesTest {

    @TempDir
    Path dir;
    private Messages messages;
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();

    @BeforeEach
    void words() {
        messages = new Messages(dir.resolve("messages.yml"));
        messages.defineFrom(OneBlockModule.class.getResourceAsStream("messages.yml"));
    }

    private String plain(ModeBadges badges, UUID player) {
        return badges.badgeOf(player).map(PlainTextComponentSerializer.plainText()::serialize).orElse("");
    }

    @Test
    @DisplayName("elsewhere: what the shared table said, an undecided island and no island showing nothing")
    void elsewhere() {
        ModeBadges badges = new ModeBadges(messages, null);
        badges.refresh(List.of(ada, bo, cy), () -> Optional.of(Map.of(ada, "smp", bo, "pure", cy, "undecided")));

        assertThat(plain(badges, ada)).contains("◆");
        assertThat(plain(badges, bo)).contains("✦");
        assertThat(plain(badges, cy)).isEmpty();
        assertThat(plain(badges, UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a failed read keeps what was known; somebody gone offline is forgotten")
    void keepsAndForgets() {
        ModeBadges badges = new ModeBadges(messages, null);
        badges.refresh(List.of(ada, bo), () -> Optional.of(Map.of(ada, "smp", bo, "pure")));
        badges.refresh(List.of(ada, bo), Optional::empty);
        assertThat(plain(badges, ada)).contains("◆");

        badges.refresh(List.of(ada), () -> Optional.of(Map.of(ada, "smp")));
        assertThat(plain(badges, bo)).isEmpty();
    }

    @Test
    @DisplayName("on the island server the book answers, never a stale copy")
    void direct() {
        ModeBadges badges = new ModeBadges(messages, player -> player.equals(ada) ? Optional.of(EconomyMode.PURE)
                : Optional.empty());
        badges.refresh(List.of(ada), () -> Optional.of(Map.of(ada, "smp")));

        assertThat(plain(badges, ada)).contains("✦");
        assertThat(plain(badges, bo)).isEmpty();
    }
}
