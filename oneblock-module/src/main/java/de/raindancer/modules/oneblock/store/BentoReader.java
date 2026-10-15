package de.raindancer.modules.oneblock.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.modules.oneblock.model.BentoIsland;
import de.raindancer.modules.oneblock.model.Spawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * BentoBox's JSON database and AOneBlock's counts, read straight from the files — BentoBox need not run, and must not:
 * it would keep writing the very world being moved out of. Read-only.
 */
public final class BentoReader {

    private static final int MEMBER_RANK = 500;
    private static final Pattern PHASE_FILE = Pattern.compile("^(\\d+)_(.+)\\.yml$");

    /** @param skipped one line per island left behind, with why */
    public record Result(List<BentoIsland> islands, List<String> skipped) {
    }

    /** Where AOneBlock's nether and end phases begin; {@link Long#MAX_VALUE} for one it does not have. */
    public record Phases(long nether, long end) {
    }

    private BentoReader() {
    }

    public static Result read(Path islandFolder, Path countFolder) throws IOException {
        List<BentoIsland> islands = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (!Files.isDirectory(islandFolder)) {
            return new Result(islands, List.of("no BentoBox island folder at " + islandFolder));
        }
        try (Stream<Path> files = Files.list(islandFolder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    String id = json.has("uniqueId") ? json.get("uniqueId").getAsString() : name.replace(".json", "");
                    if (json.has("deleted") && json.get("deleted").getAsBoolean()) {
                        skipped.add(id + ": deleted in BentoBox");
                        continue;
                    }
                    if (!json.has("owner") || json.get("owner").isJsonNull()) {
                        skipped.add(id + ": nobody owns it");
                        continue;
                    }
                    islands.add(island(id, json, count(countFolder.resolve(name))));
                } catch (RuntimeException unreadable) {
                    skipped.add(name + ": unreadable (" + unreadable.getMessage() + ")");
                }
            }
        }
        return new Result(islands, skipped);
    }

    private static BentoIsland island(String id, JsonObject json, long lifetime) {
        UUID owner = UUID.fromString(json.get("owner").getAsString());
        List<UUID> members = new ArrayList<>();
        if (json.has("members")) {
            for (Map.Entry<String, JsonElement> member : json.getAsJsonObject("members").entrySet()) {
                UUID player = UUID.fromString(member.getKey());
                if (!player.equals(owner) && member.getValue().getAsInt() >= MEMBER_RANK) {
                    members.add(player);
                }
            }
        }
        JsonArray center = json.getAsJsonArray("center");
        int protection = Math.max(number(json, "protectionRange"), number(json, "maxEverProtectionRange"));
        Optional<Spawn> spawn = Optional.empty();
        if (json.has("spawnPoint") && json.getAsJsonObject("spawnPoint").has("NORMAL")) {
            JsonArray at = json.getAsJsonObject("spawnPoint").getAsJsonArray("NORMAL");
            spawn = Optional.of(new Spawn(at.get(1).getAsDouble(), at.get(2).getAsDouble(), at.get(3).getAsDouble(),
                    at.get(4).getAsFloat(), at.get(5).getAsFloat()));
        }
        return new BentoIsland(id, owner, members, (int) Math.floor(center.get(1).getAsDouble()),
                (int) Math.floor(center.get(2).getAsDouble()), (int) Math.floor(center.get(3).getAsDouble()),
                protection, number(json, "range"), lifetime, spawn,
                json.has("createdDate") ? json.get("createdDate").getAsLong() : 0);
    }

    private static int number(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsInt() : 0;
    }

    /** AOneBlock's lifetime count, or its phase count for a file from before it kept one; zero without a file. */
    private static long count(Path file) {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            long lifetime = json.has("lifetime") ? json.get("lifetime").getAsLong() : 0;
            long phase = json.has("blockNumber") ? json.get("blockNumber").getAsLong() : 0;
            return Math.max(lifetime, phase);
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    /** From the phase files' names — {@code 7500_the nether.yml} — so a server's own phase list is what counts. */
    public static Phases phases(Path folder) {
        long nether = Long.MAX_VALUE;
        long end = Long.MAX_VALUE;
        if (!Files.isDirectory(folder)) {
            return new Phases(nether, end);
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                Matcher matcher = PHASE_FILE.matcher(file.getFileName().toString().toLowerCase(Locale.ROOT));
                if (!matcher.matches() || matcher.group(2).endsWith("_chests")) {
                    continue;
                }
                long start = Long.parseLong(matcher.group(1));
                String name = matcher.group(2);
                if (name.contains("nether")) {
                    nether = Math.min(nether, start);
                } else if (name.equals("the end") || name.equals("end") || name.equals("the_end")) {
                    end = Math.min(end, start);
                }
            }
        } catch (IOException unreadable) {
            return new Phases(nether, end);
        }
        return new Phases(nether, end);
    }
}
