package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.identity.NameBadges;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.EconomyMode;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Which money a player's island plays with, as a badge in front of their name in the tablist — on every server of the
 * network, so it is seen wherever they are. The island server knows from its book; the others refresh what the shared
 * table says for whoever is online, now and then, and draw from that. Never a database read while drawing.
 */
public final class ModeBadges implements IOneBlockService {

    static final String OWNER = "rainsoneblock";

    private final Messages messages;
    private final Map<UUID, EconomyMode> known = new ConcurrentHashMap<>();
    /** On the island server: straight from the book, which is always current. */
    private final Function<UUID, Optional<EconomyMode>> direct;

    public ModeBadges(Messages messages, Function<UUID, Optional<EconomyMode>> direct) {
        this.messages = messages;
        this.direct = direct;
    }

    /** Nothing configured: a badge is drawn wherever the tablist draws names. */
    @Override
    public void settings(OneBlockSettings settings) {
    }

    public void register() {
        NameBadges.registerPlaced(OWNER, (player, place) -> place == NameBadges.Place.TABLIST
                ? badgeOf(player) : Optional.empty());
    }

    public void unregister() {
        NameBadges.unregister(OWNER);
    }

    Optional<Component> badgeOf(UUID player) {
        Optional<EconomyMode> mode = direct != null ? direct.apply(player) : Optional.ofNullable(known.get(player));
        return mode.filter(EconomyMode::decided)
                .map(decided -> messages.get(decided == EconomyMode.SMP ? "oneblock.tab.smp" : "oneblock.tab.pure"));
    }

    /**
     * Off the server's threads: reads what the islands of these players play with. A read that fails keeps what was
     * known — a badge that is a few seconds old beats one that flickers away whenever the database is slow.
     */
    public void refresh(Collection<UUID> online, Supplier<Optional<Map<UUID, String>>> read) {
        if (direct != null) {
            return;
        }
        Optional<Map<UUID, String>> modes = read.get();
        if (modes.isEmpty()) {
            return;
        }
        known.keySet().retainAll(online);
        for (UUID player : online) {
            Optional<EconomyMode> mode = Optional.ofNullable(modes.get().get(player)).flatMap(EconomyMode::fromWire);
            mode.ifPresentOrElse(found -> known.put(player, found), () -> known.remove(player));
        }
    }
}
