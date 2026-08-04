package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may not be acted on, remembered for the times they are not here to ask.
 *
 * <h2>This list is the whole answer</h2>
 * Protection used to be a permission node, mirrored in here when its holder logged in. That is gone:
 * a permission plugin can only answer for somebody who is <em>online</em>, so the fact that decides
 * whether an <em>offline</em> account can be banned was being read from a source that cannot answer
 * about offline accounts. It was patched by remembering the last answer, which left the protection as
 * good as whenever that person last logged in — and a grant made while they were away did nothing at
 * all.
 *
 * <p>So the file is now the source rather than a cache of one, and the only way into it is
 * {@code /protect} and {@code /unprotect} <b>from the console</b>. Nothing in the game can protect an
 * account: not a rank, not a menu, not a permission somebody was granted in LuckPerms. That is the
 * point — the thing that stops one moderator acting on another must not be handed out by the same
 * people it is aimed at.
 *
 * <p>Operators are protected on top of this list and are not in it (see {@code StaffRule}), so a fresh
 * server is never in the window where an admin can ban the owner before anybody has typed anything.
 *
 * <h2>Why it is written down</h2>
 * Because a memory that starts empty is a hole that reopens at every restart, and this one would
 * reopen for good: nothing logs in to refill it any more.
 *
 * <h2>Thread safety</h2>
 * Read from the rule, which is asked from render loops and from command threads; written from the join
 * handler. Hence the concurrent set.
 */
public final class ImmuneStaff {

    private static final LogChannel log = Log.of("moderation");

    private final Set<UUID> immune = ConcurrentHashMap.newKeySet();
    private final YamlStore store;

    public ImmuneStaff(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("immune.yml"));
    }

    /**
     * Protects an account. <b>The only way in, and it is reached from the console alone.</b>
     *
     * @return whether this changed anything, so the caller can avoid a pointless write
     */
    public boolean protect(UUID who) {
        return who != null && immune.add(who);
    }

    /**
     * Takes the protection off again.
     *
     * <p>The half that has to exist. A protection nothing can lift is one that survives the person
     * leaving the staff, and then the only way to act on that account is a text editor and a restart.
     *
     * @return whether this changed anything
     */
    public boolean unprotect(UUID who) {
        return who != null && immune.remove(who);
    }

    /** Whether this account is protected from moderators. The console is never asked. */
    public boolean isImmune(UUID who) {
        return who != null && immune.contains(who);
    }

    /** Everybody on the list, for the console command that shows it. */
    public Set<UUID> all() {
        return Set.copyOf(immune);
    }

    public int size() {
        return immune.size();
    }

    /** Reads what is on disk. Called once, when the module starts. */
    public void load() {
        List<String> ids = store.read().getStringList("immune");
        immune.clear();
        List<String> unreadable = new ArrayList<>();
        for (String id : ids) {
            try {
                immune.add(UUID.fromString(id));
            } catch (IllegalArgumentException notAnId) {
                unreadable.add(id);
            }
        }
        if (!unreadable.isEmpty()) {
            // Loud, because the consequence is an account somebody believes is protected and is not.
            log.error("{} entry/entries in immune.yml are not player ids and have been skipped: {}. "
                    + "Those accounts are NOT protected until they next log in.",
                    unreadable.size(), String.join(", ", unreadable));
        }
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        List<String> ids = new ArrayList<>();
        for (UUID who : immune) {
            ids.add(who.toString());
        }
        return store.write(yaml -> yaml.set("immune", ids));
    }
}
