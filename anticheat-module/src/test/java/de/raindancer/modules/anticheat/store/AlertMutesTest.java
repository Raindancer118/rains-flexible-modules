package de.raindancer.modules.anticheat.store;

import de.raindancer.modules.anticheat.model.CheckType;
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
        assertThat(mutes.mute(mod, CheckType.AUTOCLICKER)).isTrue();
        assertThat(mutes.isMuted(mod, CheckType.AUTOCLICKER)).isTrue();
        assertThat(mutes.isMuted(other, CheckType.AUTOCLICKER)).isFalse();
        assertThat(mutes.isMuted(mod, CheckType.FLY)).isFalse();
        assertThat(mutes.unmute(mod, CheckType.AUTOCLICKER)).isTrue();
        assertThat(mutes.isMuted(mod, CheckType.AUTOCLICKER)).isFalse();
    }

    @Test
    @DisplayName("a global mute is everybody's")
    void global() {
        AlertMutes mutes = new AlertMutes(folder);
        mutes.muteForEveryone(CheckType.BLINK);
        assertThat(mutes.isMuted(mod, CheckType.BLINK)).isTrue();
        assertThat(mutes.isMuted(other, CheckType.BLINK)).isTrue();
        mutes.unmuteForEveryone(CheckType.BLINK);
        assertThat(mutes.isMuted(mod, CheckType.BLINK)).isFalse();
    }

    @Test
    @DisplayName("a gathered line keeps only what this moderator still wants to hear")
    void gathered() {
        AlertMutes mutes = new AlertMutes(folder);
        mutes.mute(mod, CheckType.SPEED);
        Map<CheckType, Integer> counts = Map.of(CheckType.FLY, 12, CheckType.SPEED, 4);
        assertThat(mutes.visible(mod, counts)).containsOnlyKeys(CheckType.FLY);
        assertThat(mutes.visible(other, counts)).containsOnlyKeys(CheckType.FLY, CheckType.SPEED);
    }

    @Test
    @DisplayName("mutes, personal and global, survive a restart")
    void kept() {
        AlertMutes mutes = new AlertMutes(folder);
        mutes.mute(mod, CheckType.AUTOCLICKER);
        mutes.muteForEveryone(CheckType.BLINK);
        assertThat(mutes.flush()).isTrue();

        AlertMutes reread = new AlertMutes(folder);
        reread.load();
        assertThat(reread.isMuted(mod, CheckType.AUTOCLICKER)).isTrue();
        assertThat(reread.isMuted(other, CheckType.BLINK)).isTrue();
        assertThat(reread.mutedBy(mod)).containsExactly(CheckType.AUTOCLICKER);
        assertThat(reread.mutedForEveryone()).containsExactly(CheckType.BLINK);
    }
}
