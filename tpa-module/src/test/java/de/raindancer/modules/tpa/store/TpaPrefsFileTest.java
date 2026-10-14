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

    @Test
    @DisplayName("a file that cannot be read is never written over, and a block is written with the names for people")
    void unreadableIsLeftAlone() throws Exception {
        UUID who = UUID.randomUUID();
        UUID pest = UUID.randomUUID();
        Path file = folder.resolve("tpa.yml");
        String broken = "players:\n  " + who + ":\n    blocked: [unclosed\n";
        Files.writeString(file, broken);
        TpaPrefsFile prefs = new TpaPrefsFile(file);
        prefs.load();

        assertThat(prefs.isReadable()).isFalse();
        assertThat(prefs.change(who, "Alex", now -> now.blocking(pest))).isFalse();
        assertThat(Files.readString(file)).isEqualTo(broken);

        Files.writeString(file, "");
        prefs.load();
        prefs.seen(pest, "Pest");
        assertThat(prefs.change(who, "Alex", now -> now.blocking(pest))).isTrue();
        assertThat(prefs.of(who).blocked()).containsExactly(pest);
        assertThat(Files.readString(file)).contains("name: Alex").contains(pest + ": Pest");
    }
}
