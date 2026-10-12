package de.raindancer.modules.ranks.service;

import de.raindancer.core.ui.identity.NameBadges;
import de.raindancer.core.ui.text.Text;
import de.raindancer.modules.ranks.RanksSettings;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import de.raindancer.modules.ranks.model.Rank;
import de.raindancer.modules.ranks.rules.BadgeRule;
import de.raindancer.modules.ranks.store.HolderBook;
import de.raindancer.modules.ranks.store.RankCatalogue;
import net.kyori.adventure.text.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Giving and taking ranks, what a player wears, and the badge Core draws in front of their name. */
public final class RankService implements IRanksService {

    public enum Outcome { DONE, ALREADY, NOT_HELD, NOT_ALLOWED, NOT_SAVED, INVALID }

    private final RankCatalogue catalogue;
    private final HolderBook book;
    private final BadgeRule rule = new BadgeRule();
    /** Parsed tags by their markup, so a chat line or a tablist pass parses nothing. */
    private final Map<String, Component> parsed = new ConcurrentHashMap<>();
    private volatile RanksSettings settings;

    public RankService(RankCatalogue catalogue, HolderBook book, RanksSettings settings) {
        this.catalogue = catalogue;
        this.book = book;
        settings(settings);
    }

    @Override
    public void settings(RanksSettings fresh) {
        this.settings = fresh;
    }

    public RanksSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------------------- reading

    /** What Core draws in front of a name; safe from any thread. */
    public Optional<Component> badge(UUID player, NameBadges.Place where) {
        Optional<Place> place = Place.of(where);
        if (place.isEmpty()) {
            return Optional.empty();
        }
        return shown(player, place.get()).map(rank -> parsed.computeIfAbsent(rank.tag(),
                tag -> Text.styleOnly().deserialize(tag)));
    }

    public Optional<Rank> shown(UUID player, Place place) {
        Optional<Holder> holder = book.find(player);
        return holder.isEmpty() ? Optional.empty()
                : rule.shown(holder.get(), catalogue.byId(), settings, place);
    }

    /** The rank they wear wherever it shows — what /rank and the menu call "your rank". */
    public Optional<Rank> worn(UUID player) {
        return book.find(player).flatMap(holder -> rule.worn(holder, catalogue.byId(), settings.playersChoose()));
    }

    /** Their ranks that still exist in ranks.yml, most senior first. */
    public List<Rank> held(UUID player) {
        Map<String, Rank> known = catalogue.byId();
        return book.of(player).ranks().stream().map(known::get).filter(rank -> rank != null)
                .sorted(Comparator.comparingInt(Rank::weight).reversed().thenComparing(Rank::id)).toList();
    }

    public Holder holder(UUID player) {
        return book.of(player);
    }

    /** The tablist section somebody belongs in — the team — or empty. Safe from any thread. */
    public Optional<String> section(UUID player) {
        RanksSettings now = settings;
        if (!now.teamSection() || player == null) {
            return Optional.empty();
        }
        Map<String, Rank> known = catalogue.byId();
        boolean team = book.find(player).map(holder -> holder.ranks().stream()
                .map(known::get).anyMatch(rank -> rank != null && rank.team())).orElse(false);
        String name = now.teamName() == null || now.teamName().isBlank() ? "Team" : now.teamName().strip();
        return team ? Optional.of(name) : Optional.empty();
    }

    public int sortWeight(UUID player) {
        return book.find(player).map(holder -> rule.sortWeight(holder, catalogue.byId(), settings)).orElse(0);
    }

    public List<Rank> ranks() {
        return catalogue.all();
    }

    public Optional<Rank> rank(String id) {
        return catalogue.find(id);
    }

    /** Everybody holding this rank. */
    public List<UUID> holdersOf(Rank rank) {
        return book.all().values().stream().filter(holder -> holder.ranks().contains(rank.id()))
                .map(Holder::player).toList();
    }

    public boolean readable() {
        return book.readable();
    }

    // ------------------------------------------------------------------------------- staff

    public Outcome give(UUID player, Rank rank) {
        if (book.of(player).ranks().contains(rank.id())) {
            return Outcome.ALREADY;
        }
        return book.change(player, holder -> {
            Set<String> more = new LinkedHashSet<>(holder.ranks());
            more.add(rank.id());
            return holder.withRanks(more);
        }) ? Outcome.DONE : Outcome.NOT_SAVED;
    }

    public Outcome take(UUID player, Rank rank) {
        if (!book.of(player).ranks().contains(rank.id())) {
            return Outcome.NOT_HELD;
        }
        return book.change(player, holder -> {
            Set<String> fewer = new LinkedHashSet<>(holder.ranks());
            fewer.remove(rank.id());
            Holder changed = holder.withRanks(fewer);
            return changed.worn().equals(rank.id()) ? changed.wearing("") : changed;
        }) ? Outcome.DONE : Outcome.NOT_SAVED;
    }

