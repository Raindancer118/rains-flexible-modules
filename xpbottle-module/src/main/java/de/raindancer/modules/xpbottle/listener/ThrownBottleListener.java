package de.raindancer.modules.xpbottle.listener;

import de.raindancer.modules.xpbottle.XpBottleServices;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.store.BottleTags;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * A bottled bottle, thrown.
 *
 * <h2>Why this exists at all</h2>
 * Because a bottle o' enchanting that cannot be thrown is not a bottle o' enchanting. The plain path
 * hands back the real item — it stacks nowhere, but it flies, it breaks, and it makes the noise —
 * and vanilla would then pay out its usual three to eleven points. A bottle that took 137 and gave
 * back seven is the whole module failing at the one thing it promises.
 *
 * <p>So the amount is overridden here, from the tag the thrown item still carries: a projectile keeps
 * the {@code ItemStack} it was thrown as, persistent data and all, which is what makes this possible
 * without remembering anything about who threw what.
 *
 * <h2>What is deliberately not touched</h2>
 * An untagged bottle o' enchanting. Those are vanilla's, players throw them, and they pay out
 * vanilla's random three to eleven — reaching into that would change a number nobody asked this
 * module about.
 */
public final class ThrownBottleListener implements IXpBottleListener {

    private final XpBottleServices services;

    public ThrownBottleListener(XpBottleServices services) {
        this.services = services;
    }

    /**
     * {@link EventPriority#HIGH}, not {@code MONITOR}: the amount is being <em>set</em>, so this has
     * to run before whatever reads it, and late enough that a plugin with an opinion about
     * experience multipliers has already had its say on the vanilla number.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSplash(ExpBottleEvent event) {
        ItemStack thrown = event.getEntity().getItem();
        Optional<Bottle> bottle = BottleTags.read(thrown, services.config());
        if (bottle.isEmpty()) {
            return;
        }
        // Zero is a real answer: an empty bottle that was somehow thrown pays out nothing rather
        // than falling through to vanilla's three-to-eleven, which would be points from nowhere.
        event.setExperience(bottle.get().stored());
        event.setShowEffect(bottle.get().stored() > 0);
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered about anybody: what a thrown bottle is worth travels on the bottle.
    }

    @Override
    public String describe() {
        return "a thrown bottle paying out exactly what went into it, not vanilla's three to eleven";
    }
}
