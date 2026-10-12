package de.raindancer.modules.chat.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PanelFeedTest {

    @TempDir
    Path dir;

    private final UUID tom = UUID.randomUUID();

    private JsonObject written() throws Exception {
        return JsonParser.parseString(Files.readString(dir.resolve(PanelFeed.FILE))).getAsJsonObject();
    }

    @Test
    @DisplayName("writes each line as players see it, with who said it and whether it came from the panel")
    void writesLines() throws Exception {
        PanelFeed feed = new PanelFeed(dir, 100);
        feed.add(1000, tom, "Raindancer118", "hi", Component.text("hi", NamedTextColor.GOLD), false);
        feed.add(2000, tom, "Raindancer118", "from the panel", Component.text("x"), true);

        assertThat(feed.flush()).isTrue();

        JsonObject file = written();
        assertThat(file.get("version").getAsInt()).isEqualTo(1);
        var lines = file.getAsJsonArray("lines");
        assertThat(lines).hasSize(2);
        JsonObject first = lines.get(0).getAsJsonObject();
        assertThat(first.get("id").getAsLong()).isEqualTo(1);
        assertThat(first.get("at").getAsLong()).isEqualTo(1000);
        assertThat(first.get("player").getAsString()).isEqualTo(tom.toString());
        assertThat(first.get("name").getAsString()).isEqualTo("Raindancer118");
        assertThat(first.get("text").getAsString()).isEqualTo("hi");
        assertThat(first.get("panel").getAsBoolean()).isFalse();
        assertThat(first.getAsJsonObject("line").get("color").getAsString()).isEqualTo("gold");
        assertThat(lines.get(1).getAsJsonObject().get("panel").getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("keeps only the newest lines, and writes nothing when nothing changed")
    void capsAndSkips() throws Exception {
        PanelFeed feed = new PanelFeed(dir, 3);
        for (int i = 1; i <= 5; i++) {
            feed.add(i, tom, "Tom", "line " + i, Component.text("line " + i), false);
        }
        assertThat(feed.flush()).isTrue();
        assertThat(feed.flush()).isFalse();

        var lines = written().getAsJsonArray("lines");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).getAsJsonObject().get("text").getAsString()).isEqualTo("line 3");
        assertThat(lines.get(2).getAsJsonObject().get("id").getAsLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("picks up where it left off after a restart, numbering on")
    void survivesRestart() throws Exception {
        PanelFeed before = new PanelFeed(dir, 100);
        before.add(1, tom, "Tom", "before", Component.text("before"), false);
        before.flush();

        PanelFeed after = new PanelFeed(dir, 100);
        after.load();
        after.add(2, tom, "Tom", "after", Component.text("after"), false);
        after.flush();

        var lines = written().getAsJsonArray("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).getAsJsonObject().get("id").getAsLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("a server without a feed yet gets an empty one, so the panel knows it can have one")
    void startsEmpty() throws Exception {
        PanelFeed feed = new PanelFeed(dir, 100);
        feed.load();

        assertThat(feed.flush()).isTrue();
        assertThat(written().getAsJsonArray("lines")).isEmpty();
    }

    @Test
    @DisplayName("a broken file is started over rather than stopping chat")
    void brokenFile() throws Exception {
        Files.writeString(dir.resolve(PanelFeed.FILE), "{not json");
        PanelFeed feed = new PanelFeed(dir, 100);
        feed.load();
        feed.add(1, tom, "Tom", "fresh", Component.text("fresh"), false);
        feed.flush();

        assertThat(written().getAsJsonArray("lines")).hasSize(1);
    }
}
