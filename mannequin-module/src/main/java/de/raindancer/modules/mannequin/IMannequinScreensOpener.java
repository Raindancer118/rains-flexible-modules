package de.raindancer.modules.mannequin;

import de.raindancer.modules.mannequin.model.Mannequin;
import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it.
 *
 * <p>The seam exists so that a command — built at bootstrap, long before any menu could be
 * constructed — can name what it wants to open without naming a menu class.
 */
public interface IMannequinScreensOpener {

    /** Choosing a slot's material and enchants. */
    void loadout(Player viewer, Mannequin mannequin);

    /** Choosing whose skin the mannequin wears. */
    void skin(Player viewer, Mannequin mannequin);

    /** The mannequin's combat tally, with a reset button. */
    void stats(Player viewer, Mannequin mannequin);
}
