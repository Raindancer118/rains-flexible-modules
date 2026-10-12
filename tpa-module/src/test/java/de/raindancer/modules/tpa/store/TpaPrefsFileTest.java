package de.raindancer.modules.tpa.store;

import de.raindancer.modules.tpa.model.TpaPrefs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TpaPrefsFileTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("auto-accept is written and read back, and an old file without it reads as off")
    void autoAcceptRoundTrips() throws Exception {
        UUID who = UUID.randomUUID();
        Path file = folder.resolve("tpa.yml");
        TpaPrefsFile first = new TpaPrefsFile(file);
        first.load();
        first.set(who, "Alex", TpaPrefs.untouched().autoAccepting(true));

        TpaPrefsFile second = new TpaPrefsFile(file);
        second.load();
        assertThat(second.of(who).autoAccept()).isTrue();
        assertThat(second.of(who).accepting()).isTrue();

        Files.writeString(file, "players:\n  " + who + ":\n    accepting: false\n");
        TpaPrefsFile old = new TpaPrefsFile(file);
        old.load();
        assertThat(old.of(who).accepting()).isFalse();
        assertThat(old.of(who).autoAccept()).isFalse();
    }
}
