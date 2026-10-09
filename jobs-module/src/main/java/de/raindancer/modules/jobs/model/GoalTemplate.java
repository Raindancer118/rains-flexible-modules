package de.raindancer.modules.jobs.model;

import de.raindancer.core.ui.choose.ItemSelection;

/**
 * A kind of goal the board can put up, from jobs.yml.
 *
 * @param items what counts towards it
 * @param start how big the first one is; later ones learn from how it went
 * @param least the smallest it may ever learn to be
 * @param most  the largest
 * @param days  how long one runs
 */
public record GoalTemplate(String id, String title, String icon, GoalKind kind, ItemSelection items, int start,
                           int least, int most, int days) {
}
