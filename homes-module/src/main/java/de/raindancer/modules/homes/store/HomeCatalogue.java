package de.raindancer.modules.homes.store;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.rules.HomeNameRule;
import org.bukkit.Location;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The module's door to the homes, which are RainsCore's places.
 *
 * <h2>Why there is no store of its own</h2>
 * Because a home is a place with a name, a world and coordinates belonging to one player, and that is
 * what a {@link Poi} is — down to the world being a name so an unloaded world makes a home
 * <em>unreachable</em> rather than lost. The old plugin's own store had written the temp-file-then-move
 * dance, a private writer thread and a corrupt-entry skip, all of which are solved and tested in Core.
 *
 * <p>The payoff is not only that the code is shorter. It means everything that understands places
 * understands homes: a ghast line can fly somebody to one, a menu can list them beside warps, and
 * deleting a world takes its homes with it rather than leaving them pointing at nothing.
 *
 * <h2>The migration</h2>
 * An upgrading server's homes are in a {@code homes.yml}, so {@link #importLegacy} (this module's own
 * predecessor, {@code RainsHomes}) and {@link #importSetHomePlugin} (the third-party {@code SetHome}
 * plugin) each read a different shape of it once and put the result in the place store. RainsHomes
 * always wins when both are present — see {@link #importSetHomePlugin} for why. Nothing is deleted: the
 * old file is renamed aside, which means a server that has to roll back still has it, and a second
 * start does not import twice.
 */
public final class HomeCatalogue {

    /** The kind these are stored under, so they are told apart from warps and ghast stops. */
    public static final String KIND = "home";

    /** Where a home's chosen icon lives on the underlying place. */
    static final String TAG_ICON = "icon";

    /** Where the last-known name of the owner lives, for whoever reads the store by hand. */
    static final String TAG_OWNER_NAME = "owner-name";

    private final PoiStore places;
    /**
     * Writing the places out, and saying whether it worked.
     *
     * <p>A {@code BooleanSupplier} rather than a {@code Runnable}, and that is the whole difference
     * between a safe migration and a lost one — see {@link #importLegacy}.
     */
    private final java.util.function.BooleanSupplier flush;
    private final HomeNameRule names = new HomeNameRule();

    public HomeCatalogue(PoiStore places, java.util.function.BooleanSupplier flush) {
        this.places = places;
        this.flush = flush == null ? () -> true : flush;
    }

    // ------------------------------------------------------------------------ looking

    /** One player's homes, in alphabetical order — what a list and a completion show. */
    public List<Home> of(UUID owner) {
        if (owner == null) {
            return List.of();
        }
        return places.owned(owner, KIND).stream()
                .map(Home::new)
                .sorted(Comparator.comparing(Home::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** One home by name, however it was capitalised. */
    public Optional<Home> find(UUID owner, String name) {
        String wanted = names.normalise(name);
        if (owner == null || wanted == null) {
            return Optional.empty();
        }
        return places.named(owner, KIND, wanted).map(Home::new);
    }

    public int count(UUID owner) {
        return owner == null ? 0 : places.count(owner, KIND);
    }

    /**
     * Whether anybody on the server has a home at all.
     *
     * <p>What {@link #importSetHomePlugin} checks before running, not {@link #count(UUID)} for one
     * player: RainsHomes' own migration is the one that must win when both are on the same server, and
     * the only cheap, reliable sign that it already has is that the store is not empty — a file that
     * happened to exist but held nothing, or one already read on an earlier boot, look the same either
     * way from here.
     */
    public boolean isEmpty() {
        return places.ofKind(KIND).isEmpty();
    }

    public boolean has(UUID owner, String name) {
        return find(owner, name).isPresent();
    }

    // ------------------------------------------------------------------------ changing

    /**
     * Sets a home, replacing one of the same name.
     *
     * <p>Replacing keeps the home's id and its icon: somebody moving their base does not expect to
     * lose the block they chose for it. It also keeps the date it was first made, which is the only
     * reason that field is on disk.
     *
     * @return the home, or empty when the name was not a name or the place was nowhere
     */
    public Optional<Home> set(UUID owner, String ownerName, String name, Location where) {
        String clean = names.normalise(name);
        if (owner == null || clean == null || where == null || where.getWorld() == null) {
            return Optional.empty();
        }
        Optional<Home> existing = find(owner, clean);

        Poi.Builder building = Poi.builder(clean, where.getWorld().getName(),
                        where.getX(), where.getY(), where.getZ())
                .kind(KIND)
                .owner(owner)
                .facing(where.getYaw(), where.getPitch())
                // Not shared: a home is one player's, and the place store's own listings honour that.
                .shared(false);
        if (ownerName != null && !ownerName.isBlank()) {
            building.tag(TAG_OWNER_NAME, ownerName);
        }
        existing.ifPresent(had -> {
            building.id(had.poi().id());
            had.icon().ifPresent(icon -> building.tag(TAG_ICON, icon));
        });

        Poi saved = building.build();
        places.save(saved);
        flush.getAsBoolean();
        return Optional.of(new Home(saved));
    }

    /** Forgets a home. */
    public boolean delete(UUID owner, String name) {
        return find(owner, name).map(home -> {
            boolean gone = places.delete(home.poi().id());
            if (gone) {
                flush.getAsBoolean();
            }
            return gone;
        }).orElse(false);
    }

    /**
     * The same home under another name.
     *
     * @return empty when there was no such home, the new name is not a name, or they already have one
     *         called that — the caller tells them which, so the three are kept apart
     */
    public Optional<Home> rename(UUID owner, String from, String to) {
        String wanted = names.normalise(to);
        if (wanted == null) {
            return Optional.empty();
        }
        Optional<Home> existing = find(owner, from);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        if (!existing.get().name().equals(wanted) && has(owner, wanted)) {
            return Optional.empty();
        }
        Poi renamed = existing.get().poi().renamedTo(wanted);
        places.save(renamed);
        flush.getAsBoolean();
        return Optional.of(new Home(renamed));
    }

    /** The block a home shows as in a menu; null puts it back to being chosen by its world. */
    public boolean setIcon(UUID owner, String name, String iconName) {
        return find(owner, name).map(home -> {
            places.save(home.poi().withTag(TAG_ICON,
                    iconName == null || iconName.isBlank() ? null : iconName));
            flush.getAsBoolean();
            return true;
        }).orElse(false);
    }

    // ------------------------------------------------------------------------ the migration

    /**
     * Brings an upgrading server's homes across, once.
     *
     * <p>Skips any home the player already has under that name, so a second run cannot overwrite
     * something set since. Then renames the old file aside rather than deleting it: a server that has
     * to roll back still has every home, and the presence of the original is what makes this run once.
     *
     * @return how many were brought across
     */
    public int importLegacy(Path homesFile, LogChannel log) {
        return importEntries(LegacyHomesFile.read(homesFile), homesFile, log);
    }

    /**
     * Brings across a server's homes from the third-party {@code SetHome} plugin, once — but only onto
     * a server that has no homes from RainsHomes' own migration already.
     *
     * <h2>Why RainsHomes wins outright rather than the two merging</h2>
     * A server only has both files when it changed homes plugins twice: SetHome, then RainsHomes, and
     * whatever is in RainsHomes' own file is what the owner has actually been playing with since —
     * newer, and the one they would call correct if the two disagree about where {@code base} is.
     * Merging by per-name skip, the way two runs of the <em>same</em> source do, would let whichever
     * file happened to be read first win a coin flip the owner never asked for. Deferring to RainsHomes
     * whenever it has anything at all removes the coin flip: SetHome's export is left exactly where it
     * was, untouched and not set aside, so it is still there to bring across by hand if that ever turns
     * out to be the wrong call.
     *
     * <p>Otherwise the same rules as {@link #importLegacy}: a home the owner already has under that
     * name is left alone rather than overwritten, and the file is set aside rather than deleted so this
     * cannot run twice and a rollback still has it.
     *
     * @return how many were brought across
     */
    public int importSetHomePlugin(Path homesFile, LogChannel log) {
        if (!isEmpty()) {
            if (log != null) {
                log.info("Not importing {} — RainsHomes already has homes of its own, and those are "
                                + "the ones kept. The file was left where it is.",
                        homesFile == null ? SetHomePluginFile.FILE_NAME : homesFile.getFileName());
            }
            return 0;
        }
        return importEntries(SetHomePluginFile.read(homesFile), homesFile, log);
    }

    private int importEntries(List<LegacyHomesFile.Entry> waiting, Path homesFile, LogChannel log) {
        if (waiting.isEmpty()) {
            return 0;
        }
        int brought = 0;
        for (LegacyHomesFile.Entry entry : waiting) {
            if (has(entry.owner(), entry.name())) {
                // Either this has run before, or two keys in the old file normalise onto one name —
                // "Home" and "home", which the old plugin's own loader also collapsed. Said out loud
                // rather than skipped quietly: the second one is about to become unreachable, and the
                // owner is the only person who can decide which of the two they wanted.
                if (log != null) {
                    log.warn("{} already has a home called {} — the one at {} {} was left in {} and "
                                    + "not brought across.",
                            entry.ownerName().isBlank() ? entry.owner() : entry.ownerName(),
                            entry.name(), entry.world(),
                            Math.round(entry.x()) + ", " + Math.round(entry.y()) + ", "
                                    + Math.round(entry.z()),
                            homesFile.getFileName());
                }
                continue;
            }
            Poi.Builder building = Poi.builder(entry.name(), entry.world(),
                            entry.x(), entry.y(), entry.z())
                    .kind(KIND)
                    .owner(entry.owner())
                    .facing(entry.yaw(), entry.pitch())
                    .shared(false);
            if (!entry.icon().isBlank()) {
                building.tag(TAG_ICON, entry.icon());
            }
            if (!entry.ownerName().isBlank()) {
                building.tag(TAG_OWNER_NAME, entry.ownerName());
            }
            places.save(building.build());
            brought++;
        }
        flush.getAsBoolean();

        if (brought > 0 && log != null) {
            log.info("Brought {} home(s) across from {}.", brought, homesFile.getFileName());
        }
        setAside(homesFile, log);
        return brought;
    }

    /**
     * Renames the old file rather than deleting it.
     *
     * <p>Two reasons, and the second is the one that matters: a server that has to go back to the old
     * plugin still has every home, and a file that is still called {@code homes.yml} would be imported
     * again on the next start — which after somebody had deleted a home would quietly bring it back.
     */
    private void setAside(Path homesFile, LogChannel log) {
        Path aside = homesFile.resolveSibling(homesFile.getFileName() + ".imported");
        try {
            Files.move(homesFile, aside, StandardCopyOption.REPLACE_EXISTING);
            if (log != null) {
                log.info("{} is now {} — kept, not deleted, in case this has to be undone.",
                        homesFile.getFileName(), aside.getFileName());
            }
        } catch (Exception couldNotMove) {
            // Left where it is. Worth saying loudly: it will be read again on the next start, and
            // anything deleted in between would come back.
            if (log != null) {
                log.warn("Could not set {} aside ({}). Homes were imported, but that file will be "
                                + "read again on the next start — move it by hand.",
                        homesFile, couldNotMove.toString());
            }
        }
    }
}
