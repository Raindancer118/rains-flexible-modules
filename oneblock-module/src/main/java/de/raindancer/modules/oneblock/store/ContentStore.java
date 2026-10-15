package de.raindancer.modules.oneblock.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.oneblock.OneBlockModule;
import de.raindancer.modules.oneblock.model.Content;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The island server's {@code content.yml}: written from the shipped one the first time, read again on
 * {@code /islandadmin reload}. Whoever asks gets the content as it is now — a reload swaps it whole.
 */
public final class ContentStore {

    private final YamlStore store;
    private final LogChannel log;
    private final AtomicReference<Content> current = new AtomicReference<>();

    public ContentStore(Path file, LogChannel log) {
        this.store = new YamlStore(file);
        this.log = log;
    }

    public Content current() {
        Content content = current.get();
        if (content == null) {
            reload();
            content = current.get();
        }
        return content;
    }

    /** @return the problems found, each a line for the log and for whoever reloaded */
    public synchronized List<String> reload() {
        if (!store.exists()) {
            store.writeTextIfAbsent(shipped());
        }
        YamlConfiguration yaml = store.readIfReadable().orElseGet(() -> {
            log.warn("content.yml could not be read; the islands run on the shipped content until it can.");
            return YamlConfiguration.loadConfiguration(new java.io.StringReader(shipped()));
        });
        ContentReader.Result result = ContentReader.read(yaml);
        current.set(result.content());
        result.problems().forEach(problem -> log.warn("content.yml: {}", problem));
        log.info("Island content: {} upgrade(s), team size {}, a shard every {} block(s).",
                result.content().allUpgrades().size(), result.content().teamSize(), result.content().shardEvery());
        return result.problems();
    }

    static String shipped() {
        try (InputStream in = OneBlockModule.class.getResourceAsStream("content.yml")) {
            if (in == null) {
                throw new IllegalStateException("content.yml is missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("content.yml could not be read from the jar", unreadable);
        }
    }
}
