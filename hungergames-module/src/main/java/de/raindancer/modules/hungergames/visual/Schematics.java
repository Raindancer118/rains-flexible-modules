package de.raindancer.modules.hungergames.visual;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Location;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reading and pasting the builds the arena is made of.
 *
 * <p>Six files: the platforms tributes start on, the barrier variant of one, the tube they come up through, two
 * middles and the cornucopia. Every one ships inside the jar and is written out beside the module's own data on
 * first use, so a server can replace one with a build of its own without touching the jar.
 *
 * <h2>Why WorldEdit does the reading</h2>
 * A Sponge {@code .schem} is a palette, a block-state table and a block-entity list, in two format versions
 * that are both still in the wild. Writing a parser for that was considered — RainsCore already has the NBT
 * half — and rejected: a parser that is subtly wrong builds an arena that is subtly wrong, and the place that
 * is discovered is {@code /init}, with forty people already waiting. WorldEdit reads these correctly, the
 * plugin this was ported from already required it, and it is {@code provided}, so the server's own copy is the
 * one that is used.
 *
 * <h2>Why the module never trusts a name</h2>
 * Names reach this class from a config file, an arena definition and a command argument. {@link SchematicName}
 * decides whether one may be used, as a pure function that is actually tested — and the canonical-path check
 * happens here as well, once the path exists, because the two catch different mistakes: the name guard catches
 * a hostile string, and the path check catches a symlink somebody has put in the schematic folder.
 *
 * <h2>Not thread-safe in the way that matters</h2>
 * {@link #paste} edits blocks, so it belongs to the thread that owns them. It does not schedule itself onto
 * that thread, because the caller is building an arena as a sequence — a platform, then a tube under it, then
 * the next — and a method that hopped threads on each call would interleave them. The arena builder is what
 * holds the sequence together, on the region that owns the world.
 */
public final class Schematics {

    /** The folder inside the module's data folder, and the folder inside the jar. Deliberately the same. */
    private static final String FOLDER = "schem";

    /**
     * Where the bundled copies live inside the jar, as an absolute resource path.
     *
     * <h2>Why absolute, and why that is not a detail</h2>
     * It was relative — {@code getResourceAsStream("schem/tube.schem")} — and a relative resource name is
     * resolved against the <em>asking class's own package</em>. This class is in {@code …hungergames.visual},
     * so it looked in {@code de/raindancer/modules/hungergames/visual/schem/} while the files are packaged one
     * level up, beside the module class.
     *
     * <p>Nothing noticed. The jar test confirmed the schematics were present, and they were — at the path
     * nothing was reading. {@code /init} reported "there is no schematic called 'tube.schem' in this module,
     * and none on disk either", guessed a tube depth, and gave up when the middle would not paste: two
     * platforms and no cornucopia, in front of whoever ran it.
     *
     * <p>Absolute, so moving this class between packages cannot move the files it reads.
     */
    private static final String BUNDLED_AT = "/de/raindancer/modules/hungergames/" + FOLDER + "/";

    private final Path schematics;
    private final LogChannel log;

    /**
     * @param dataFolder the module's own data folder — its own when it is a plugin, a corner of the host's
     *                   when it is not. Never {@code getDataFolder()} of some plugin, so a hosted module puts
     *                   its schematics beside its own files rather than in whatever plugin is carrying it
     */
    public Schematics(Path dataFolder, LogChannel log) {
        this.schematics = dataFolder.resolve(FOLDER);
        this.log = log;
    }

    /**
     * The file for a name, extracting the bundled copy if it is not there yet.
     *
     * <p>Empty for a name that is refused, a file that cannot be produced, or a path that escapes the
     * schematic folder once resolved. Every one of those is logged, because a missing schematic means an arena
     * with no cornucopia and the report that reaches a maintainer is "the middle is empty".
     */
    public Optional<Path> resolve(String name) {
        Optional<String> safe = SchematicName.checked(name);
        if (safe.isEmpty()) {
            log.error("'{}' is not a usable schematic name, so nothing was read.", name);
            return Optional.empty();
        }
        Path file = schematics.resolve(safe.get());

        // The second half of the guard, and it catches what the name check cannot: a symlink in the schematic
        // folder pointing somewhere else. The name is blameless and the path still leaves the folder.
        try {
            Path folder = schematics.toRealPath();
            if (Files.exists(file) && !file.toRealPath().startsWith(folder)) {
                log.error("The schematic '{}' resolves outside the schematic folder, so it was not read.",
                        safe.get());
                return Optional.empty();
            }
        } catch (IOException cannotResolve) {
            // The folder does not exist yet, which is the ordinary first-run case. extract() makes it.
        }

        if (!Files.exists(file) && !extract(safe.get(), file)) {
            return Optional.empty();
        }
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /**
     * Writes the bundled copy out beside the module's data.
     *
     * <p>Read from this class's own resources rather than through {@code Plugin.saveResource}, which reads the
     * jar of whichever plugin is hosting the module. That is the same jar when this is a plugin of its own, and
     * the wrong question entirely when it is one feature of a larger plugin.
     */
    private boolean extract(String name, Path to) {
        try (InputStream bundled = bundled(name)) {
            if (bundled == null) {
                log.error("There is no schematic called '{}' in this module, and none on disk either.", name);
                return false;
            }
            Files.createDirectories(schematics);
            Files.copy(bundled, to, StandardCopyOption.REPLACE_EXISTING);
            log.info("Wrote out the bundled schematic '{}'.", name);
            return true;
        } catch (IOException failed) {
            log.error("Could not write out the schematic '{}': {}", name, failed.getMessage());
            return false;
        }
    }

    /**
     * The bundled copy of one schematic, or {@code null} when this module does not ship one by that name.
     *
     * <p>Public and static so a test can ask exactly what production asks — see
     * {@code TheSchematicsAreReachableTest}. A resource path is a string, and the one that was wrong here was
     * wrong by a single package.
     */
    public static InputStream bundled(String name) {
        return Schematics.class.getResourceAsStream(BUNDLED_AT + name);
    }

    /**
     * How tall a schematic is, in blocks.
     *
     * <p>Wanted before anything is pasted: the tube a tribute rises through has to be as deep as the platform
     * is tall, and the underground room has to be tall enough to hold it. Empty rather than a guessed number —
     * a guessed height is a platform embedded in stone or floating over a hole.
     */
    public OptionalInt height(String name) {
        Optional<Clipboard> clipboard = read(name);
        return clipboard.map(board -> OptionalInt.of(board.getDimensions().getY()))
                .orElseGet(OptionalInt::empty);
    }

    /**
     * Pastes a schematic with its own origin at that location.
     *
     * <p>{@code ignoreAirBlocks(false)}, deliberately, and it is the difference between building an arena and
     * decorating whatever was already there. The air in these builds is load-bearing: the platform's schematic
     * carries the empty space a tribute stands in, and the barrier ring carries the gap they cannot walk
     * through. Pasted ignoring air, a second {@code /init} over an old arena leaves the previous one's blocks
     * inside the new one's empty spaces.
     *
     * @return whether it was pasted. False is already logged; a caller building an arena should stop rather
     *         than carry on placing things around something that is not there
     */
    public boolean paste(String name, Location at) {
        if (at == null || at.getWorld() == null) {
            log.error("Nowhere to paste '{}' — the location has no world.", name);
            return false;
        }
        Optional<Clipboard> clipboard = read(name);
        if (clipboard.isEmpty()) {
            return false;
        }
        try (EditSession session =
                     WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(at.getWorld()))) {
            Operation operation = new ClipboardHolder(clipboard.get())
                    .createPaste(session)
                    .to(BlockVector3.at(at.getBlockX(), at.getBlockY(), at.getBlockZ()))
                    .ignoreAirBlocks(false)
                    .build();
            Operations.complete(operation);
            return true;
        } catch (Exception worldEditFailed) {
            // Caught broadly on purpose. WorldEdit throws several checked and unchecked types from here, and
            // the arena builder's only sensible response to any of them is the same: stop, and say which
            // schematic it was. An escaping exception mid-arena leaves a half-built one and a stack trace
            // naming WorldEdit rather than the thing that asked.
            log.error("WorldEdit could not paste '{}': {}", name, worldEditFailed.getMessage());
            return false;
        }
    }

    /** The clipboard for a name, or empty — logged either way. */
    private Optional<Clipboard> read(String name) {
        Optional<Path> file = resolve(name);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file.get().toFile());
        if (format == null) {
            log.error("'{}' is not in a schematic format WorldEdit recognises.", name);
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file.get());
             ClipboardReader reader = format.getReader(in)) {
            return Optional.of(reader.read());
        } catch (IOException unreadable) {
            log.error("Could not read the schematic '{}': {}", name, unreadable.getMessage());
            return Optional.empty();
        }
    }
}
