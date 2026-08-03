package de.raindancer.modules.claims.model;


import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.store.ClaimRegistry;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns claim names into claims, and claims into names people can tell apart.
 * <p>
 * Names are unique per owner rather than per server, so "home" on its own is not always an answer. The
 * qualified form is {@code owner/name} — no space, so it survives being one command argument, and it
 * reads the way a path does, which is what it is.
 */
public final class ClaimNames {

    /**
     * What a claim may be called: letters, digits, dashes and underscores, three to twenty-four of them.
     *
     * <p>Here rather than in the service that used to own it, because both the rule and the rename screen ask —
     * and two copies of a name rule is one screen accepting what the other refuses.
     */
    private static final java.util.regex.Pattern VALID_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_\\-]{3,24}");

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** Separates the owner from the claim in a qualified name. */
    public static final char SEPARATOR = '/';

    private final ClaimRegistry claims;
    /**
     * How a uuid becomes a name people recognise.
     *
     * <p>A function rather than a cache of its own. Core already knows every player it has seen — see
     * {@code core.ui.choose.PlayerDirectory} — and a second name cache next to it would be a second set
     * of answers that drift apart the first time somebody changes their name.
     */
    private final Function<UUID, String> nameOf;

    public ClaimNames(ClaimRegistry claims, Function<UUID, String> nameOf) {
        this.claims = claims;
        this.nameOf = nameOf;
    }

    /** How a claim should be written when it might be confused with somebody else's. */
    public String qualified(Claim claim) {
        return primaryOwner(claim) + SEPARATOR + claim.name();
    }

    /**
     * How a claim reads in a sentence: {@code Raindancer118's home}.
     * <p>
     * The slash form is for commands, where it has to survive being one argument. Prose wants the
     * possessive, and it wants it even when nobody else shares the name — "escorted out of home" tells a
     * reader nothing about whose doorstep it was.
     * <p>
     * A name already ending in s takes the bare apostrophe, so Linus keeps his one s.
     */
    public String possessive(Claim claim) {
        String owner = primaryOwner(claim);
        String suffix = owner.endsWith("s") || owner.endsWith("S") ? "'" : "'s";
        return owner + suffix + " " + claim.name();
    }

    /**
     * How a claim should be written to this reader.
     * <p>
     * Their own claims, and any name only one claim on the server holds, are shown plainly: qualifying
     * everything would be noise. Anything else is qualified, because otherwise the reader cannot tell
     * which "home" is meant.
     */
    public String display(Claim claim, UUID reader) {
        if (reader != null && claim.isOwner(reader)) {
            return claim.name();
        }
        return claims.allByName(claim.name()).size() > 1 ? qualified(claim) : claim.name();
    }

    /** Always qualified when anybody else shares the name, for lists that mix claims from everywhere. */
    public String listed(Claim claim) {
        return claims.allByName(claim.name()).size() > 1 ? qualified(claim) : claim.name();
    }

    /** Never null and never blank, because these end up in the middle of a sentence. */
    private String nameFor(UUID owner) {
        String name = owner == null ? null : nameOf.apply(owner);
        return name == null || name.isBlank() ? "somebody" : name;
    }

    /**
     * What to call this person.
     *
     * <p>Public because the screens list people as often as they list claims, and every one of them would
     * otherwise reach for the name lookup itself and answer a raw uuid when the server has never seen them.
     */
    public String nameOfOwner(UUID who) {
        return nameFor(who);
    }

    public String primaryOwner(Claim claim) {
        for (UUID owner : claim.owners()) {
            return nameFor(owner);
        }
        return "nobody";
    }

    /** Every owner of the claim, comma separated. */
    public String allOwners(Claim claim) {
        StringBuilder builder = new StringBuilder();
        for (UUID owner : claim.owners()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(nameFor(owner));
        }
        return builder.length() == 0 ? "nobody" : builder.toString();
    }

    /**
     * What a typed name refers to.
     * <p>
     * {@code owner/name} picks that owner's claim outright. A bare name resolves to the only claim that
     * carries it, or — when several do — to the asker's own, since somebody typing "home" almost always
     * means theirs. Anything still ambiguous is reported as such rather than guessed at.
     */
    public Resolution resolve(String input, UUID asker) {
        if (input == null || input.isBlank()) {
            return Resolution.unknown(List.of());
        }
        int separator = input.indexOf(SEPARATOR);
        if (separator > 0 && separator < input.length() - 1) {
            String owner = input.substring(0, separator);
            String name = input.substring(separator + 1);
            return qualifiedMatch(owner, name);
        }

        List<Claim> matches = claims.allByName(input);
        if (matches.isEmpty()) {
            return Resolution.unknown(List.of());
        }
        if (matches.size() == 1) {
            return Resolution.found(matches.get(0));
        }
        if (asker != null) {
            for (Claim candidate : matches) {
                if (candidate.isOwner(asker)) {
                    return Resolution.found(candidate);
                }
            }
        }
        return Resolution.ambiguous(matches);
    }

    private Resolution qualifiedMatch(String owner, String name) {
        List<Claim> matches = new ArrayList<>();
        for (Claim candidate : claims.allByName(name)) {
            if (ownedByName(candidate, owner)) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            return Resolution.unknown(List.of());
        }
        // One owner cannot hold the same name twice, so anything here is a single claim.
        return Resolution.found(matches.get(0));
    }

    private boolean ownedByName(Claim claim, String ownerName) {
        for (UUID owner : claim.owners()) {
            if (nameFor(owner).equalsIgnoreCase(ownerName)) {
                return true;
            }
        }
        return false;
    }

    /** The candidates for a name, written qualified, for an error message. */
    public String describeCandidates(List<Claim> candidates) {
        StringBuilder builder = new StringBuilder();
        for (Claim candidate : candidates) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(qualified(candidate));
        }
        return builder.toString();
    }

    /** Names to offer for tab completion: qualified only where a bare name would be ambiguous. */
    public List<String> suggestions(UUID asker) {
        List<String> suggestions = new ArrayList<>();
        for (Claim claim : claims.all()) {
            suggestions.add(display(claim, asker));
        }
        suggestions.sort(String.CASE_INSENSITIVE_ORDER);
        return suggestions;
    }

    /** Whether the owner may still take this name, i.e. does not already have one like it. */
    public boolean available(String name, UUID owner) {
        return !claims.nameTaken(name, owner);
    }

    /** The outcome of looking a name up. */
    public record Resolution(Optional<Claim> claim, List<Claim> candidates) {

        static Resolution found(Claim claim) {
            return new Resolution(Optional.of(claim), List.of(claim));
        }

        static Resolution ambiguous(List<Claim> candidates) {
            return new Resolution(Optional.empty(), List.copyOf(candidates));
        }

        static Resolution unknown(List<Claim> candidates) {
            return new Resolution(Optional.empty(), List.copyOf(candidates));
        }

        /** Several claims carry the name and none of them is obviously the one meant. */
        public boolean isAmbiguous() {
            return claim.isEmpty() && candidates.size() > 1;
        }
    }

    /** Lowercased for comparisons, mirroring the registry's own key. */
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
