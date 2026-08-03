package de.raindancer.modules.claims.selection;

import de.raindancer.modules.claims.ClaimSettings;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the active selection per player and owns the vertical range policy. */
public final class SelectionService {

    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public SelectionService(ClaimSettings settings) {
        this.settings = settings;
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>Called on reload. The field is a snapshot rather than a live view, so nothing here has to think about a
     * value changing halfway through a calculation — and replacing the whole snapshot means a reload takes effect
     * on the next event rather than on the next restart.
     */
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    public Optional<Selection> selection(Player player) {
        return Optional.ofNullable(selections.get(player.getUniqueId()));
    }

    public Selection begin(Player player, Selection.Mode mode, Selection.Purpose purpose) {
        World world = player.getWorld();
        Selection selection = new Selection(world.getUID(), world.getName(), mode,
                world.getMinHeight(), world.getMaxHeight() - 1);
        selection.purpose(purpose);
        selections.put(player.getUniqueId(), selection);
        return selection;
    }

    /** Returns the existing selection, or starts a fresh one in the player's current world. */
    public Selection selectionOrBegin(Player player) {
        Selection existing = selections.get(player.getUniqueId());
        if (existing != null && existing.worldId().equals(player.getWorld().getUID())) {
            return existing;
        }
        Selection.Mode mode = existing == null ? Selection.Mode.RECTANGLE : existing.mode();
        Selection.Purpose purpose = existing == null ? Selection.Purpose.NEW_CLAIM : existing.purpose();
        return begin(player, mode, purpose);
    }

    public void clear(Player player) {
        selections.remove(player.getUniqueId());
    }

    public void clear(UUID uuid) {
        selections.remove(uuid);
    }

    public boolean hasSelection(Player player) {
        return selections.containsKey(player.getUniqueId());
    }

    /**
     * Turns the configured vertical mode plus any explicit override into a concrete Y range.
     * <p>
     * This is what makes hidden underground claims possible: in {@code SELECTION} modes the claim only
     * spans the Y values the player actually worked in, so nothing on the surface hints at it.
     */
    public int[] resolveVerticalRange(Selection selection) {
        int worldMin = selection.worldMinY();
        int worldMax = selection.worldMaxY();

        Integer explicitMin = selection.explicitMinY().orElse(null);
        Integer explicitMax = selection.explicitMaxY().orElse(null);
        if (explicitMin != null && explicitMax != null) {
            return new int[]{Math.min(explicitMin, explicitMax), Math.max(explicitMin, explicitMax)};
        }

        int clickedMin = selection.clickedMinY().orElse(worldMin);
        int clickedMax = selection.clickedMaxY().orElse(worldMax);

        int min;
        int max;
        switch (settings.verticalMode()) {
            case FULL_HEIGHT -> {
                min = worldMin;
                max = worldMax;
            }
            case SELECTION -> {
                min = clickedMin;
                max = clickedMax;
            }
            default -> {
                min = clickedMin - settings.verticalPaddingDown();
                max = clickedMax + settings.verticalPaddingUp();
            }
        }
        if (explicitMin != null) {
            min = explicitMin;
        }
        if (explicitMax != null) {
            max = explicitMax;
        }
        min = Math.max(worldMin, Math.min(worldMax, min));
        max = Math.max(worldMin, Math.min(worldMax, max));

        // Underground claims disabled means the claim always reaches the build limit, so nobody can
        // hide a claim beneath somebody else's base.
        if (!settings.allowUndergroundClaims()) {
            min = worldMin;
            max = worldMax;
        }

        int minHeight = settings.minClaimHeight();
        if (max - min + 1 < minHeight) {
            max = Math.min(worldMax, min + minHeight - 1);
            if (max - min + 1 < minHeight) {
                min = Math.max(worldMin, max - minHeight + 1);
            }
        }
        return new int[]{Math.min(min, max), Math.max(min, max)};
    }

    public int activeSelections() {
        return selections.size();
    }
}