    // ------------------------------------------------------------------------------- the player's own

    /**
     * Which of their ranks they show: a rank id they hold, {@link Holder#HIDDEN} for none, or empty for the most
     * senior.
     */
    public Outcome wear(UUID player, String id) {
        if (!settings.playersChoose()) {
            return Outcome.NOT_ALLOWED;
        }
        String wanted = id == null ? "" : id;
        if (!wanted.isEmpty() && !wanted.equals(Holder.HIDDEN) && !book.of(player).ranks().contains(wanted)) {
            return Outcome.NOT_HELD;
        }
        if (book.of(player).worn().equals(wanted)) {
            return Outcome.ALREADY;
        }
        return book.change(player, holder -> holder.wearing(wanted)) ? Outcome.DONE : Outcome.NOT_SAVED;
    }

    /** Switches their own rank in one place. */
    public Outcome show(UUID player, Place place, boolean on) {
        if (!settings.playersChoose()) {
            return Outcome.NOT_ALLOWED;
        }
        if (book.of(player).shows(place) == on) {
            return Outcome.ALREADY;
        }
        return book.change(player, holder -> holder.showing(place, on)) ? Outcome.DONE : Outcome.NOT_SAVED;
    }

    // ------------------------------------------------------------------------------- making ranks in game

    /**
     * A new rank. {@code tag} is what shows — plain text gets square brackets if it has none, markup is kept as
     * written. Saved to custom-ranks.yml.
     */
    public Outcome create(String id, String tag, int weight) {
        if (!RankCatalogue.isId(id) || weight < 1 || weight > 99) {
            return Outcome.INVALID;
        }
        if (catalogue.find(id).isPresent()) {
            return Outcome.ALREADY;
        }
        String shown = bracketed(tag);
        if (RankCatalogue.tagProblem(shown).isPresent()) {
            return Outcome.INVALID;
        }
        return saved(catalogue.define(new Rank(id, id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1),
                shown, weight, List.of())));
    }

    /** What the rank shows, as typed — this replaces its style too. */
    public Outcome retag(String id, String tag) {
        String shown = bracketed(tag);
        if (RankCatalogue.tagProblem(shown).isPresent()) {
            return Outcome.INVALID;
        }
        return change(id, rank -> rank.withTag(shown));
    }

    /** Paints the rank's tag — same letters, a colour, a gradient, bold. NONE leaves it plain white. */
    public Outcome restyle(String id, de.raindancer.core.ui.text.NameStyle style) {
        return change(id, rank -> {
            String letters = Text.plain(Text.styleOnly().deserialize(rank.tag()));
            Component painted = style == null || style.isEmpty() ? Component.text(letters)
                    : de.raindancer.core.ui.text.Gradients.styled(letters, style);
            return rank.withTag(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(painted));
        });
    }

    public Outcome rename(String id, String title) {
        if (title == null || title.isBlank() || title.length() > 32) {
            return Outcome.INVALID;
        }
        return change(id, rank -> rank.withTitle(title.strip()));
    }

    public Outcome reweigh(String id, int weight) {
        if (weight < 1 || weight > 99) {
            return Outcome.INVALID;
        }
        return change(id, rank -> rank.withWeight(weight));
    }

    /** Whether holding the rank puts somebody in the server's team. */
    public Outcome team(String id, boolean onTeam) {
        return change(id, rank -> rank.withTeam(onTeam));
    }

    /** Deletes the rank everywhere; whoever held it simply shows nothing for it. */
    public Outcome delete(String id) {
        if (catalogue.find(id).isEmpty()) {
            return Outcome.NOT_HELD;
        }
        Outcome outcome = saved(catalogue.remove(id));
        parsed.clear();
        return outcome;
    }

    private Outcome change(String id, java.util.function.UnaryOperator<Rank> how) {
        Optional<Rank> rank = catalogue.find(id);
        if (rank.isEmpty()) {
            return Outcome.NOT_HELD;
        }
        Rank changed = how.apply(rank.get());
        if (RankCatalogue.tagProblem(changed.tag()).isPresent()) {
            return Outcome.INVALID;
        }
        return saved(catalogue.define(changed));
    }

    private Outcome saved(boolean saved) {
        return saved ? Outcome.DONE : Outcome.NOT_SAVED;
    }

    private static String bracketed(String typed) {
        String tag = typed == null ? "" : typed.strip();
        if (tag.isEmpty() || tag.contains("<") || tag.startsWith("[")) {
            return tag;
        }
        return "[" + tag + "]";
    }

    /** Reads ranks.yml again. @return how many ranks */
    public int reload() {
        int count = catalogue.reload();
        parsed.clear();
        return count;
    }

    public List<String> problems() {
        return catalogue.problems();
    }
}
