package de.raindancer.modules.moderation.store;

import de.raindancer.modules.moderation.model.CheckType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlertMutesTest {

    @TempDir
    Path folder;

    private final UUID mod = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    @DisplayName("a moderator's own mute is theirs alone")
    void personal() {
        AlertMutes mutes = new AlertMutes(folder);
        assertThat(mutes.mute(mod, CheckType.GRIM_COMBAT)).isTrue();
        assertThat(mutes.isMuted(mod, CheckType.GRIM_COMBAT)).isTrue();
        assertThat(mutes.isMuted(other, CheckType.GRIM_COMBAT)).isFalse();
        assertThat(mutes.isMuted(mod, CheckType.GRIM_MOVEMENT)).isFalse();
        assertThat(mutes.unmute(mod, CheckType.GRIM_COMBAT)).isTrue();
        assertThat(mutes.isMuted(mod, CheckType.GRIM_COMBAT)).isFalse();
    }

    @Test
    @DisplayName("a global mute is everybody's")
    void global() {
        AlertMutes mutes = new AlertMutes(folder);
        mutes.muteForEveryone(CheckType.GRIM_PACKETS);
        assertThat(mutes.isMuted(mod, CheckType.GRIM_PACKETS)).isTrue();
        assertThat(mutes.isMuted(other, CheckType.GRIM_PACKETS)).isTrue();
        mutes.unmuteForEveryone(CheckType.GRIM_PACKETS);
        assertThat(mutes.isMuted(mod, CheckType.GRIM_PACKETS)).isFalse();
    }

    @Test
    @DisplayName("a gathered line keeps only what this moderator still wants to hear")
    void gathered() {
        AlertMutes mutes = new AlertMutes(folder);
        mutes.mute(mod, CheckType.GRIM_WORLD);
        Map<CheckType, Integer> counts = Map.of(CheckType.GRIM_MOVEMENT, 12, CheckType.GRIM_WORLD, 4);
        assertThat(mutes.visible(mod, counts)).containsOnlyKeys(CheckType.GRIM_MOVEMENT);
        assertThat(mutes.visible(other, counts)).containsOnlyKeys(CheckType.GRIM_MOVEMENT, CheckType.GRIM_WORLD);
    }

    @Test
    @DisplayName("mutes, personal and global, survive a restart")
    void kept() {
        AlertMutes mutes = new AlertMutes(folder);
        mutes.mute(mod, CheckType.GRIM_COMBAT);
        mutes.muteForEveryone(CheckType.GRIM_PACKETS);
        assertThat(mutes.flush()).isTrue();

        AlertMutes reread = new AlertMutes(folder);
        reread.load();
        assertThat(reread.isMuted(mod, CheckType.GRIM_COMBAT)).isTrue();
        assertThat(reread.isMuted(other, CheckType.GRIM_PACKETS)).isTrue();
        assertThat(reread.mutedBy(mod)).containsExactly(CheckType.GRIM_COMBAT);
        assertThat(reread.mutedForEveryone()).containsExactly(CheckType.GRIM_PACKETS);
    }
}
