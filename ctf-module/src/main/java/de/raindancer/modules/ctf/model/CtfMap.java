package de.raindancer.modules.ctf.model;

import de.raindancer.core.social.team.TeamColour;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A map: the schematic it is built from and where everything is on it, every spot relative to the schematic's lowest
 * corner so it can be built anywhere.
 *
 * @param floor   below this height (relative) a player has fallen off the map
 * @param neutral the middle flag for {@link GameType#NEUTRAL}, and where both flags go in sudden death
 * @param control the control point, if the map has one
 */
public record CtfMap(String id, String file, String title, String author, int width, int height, int length,
                     List<Base> bases, Point neutral, Point control, List<Point> powerUps, List<Point> supplies,
                     int floor, Point spectate) {

    public CtfMap {
        bases = bases == null ? List.of() : List.copyOf(bases);
        powerUps = powerUps == null ? List.of() : List.copyOf(powerUps);
        supplies = supplies == null ? List.of() : List.copyOf(supplies);
        title = title == null || title.isBlank() ? id : title;
        author = author == null ? "" : author;
    }

    public static CtfMap fresh(String id, String file, int width, int height, int length) {
        return new CtfMap(id, file, id, "", width, height, length, List.of(), null, null, List.of(), List.of(), -1,
                null);
    }

    public Optional<Base> base(TeamColour colour) {
        return bases.stream().filter(base -> base.colour() == colour).findFirst();
    }

    /** The bases that are complete — a flag and at least one spawn. */
    public List<Base> completeBases() {
        return bases.stream().filter(Base::isComplete).toList();
    }

    /** What is still missing before a round of {@code type} can be played here. Empty when it can. */
    public List<String> missingFor(GameType type) {
        List<String> missing = new ArrayList<>();
        if (completeBases().size() < 2) {
            missing.add("two bases, each with a flag and a spawn");
        }
        if (type == GameType.NEUTRAL && neutral == null) {
            missing.add("a neutral flag in the middle");
        }
        return missing;
    }

    public boolean isPlayable() {
        return missingFor(GameType.CLASSIC).isEmpty();
    }

    public boolean supports(GameType type) {
        return missingFor(type).isEmpty();
    }

    /** The most sides a round on this map can have. */
    public int mostSides() {
        return completeBases().size();
    }

    public CtfMap withBase(Base base) {
        List<Base> next = new ArrayList<>(bases.stream().filter(each -> each.colour() != base.colour()).toList());
        next.add(base);
        return new CtfMap(id, file, title, author, width, height, length, next, neutral, control, powerUps, supplies,
                floor, spectate);
    }

    public CtfMap withoutBase(TeamColour colour) {
        return new CtfMap(id, file, title, author, width, height, length,
                bases.stream().filter(each -> each.colour() != colour).toList(), neutral, control, powerUps, supplies,
                floor, spectate);
    }

    public CtfMap withNeutral(Point at) {
        return new CtfMap(id, file, title, author, width, height, length, bases, at, control, powerUps, supplies,
                floor, spectate);
    }

    public CtfMap withControl(Point at) {
        return new CtfMap(id, file, title, author, width, height, length, bases, neutral, at, powerUps, supplies,
                floor, spectate);
    }

    public CtfMap withPowerUp(Point at) {
        List<Point> more = new ArrayList<>(powerUps);
        more.add(at);
        return new CtfMap(id, file, title, author, width, height, length, bases, neutral, control, more, supplies,
                floor, spectate);
    }

    public CtfMap withoutPowerUps() {
        return new CtfMap(id, file, title, author, width, height, length, bases, neutral, control, List.of(),
                supplies, floor, spectate);
    }

    public CtfMap withSupply(Point at) {
        List<Point> more = new ArrayList<>(supplies);
        more.add(at);
        return new CtfMap(id, file, title, author, width, height, length, bases, neutral, control, powerUps, more,
                floor, spectate);
    }

    public CtfMap withoutSupplies() {
        return new CtfMap(id, file, title, author, width, height, length, bases, neutral, control, powerUps,
                List.of(), floor, spectate);
    }

    public CtfMap withFloor(int y) {
        return new CtfMap(id, file, title, author, width, height, length, bases, neutral, control, powerUps, supplies,
                y, spectate);
    }

    public CtfMap withSpectate(Point at) {
        return new CtfMap(id, file, title, author, width, height, length, bases, neutral, control, powerUps, supplies,
                floor, at);
    }

    public CtfMap withTitle(String newTitle, String newAuthor) {
        return new CtfMap(id, file, newTitle, newAuthor, width, height, length, bases, neutral, control, powerUps,
                supplies, floor, spectate);
    }
}
