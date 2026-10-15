package de.raindancer.modules.oneblock.service;

import de.raindancer.core.data.nbt.Nbt;
import de.raindancer.core.data.nbt.Tag;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.BentoIsland;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.rules.MigrationRule;
import de.raindancer.modules.oneblock.store.BentoReader;
import de.raindancer.modules.oneblock.store.IslandBook;
import de.raindancer.modules.oneblock.store.PlayerSpot;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Moves BentoBox's islands into this module's, once: {@code /islandadmin migrate} says what it would do,
 * {@code /islandadmin migrate confirm} does it. Each island's region files are copied as they are into its own world,
 * so the block, the builds and the chests keep their coordinates; its breaks pay their shards and a dimension AOneBlock
 * had reached comes unlocked. Where a player logged out on an island is kept, so they come back to that spot.
 *
 * <p>Nothing of BentoBox's is changed or deleted — its files stay as the way back. An island already moved is skipped,
 * so running it twice is safe. BentoBox must not be running: it would keep writing the world being copied.
 */
public final class MigrationService implements IOneBlockService {

    private static final String SOURCE_WORLD = "oneblock_world";
    private static final String[] WORLD_PARTS = {"region", "entities", "poi"};

    private final Plugin plugin;
    private final Server server;
    private final LogChannel log;
    private final Messages messages;
    private final IslandServer islands;
    private final YamlStore done;
    private final MigrationRule rule = new MigrationRule();
    private final AtomicBoolean running = new AtomicBoolean();

    public MigrationService(Plugin plugin, Server server, LogChannel log, Messages messages, IslandServer islands,
                            Path dataFolder) {
        this.plugin = plugin;
        this.server = server;
        this.log = log;
        this.messages = messages;
        this.islands = islands;
        this.done = new YamlStore(dataFolder.resolve("migrated.yml"));
    }

    /** Nothing configured: the BentoBox files are where BentoBox kept them. */
    @Override
    public void settings(OneBlockSettings settings) {
    }

    private Path bentoFolder() {
        return server.getPluginsFolder().toPath().resolve("BentoBox");
    }

    /** Plans, and with {@code confirm} moves. Answers the sender as it goes. */
    public void run(CommandSender sender, boolean confirm) {
        if (server.getPluginManager().isPluginEnabled("BentoBox")) {
            messages.send(sender, "oneblock.migrate.bentobox-running");
            return;
        }
        if (server.getWorld(SOURCE_WORLD) != null) {
            messages.send(sender, "oneblock.migrate.world-loaded", "world", SOURCE_WORLD);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            messages.send(sender, "oneblock.migrate.busy");
            return;
        }
        Path source = islands.worlds().folderOf(SOURCE_WORLD).toPath();
        Path players = server.getWorlds().getFirst().getWorldFolder().toPath();
        Path levelRoot = levelRoot(players);
        Map<String, String> already = migrated();
        Scheduling.async(plugin, () -> {
            try {
                BentoReader.Result read = BentoReader.read(bentoFolder().resolve("database/Island"),
                        bentoFolder().resolve("database/OneBlockIslands"));
                BentoReader.Phases phases = BentoReader.phases(bentoFolder().resolve("addons/AOneBlock/phases"));
                Scheduling.global(plugin, () -> plan(sender, confirm, read, phases, source, levelRoot, already));
            } catch (IOException | RuntimeException failed) {
                running.set(false);
                log.warn("Reading BentoBox's islands failed: {}", failed.toString());
                Scheduling.global(plugin, () -> messages.send(sender, "oneblock.migrate.failed", "why", failed.toString()));
            }
        });
    }

    private record Planned(BentoIsland bento, Island island) {
    }

