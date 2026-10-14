package de.raindancer.modules.enchants.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.modules.enchants.rules.ShardBiomes;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes the shard world's purple biomes as a datapack, from the game's own biomes and biome tags — so they always
 * match the game running — and has Paper load it. Runs in the bootstrapper: worldgen data is read before any world.
 */
public final class ShardBiomePack {

    private static final String BIOMES = "data/minecraft/worldgen/biome";
    private static final String TAGS = "data/minecraft/tags/worldgen/biome";

    private ShardBiomePack() {
    }

    public static void register(BootstrapContext context) throws IOException {
        Path pack = context.getDataDirectory().resolve("shard-biomes");
        write(pack, context);
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                event.registrar().discoverPack(pack, "shard-biomes", configure -> configure.autoEnableOnServerStart(true));
            } catch (IOException unreadable) {
                context.getLogger().error("The shard world's biomes could not be loaded; it keeps the game's colours.",
                        unreadable);
            }
        });
    }

    private static void write(Path pack, BootstrapContext context) throws IOException {
        URL plains = org.bukkit.Bukkit.class.getClassLoader().getResource(BIOMES + "/plains.json");
        URL version = org.bukkit.Bukkit.class.getClassLoader().getResource("version.json");
        if (plains == null || version == null) {
            throw new IOException("the game's biomes are not on the classpath");
        }
        int format;
        try (var in = version.openStream()) {
            format = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("pack_version").get("data_major").getAsInt();
        }
        Map<String, JsonObject> biomes = new LinkedHashMap<>();
        Map<String, JsonObject> tags = new LinkedHashMap<>();
        URI jar = URI.create(plains.toString().substring(0, plains.toString().indexOf("!/")));
        FileSystem files;
        boolean ours = false;
        try {
            files = FileSystems.getFileSystem(jar);
        } catch (FileSystemNotFoundException notOpen) {
            files = FileSystems.newFileSystem(jar, Map.of());
            ours = true;
        }
        try {
            read(files.getPath(BIOMES), biomes);
            read(files.getPath(TAGS), tags);
        } finally {
            if (ours) {
                files.close();
            }
        }
        // What this start writes is the whole pack: last start's files go first.
        if (Files.isDirectory(pack)) {
            try (Stream<Path> old = Files.walk(pack)) {
                for (Path each : old.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(each);
                }
            }
        }
        Path ourBiomes = Files.createDirectories(pack.resolve("data/" + ShardBiomes.NAMESPACE + "/worldgen/biome"));
        Set<String> copied = new HashSet<>();
        for (Map.Entry<String, JsonObject> biome : biomes.entrySet()) {
            String vanilla = "minecraft:" + biome.getKey();
            Files.writeString(ourBiomes.resolve(ShardBiomes.copyKey(vanilla).substring(ShardBiomes.NAMESPACE.length() + 1)
                    + ".json"), ShardBiomes.purple(biome.getValue()).toString());
            copied.add(vanilla);
        }
        Path ourTags = Files.createDirectories(pack.resolve(TAGS));
        for (Map.Entry<String, JsonObject> tag : ShardBiomes.tags(tags, copied).entrySet()) {
            Path file = ourTags.resolve(tag.getKey() + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, tag.getValue().toString());
        }
        // All of them under one tag of their own: "is this a shard biome" for commands and checks.
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        copied.stream().sorted().map(ShardBiomes::copyKey).forEach(all::add);
        JsonObject shard = new JsonObject();
        shard.add("values", all);
        Path ownTags = Files.createDirectories(pack.resolve("data/" + ShardBiomes.NAMESPACE + "/tags/worldgen/biome"));
        Files.writeString(ownTags.resolve("shard.json"), shard.toString());
        Files.writeString(pack.resolve("pack.mcmeta"), ShardBiomes.packMeta(format));
        context.getLogger().info("The shard world's purple biomes: {} biome(s), {} tag(s).", copied.size(),
                ShardBiomes.tags(tags, copied).size());
    }

    /** Every JSON file under {@code folder}, by its path below it without ".json" ({@code plains}, {@code has_structure/igloo}). */
    private static void read(Path folder, Map<String, JsonObject> into) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> json = walk.filter(path -> path.toString().endsWith(".json")).toList();
            for (Path file : json) {
                String name = folder.relativize(file).toString().replace('\\', '/');
                into.put(name.substring(0, name.length() - ".json".length()),
                        JsonParser.parseString(Files.readString(file)).getAsJsonObject());
            }
        }
    }
}
