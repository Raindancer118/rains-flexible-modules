package de.raindancer.modules.chat.store;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * The last public lines, each as the component players were shown, in {@value #FILE} for the Pelican panel's
 * chat page to read. Written atomically, so a reader never sees half a file.
 *
 * <p>Personal data: the same names and lines {@code /chathistory} keeps, and fewer of them.
 */
public final class PanelFeed {

    private static final LogChannel log = Log.of("chat");
    private static final Gson GSON = new Gson();

    public static final String FILE = "panel-feed.json";

    private final Path file;
    private final int capacity;
    private final Deque<JsonObject> lines = new ArrayDeque<>();
    private long lastId;
    private boolean dirty;

    public PanelFeed(Path dataFolder, int capacity) {
        this.file = dataFolder.resolve(FILE);
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            dirty = true;
            return;
        }
        try {
            JsonArray saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("lines");
            for (JsonElement line : saved) {
                JsonObject kept = line.getAsJsonObject();
                lines.addLast(kept);
                lastId = Math.max(lastId, kept.get("id").getAsLong());
            }
            trim();
        } catch (IOException | RuntimeException e) {
            log.warn("{} could not be read, starting it over: {}", FILE, e.getMessage());
            lines.clear();
        }
    }

    /** Called on the chat thread; only remembers, {@link #flush} writes. */
    public synchronized void add(long at, UUID player, String name, String text, Component shown, boolean fromPanel) {
        JsonObject line = new JsonObject();
        line.addProperty("id", ++lastId);
        line.addProperty("at", at);
        line.addProperty("player", player.toString());
        line.addProperty("name", name);
        line.addProperty("text", text);
        line.addProperty("panel", fromPanel);
        line.add("line", GsonComponentSerializer.gson().serializeToTree(shown));
        lines.addLast(line);
        trim();
        dirty = true;
    }

    /** @return whether anything was written */
    public boolean flush() {
        String json;
        synchronized (this) {
            if (!dirty) {
                return false;
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray all = new JsonArray();
            lines.forEach(all::add);
            root.add("lines", all);
            json = GSON.toJson(root);
            dirty = false;
        }
        try {
            Files.createDirectories(file.getParent());
            Path partial = file.resolveSibling(FILE + ".tmp");
            Files.writeString(partial, json);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            synchronized (this) {
                dirty = true;
            }
            log.warn("{} could not be written: {}", FILE, e.getMessage());
            return false;
        }
    }

    private void trim() {
        while (lines.size() > capacity) {
            lines.removeFirst();
        }
    }
}
