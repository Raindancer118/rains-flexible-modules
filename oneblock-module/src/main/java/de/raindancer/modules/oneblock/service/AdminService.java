package de.raindancer.modules.oneblock.service;

import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.store.IslandBook;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Staff's say over islands: up or down for a while or for good, auto-unload on or off, shards given or taken. Every
 * change takes effect at once ({@code act} runs the lifecycle for that island) and survives a restart.
 */
public final class AdminService implements IOneBlockService {

    private final IslandBook book;
    private final LongSupplier clock;
    private final Consumer<Island> act;

    public AdminService(IslandBook book, LongSupplier clock, Consumer<Island> act) {
        this.book = book;
        this.clock = clock;
        this.act = act;
    }

    /** Nothing configured: admins decide each island by hand. */
    @Override
    public void settings(OneBlockSettings settings) {
    }

    /** @param length empty for good */
    public Reply hold(UUID island, Hold.Kind kind, Optional<Duration> length, String by) {
        long until = length.map(duration -> saturatedAdd(clock.getAsLong(), duration.toMillis())).orElse(Hold.FOREVER);
        IslandBook.Changed changed = book.change(island, it -> Optional.of(it.withHold(
                Optional.of(new Hold(kind, until, by)))));
        if (!changed.done()) {
            return Reply.refused("oneblock.admin.no-such-island");
        }
        changed.island().ifPresent(act);
        String name = changed.island().map(Island::ownerName).orElse("?");
        String key = switch (kind) {
            case UP -> length.isEmpty() ? "oneblock.admin.held-up-forever" : "oneblock.admin.held-up";
            case DOWN -> length.isEmpty() ? "oneblock.admin.held-down-forever" : "oneblock.admin.held-down";
        };
        return Reply.ok(key, "player", name, "time", length.map(AdminService::describe).orElse(""));
    }

    public Reply release(UUID island) {
        IslandBook.Changed changed = book.change(island, it -> it.hold().isEmpty() ? Optional.empty()
                : Optional.of(it.withHold(Optional.empty())));
        if (changed.outcome() == IslandBook.Outcome.NOT_FOUND) {
            return Reply.refused("oneblock.admin.no-such-island");
        }
        if (!changed.done()) {
            return Reply.refused("oneblock.admin.not-held", "player", changed.island().map(Island::ownerName).orElse("?"));
        }
        changed.island().ifPresent(act);
        return Reply.ok("oneblock.admin.released", "player", changed.island().map(Island::ownerName).orElse("?"));
    }

    public Reply autoUnload(UUID island, boolean on) {
        IslandBook.Changed changed = book.change(island, it -> Optional.of(it.withAutoUnload(on)));
        if (!changed.done()) {
            return Reply.refused("oneblock.admin.no-such-island");
        }
        changed.island().ifPresent(act);
        return Reply.ok(on ? "oneblock.admin.auto-unload-on" : "oneblock.admin.auto-unload-off",
                "player", changed.island().map(Island::ownerName).orElse("?"));
    }

    public Reply shards(UUID island, long amount) {
        IslandBook.Changed changed = book.change(island, it -> Optional.of(it.withShardsAdded(amount)));
        if (!changed.done()) {
            return Reply.refused("oneblock.admin.no-such-island");
        }
        return Reply.ok("oneblock.admin.shards", "player", changed.island().map(Island::ownerName).orElse("?"),
                "shards", String.valueOf(changed.island().map(Island::shards).orElse(0L)));
    }

    /**
     * The island an admin means: by a member's name or uuid, or by the start of its id (as the admin pages and world
     * names show it).
     */
    public Optional<Island> find(String typed, java.util.function.Function<String, Optional<UUID>> playerByName) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String wanted = typed.strip().toLowerCase(Locale.ROOT);
        try {
            UUID asId = UUID.fromString(wanted);
            Optional<Island> byId = book.get(asId);
            return byId.isPresent() ? byId : book.ofPlayer(asId);
        } catch (IllegalArgumentException notAUuid) {
            // A name or an id's start.
        }
        Optional<Island> byPlayer = playerByName.apply(typed.strip()).flatMap(book::ofPlayer);
        if (byPlayer.isPresent()) {
            return byPlayer;
        }
        String compact = wanted.replace("-", "").replace("island_", "");
        if (compact.length() < 6) {
            return Optional.empty();
        }
        return book.all().stream().filter(island -> island.idPart().startsWith(compact)
                || island.id().toString().replace("-", "").startsWith(compact)).findFirst();
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Hold.FOREVER : Math.min(sum, Hold.FOREVER - 1);
    }

    static String describe(Duration duration) {
        return de.raindancer.core.platform.util.Times.describe(duration);
    }
}