    private void plan(CommandSender sender, boolean confirm, BentoReader.Result read, BentoReader.Phases phases,
                      Path source, Path levelRoot, Map<String, String> already) {
        MigrationRule.Thresholds at = new MigrationRule.Thresholds(phases.nether(), phases.end());
        List<Planned> planned = new ArrayList<>();
        List<String> skipped = new ArrayList<>(read.skipped());
        for (BentoIsland bento : read.islands()) {
            if (already.containsKey(bento.bentoId())) {
                skipped.add(bento.bentoId() + ": moved already");
                continue;
            }
            Optional<UUID> taken = Stream.concat(Stream.of(bento.owner()), bento.members().stream())
                    .filter(player -> islands.book().ofPlayer(player).isPresent()).findFirst();
            if (taken.isPresent()) {
                skipped.add(bento.bentoId() + ": " + nameOf(taken.get()) + " is on an island here already");
                continue;
            }
            Island island = rule.island(islands.currentContent(), bento, nameOf(bento.owner()), at, UUID.randomUUID());
            planned.add(new Planned(bento, island));
        }
        for (Planned one : planned) {
            Island island = one.island();
            messages.send(sender, "oneblock.migrate.island", "player", island.ownerName(),
                    "team", String.valueOf(island.everyone().size()), "blocks", String.format("%,d", one.bento().lifetime()),
                    "shards", String.format("%,d", island.shards()),
                    "dimensions", String.join(", ", island.unlockedDimensions().stream().map(IslandDimension::id).toList()),
                    "spot", one.bento().x() + " " + one.bento().y() + " " + one.bento().z(),
                    "regions", String.valueOf(rule.regions(one.bento()).size()));
        }
        skipped.forEach(line -> messages.send(sender, "oneblock.migrate.skipped", "why", line));
        if (!confirm || planned.isEmpty()) {
            running.set(false);
            messages.send(sender, planned.isEmpty() ? "oneblock.migrate.nothing" : "oneblock.migrate.dry-run",
                    "count", String.valueOf(planned.size()));
            return;
        }
        messages.send(sender, "oneblock.migrate.copying", "count", String.valueOf(planned.size()));
        Scheduling.async(plugin, () -> copy(sender, planned, source, levelRoot));
    }

    private void copy(CommandSender sender, List<Planned> planned, Path source, Path levelRoot) {
        List<Planned> copied = new ArrayList<>();
        for (Planned one : planned) {
            Path target = islands.worlds().folderOf(one.island().worldName(IslandDimension.OVERWORLD)).toPath();
            try {
                if (Files.exists(target)) {
                    throw new IOException(target + " exists already");
                }
                for (String part : WORLD_PARTS) {
                    Path into = target.resolve(part);
                    Files.createDirectories(into);
                    for (String file : rule.regions(one.bento())) {
                        Path from = source.resolve(part).resolve(file);
                        if (Files.isRegularFile(from)) {
                            Files.copy(from, into.resolve(file), StandardCopyOption.COPY_ATTRIBUTES);
                        }
                    }
                }
                copied.add(one);
            } catch (IOException failed) {
                log.warn("Could not copy {}'s island: {}", one.island().ownerName(), failed.toString());
                Scheduling.global(plugin, () -> messages.send(sender, "oneblock.migrate.copy-failed",
                        "player", one.island().ownerName(), "why", failed.toString()));
            }
        }
        Map<UUID, PlayerSpot> positions = positions(levelRoot, copied);
        Scheduling.global(plugin, () -> finish(sender, copied, positions));
    }

    private void finish(CommandSender sender, List<Planned> copied, Map<UUID, PlayerSpot> positions) {
        Map<String, String> moved = new LinkedHashMap<>();
        int added = 0;
        for (Planned one : copied) {
            IslandBook.Changed result = islands.book().add(one.island());
            if (result.done()) {
                added++;
                moved.put(one.bento().bentoId(), one.island().id().toString());
                log.info("Moved {}'s island over from BentoBox ({} blocks, {} shards).", one.island().ownerName(),
                        one.bento().lifetime(), one.island().shards());
            } else {
                messages.send(sender, "oneblock.migrate.copy-failed", "player", one.island().ownerName(),
                        "why", result.outcome().name());
            }
        }
        record(moved);
        long now = System.currentTimeMillis();
        int done = added;
        Scheduling.async(plugin, () -> {
            islands.book().flush();
            positions.forEach((player, spot) -> islands.storage().savePosition(player, spot, now));
            running.set(false);
            Scheduling.global(plugin, () -> messages.send(sender, "oneblock.migrate.done",
                    "count", String.valueOf(done), "positions", String.valueOf(positions.size())));
        });
    }

