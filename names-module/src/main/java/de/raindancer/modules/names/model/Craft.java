package de.raindancer.modules.names.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Something a crafting grid can be turned into, and what it costs.
 *
 * <p>Four shapes, and the sealed hierarchy is what makes the service that charges for one exhaustive:
 * a fifth craft added here is a compile error at every place that pays for them, rather than a grid
 * that produces an item nobody was charged for.
 *
 * <p>Each craft names <em>slots</em>, never items. The listener that read the grid is the only thing
 * holding the items, so a craft can be resolved, checked and priced without any of them — and the
 * grid it was resolved from can be re-read before it is charged, which is what makes a grid that
 * changed under the click come out as "no result" rather than as a wrong one.
 */
public sealed interface Craft {

    /** Slots this craft takes exactly one item from. */
    List<Integer> takeOne();

    /** The slot whose whole stack is consumed, or {@code -1} when there is none. */
    default int takeAll() {
        return -1;
    }

    /** A name tag and a reagent: the tag comes back carrying a different style. */
    record StyleTag(int tagSlot, int reagentSlot, NameStyle style) implements Craft {
        @Override
        public List<Integer> takeOne() {
            return List.of(tagSlot, reagentSlot);
        }
    }

    /**
     * A styled tag and a plain one: both come back styled.
     *
     * <p>Not in the original sketch of the feature, but it falls out of it. Building a three-stop
     * gradient means holding three tags of the same colour family, and dyeing each one from scratch
     * is busywork that teaches the player nothing.
     */
    record CopyTag(int styledSlot, int plainSlot, NameStyle style) implements Craft {
        @Override
        public List<Integer> takeOne() {
            return List.of(styledSlot, plainSlot);
        }
    }

    /**
     * Two or more styled tags with a plain one among them: one tag carrying the whole gradient.
     *
     * <h2>Why this is worth a recipe of its own</h2>
     * Without it a gradient exists only as a row of tags, and every item painted with it means laying
     * that row out again in the right order — with the chance of getting it backwards each time, and
     * no way to keep the good one in a chest or hand it to somebody else. Burning the gradient onto a
     * single tag makes it a thing you own rather than a procedure you repeat.
     *
     * <p>The stops are the styled tags in grid order and the plain tag can be anywhere, which is both
     * forgiving and unambiguous: the plain one is the blank being written to, so where it sits says
     * nothing, while the others are the colours and their order is the whole point.
     */
    record GradientTag(int plainSlot, List<Integer> styledSlots, NameStyle style) implements Craft {
        public GradientTag {
            styledSlots = List.copyOf(styledSlots);
        }

        @Override
        public List<Integer> takeOne() {
            List<Integer> all = new ArrayList<>(styledSlots);
            all.add(plainSlot);
            return List.copyOf(all);
        }
    }

    /**
     * Tags and one item: the item's name is repainted. The tags are used up, the item is not — it
     * comes back as the result, which is why the whole stack is taken and the whole stack returned.
     */
    record ApplyToItem(int itemSlot, List<Integer> tagSlots, NameStyle style) implements Craft {
        public ApplyToItem {
            tagSlots = List.copyOf(tagSlots);
        }

        @Override
        public List<Integer> takeOne() {
            return tagSlots;
        }

        @Override
        public int takeAll() {
            return itemSlot;
        }
    }
}
