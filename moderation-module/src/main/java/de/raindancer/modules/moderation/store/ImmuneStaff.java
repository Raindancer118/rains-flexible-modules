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
 * <h2>The hole this closes</h2>
 * A permission plugin can only answer for somebody who is <em>online</em>. Ask
 * {@code server.getPlayer(uuid)} about an offline account and the answer is {@code null}, so every
 * permission they hold reads as absent. For most nodes that is harmless — an offline player is not
 * running commands, so what they may <em>do</em> never comes up.
 *
 * <p>{@code IMMUNE} is the exception, and it is the dangerous one, because immunity is a fact about the
 * <b>subject</b> — and the subject of a ban is very often offline. That is, after all, usually why it is
 * being done through a menu. Without this class, any moderator could ban the owner by waiting until the
 * owner logged off. The code carried a comment claiming it failed in the safe direction; it failed in
 * the other one, and a review caught it.
 *
 * <h2>Why it is written down</h2>
 * Because a memory that starts empty is a hole that reopens at every restart, exactly as wide as the
 * time until each immune person next logs in.
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
     * Records what was seen when somebody was last online.
     *
     * <p>Both directions matter. Forgetting on {@code false} is what stops a demotion leaving somebody
     * permanently untouchable — the failure in the other direction, and just as hard to explain.
     *
     * @return whether this changed anything, so the caller can avoid a pointless write
     */
    public boolean remember(UUID who, boolean holdsIt) {
        if (who == null) {
            return false;
        }
        return holdsIt ? immune.add(who) : immune.remove(who);
    }

    /** Whether this account is protected from moderators. The console is never asked. */
    public boolean isImmune(UUID who) {
        return who != null && immune.contains(who);
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
