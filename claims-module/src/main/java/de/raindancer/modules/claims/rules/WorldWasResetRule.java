package de.raindancer.modules.claims.rules;

import java.util.Map;
import java.util.UUID;

/**
 * Whether a claim's world was deleted and generated again underneath it.
 *
 * <h2>What this is for</h2>
 * A world folder that is removed and regenerated comes back with new terrain and the same name. Every claim in
 * it is then protecting whatever the generator happened to put at those coordinates, for an owner who never
 * chose it — and nobody would ever find it to remove by hand, because it looks perfectly ordinary from every
 * screen. So the claims go with the world.
 *
 * <h2>Why the world's id answers it completely</h2>
 * A claim already records the {@code UUID} of its world, because it needs one to index by. That id lives in
 * {@code level.dat}, and the server issues a <b>new</b> one when a world folder is deleted and generated again.
 * A recorded id that does not match the world now carrying that name is therefore, exactly and provably, a
 * claim from a world that no longer exists.
 *
 * <p>Which means nothing new is stored, no timestamp is kept, and there is no second record to drift out of
 * step with the first — the evidence was already in the claim.
 *
 * <h2>The guard that matters more than the feature</h2>
 * <b>A world that is not loaded is not a world that was reset.</b> Servers unload worlds: for maintenance, on a
 * multiverse setup, or because a farm world is between regenerations. "There is no world of that name right
 * now" is what an unloaded world and a deleted one both look like from here, and reading it as a reset would
 * delete every claim in the world the first time somebody unloaded it. That is unrecoverable, and it is the
 * obvious way to write this, so every uncertain case answers false.
 *
 * <p>The same principle as {@code LandVerdict.UNKNOWN} one level along: with nothing to compare against, Core
 * refuses to claim anything — and here, refuses to delete anything.
 */
public final class WorldWasResetRule implements IClaimRule {

    /**
     * Whether the world this claim was made in has been replaced.
     *
     * @param worldName     what the claim says its world is called; null or blank answers false
     * @param recordedId    the world id the claim was made with; null answers false, because "I do not know
     *                      which world this was" is not evidence that it is gone
     * @param loadedWorlds  the ids of the worlds the server has loaded right now, by name. A name that is not
     *                      in here is a world that is not loaded, which is not a world that was reset
     */
    public boolean wasReset(String worldName, UUID recordedId, Map<String, UUID> loadedWorlds) {
        if (worldName == null || worldName.isBlank() || recordedId == null || loadedWorlds == null) {
            return false;
        }
        UUID nowCalledThat = loadedWorlds.get(worldName);
        if (nowCalledThat == null) {
            // Not loaded. Emphatically not the same thing as gone — see the note on the class.
            return false;
        }
        return !nowCalledThat.equals(recordedId);
    }

    @Override
    public String describe() {
        return "whether a claim's world was deleted and generated again underneath it";
    }
}