    /** Where each player logged out, when that was on one of the islands just moved. Off the server thread. */
    private Map<UUID, PlayerSpot> positions(Path levelRoot, List<Planned> moved) {
        Map<UUID, PlayerSpot> found = new LinkedHashMap<>();
        Path folder = levelRoot.resolve("players").resolve("data");
        if (!Files.isDirectory(folder)) {
            folder = levelRoot.resolve("playerdata");
        }
        if (!Files.isDirectory(folder)) {
            return found;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".dat")).toList()) {
                try {
                    UUID player = UUID.fromString(file.getFileName().toString().replace(".dat", ""));
                    Tag.Compound data = Nbt.read(file);
                    if (!(data.values().get("Dimension") instanceof Tag.Str dimension)
                            || !dimension.value().endsWith(":" + SOURCE_WORLD)
                            || !(data.values().get("Pos") instanceof Tag.List_ pos) || pos.items().size() < 3) {
                        continue;
                    }
                    double x = number(pos.items().get(0));
                    double y = number(pos.items().get(1));
                    double z = number(pos.items().get(2));
                    float yaw = 0;
                    float pitch = 0;
                    if (data.values().get("Rotation") instanceof Tag.List_ rotation && rotation.items().size() >= 2) {
                        yaw = (float) number(rotation.items().get(0));
                        pitch = (float) number(rotation.items().get(1));
                    }
                    for (Planned one : moved) {
                        if (rule.covers(one.bento(), x, z)) {
                            found.put(player, new PlayerSpot(one.island().worldName(IslandDimension.OVERWORLD),
                                    x, y, z, yaw, pitch));
                            break;
                        }
                    }
                } catch (IOException | RuntimeException unreadable) {
                    log.warn("Could not read where {} logged out: {}", file.getFileName(), unreadable.toString());
                }
            }
        } catch (IOException unreadable) {
            log.warn("Could not list the player files: {}", unreadable.toString());
        }
        return found;
    }

    private static double number(Tag tag) {
        return switch (tag) {
            case Tag.Double value -> value.value();
            case Tag.Float value -> value.value();
            case Tag.Int value -> value.value();
            default -> 0;
        };
    }

    /** The level's own folder, above {@code dimensions/minecraft/overworld} since 26.1. */
    private static Path levelRoot(Path mainDimension) {
        Path at = mainDimension;
        for (int up = 0; up < 3 && at.getParent() != null; up++) {
            if (Files.exists(at.resolve("level.dat"))) {
                return at;
            }
            at = at.getParent();
        }
        return Files.exists(at.resolve("level.dat")) ? at : mainDimension;
    }

    private String nameOf(UUID player) {
        String name = server.getOfflinePlayer(player).getName();
        return name == null ? player.toString().substring(0, 8) : name;
    }

    private Map<String, String> migrated() {
        Map<String, String> known = new LinkedHashMap<>();
        YamlConfiguration yaml = done.readIfReadable().orElseGet(YamlConfiguration::new);
        for (String key : yaml.getKeys(false)) {
            known.put(key, yaml.getString(key, ""));
        }
        return known;
    }

    private void record(Map<String, String> moved) {
        if (moved.isEmpty()) {
            return;
        }
        if (!done.update(yaml -> moved.forEach(yaml::set))) {
            log.warn("Could not note which BentoBox islands were moved: {}", moved.keySet());
        }
    }
}
