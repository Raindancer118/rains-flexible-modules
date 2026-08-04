package de.raindancer.modules.names.rules;

/**
 * Something that <em>decides</em> and does nothing else.
 *
 * <p>That is the whole membership rule for this package. Here it earns its keep twice over, because
 * every rule in this module is asked at least twice for one craft: once to draw the preview in the
 * result slot, and again on the click that takes it. A rule that changed anything the first time would
 * charge the player for looking.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>No side effects.</b> Nothing written, nothing sent, nothing scheduled, and the grid being
 *       judged is not changed by judging it.</li>
 *   <li><b>Safe from any thread.</b> A crafting grid belongs to whichever region thread owns the
 *       player on Folia, and the same rule instance is shared by everybody crafting at once.</li>
 *   <li><b>No server needed.</b> Every rule here takes plain values — {@code model.Ingredient}, never
 *       an {@code ItemStack} — which is what makes the deciding half of this module testable at all,
 *       and it is the half that determines whether somebody's items are consumed.</li>
 * </ul>
 */
public interface INamesRule {

    /**
     * What this rule is about, in a few words.
     *
     * <p>Ends up in the diagnostic naming which rule refused something. Defaulted from the class name
     * so a rule cannot fail to have one.
     */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
