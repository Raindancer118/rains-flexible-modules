package de.raindancer.modules.pack.rules;

import java.util.Locale;
import java.util.Optional;

/**
 * Reading a pack's hash out of the {@code sha1.txt} its host publishes.
 *
 * <h2>Why the hash is looked up rather than written down</h2>
 * A client caches a resource pack by its sha1, so the server has to send the right one — and the hash
 * changes every time the pack is updated. Written into a config file, that means every server wearing
 * the pack has to be edited on the day it changes, and the one nobody edits carries on sending
 * yesterday's hash: the client then either re-downloads on every join or refuses the pack outright,
 * with nothing anywhere saying why.
 *
 * <p>The host already publishes the answer next to the file. Reading it is one fewer thing to keep in
 * step, and there is no second copy to be wrong.
 *
 * <h2>What this rule is, and is not</h2>
 * It is the parsing, which is arithmetic on a few lines of text and is tested. It is <em>not</em> the
 * fetching — that needs a network and belongs in a service, off the server's threads.
 *
 * <p>The format is {@code sha1sum}'s own: one {@code <hash>  <file>} per line, several files per file.
 * So the line has to be picked by name; taking the first would give a server the datapack's hash for
 * its resource pack, which is a pack that never applies and a hash that looks perfectly valid.
 */
public final class PublishedHashRule implements IPackRule {

    /** What a sha1 looks like written down. */
    private static final java.util.regex.Pattern SHA1 =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{40}");

    /**
     * The hash for one file, out of a published {@code sha1.txt}.
     *
     * @param published what the host served; null or empty answers empty
     * @param fileName  which file's hash is wanted — {@code yeukpack.zip}
     * @return the hash in lower case, or empty when this file is not listed or nothing parses
     */
    public Optional<String> hashOf(String published, String fileName) {
        if (published == null || published.isBlank() || fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String wanted = fileName.trim();
        for (String line : published.split("\\R")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length < 2) {
                continue;
            }
            String hash = parts[0].trim();
            // sha1sum writes "*name" for a file it read in binary mode. Both spellings appear in the
            // wild depending on which tool wrote the file.
            String named = parts[1].trim();
            if (named.startsWith("*")) {
                named = named.substring(1);
            }
            // By name, never by position — a file listing several packs would otherwise hand back
            // whichever happened to be first, which is a valid-looking hash for the wrong pack.
            if (!lastSegmentOf(named).equals(wanted)) {
                continue;
            }
            if (!SHA1.matcher(hash).matches()) {
                continue;
            }
            return Optional.of(hash.toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    /** {@code files/yeukpack/yeukpack.zip} and {@code yeukpack.zip} are the same file. */
    private static String lastSegmentOf(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    @Override
    public String describe() {
        return "reading a pack's published sha1";
    }
}
