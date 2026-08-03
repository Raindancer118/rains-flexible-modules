package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * How deep and how high the claim reaches, without redrawing it.
 *
 * <p>Separate from the border for a practical reason: somebody who wants their claim to reach bedrock should not
 * have to walk the outline again with the tool. That was the old behaviour and it is why most claims on a server
 * stop a few blocks below the surface — nobody redraws a claim to fix the depth.
 *
 * <h2>Back to the old plugin's version of this screen</h2>
 * The rewrite offered four nudge buttons and one "all the way" preset, which is a lot of clicking to express
 * something anybody could say in one sentence. The old screen had it right: type the number you want, or pick
 * the shape you actually mean.
 *
 * <p>The four shapes are the four reasons people change a claim's height at all — cover everything, cover the
 * surface, hide underground, or just wrap what is around you. Each one is worked out from where the viewer is
 * standing, so "hidden underground" means hidden from where they are rather than from an assumed sea level.
 * The nudge buttons stay, because a claim that is eight blocks short does not want a preset.
 */
public final class ClaimHeightMenu extends ClaimScreen {

    /** How much one click moves the ceiling or the floor. Sixteen: a chunk-tall step, and few clicks to bedrock. */
    private static final int STEP = 16;

    public ClaimHeightMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text("How deep and how high");
    }

    @Override
    protected void render() {
        Claim claim = claim();
        boolean allowed = may(ClaimAdminPermission.MANAGE_SHAPE);
        int min = claim.shape().minY();
        int max = claim.shape().maxY();
        // Read from the world rather than assumed: a world with a custom height is not -64 to 320, and a
        // claim clamped to the wrong numbers is one that stops short of bedrock for ever.
        org.bukkit.World world = services().server().getWorld(claim.worldId());
        int floor = world == null ? -64 : world.getMinHeight();
        int ceiling = world == null ? 319 : world.getMaxHeight() - 1;

        band(MenuLayout.WHO, 2, allowed, Icons.of(Material.NETHERRACK, "<green>Deeper",
                        "<gray>Now reaches down to <white>y " + min,
                        "<dark_gray>−" + STEP + " blocks, floor is y " + floor),
                "The owner's to change",
                click -> move(Math.max(floor, min - STEP), max));

        band(MenuLayout.WHO, 3, allowed, Icons.of(Material.DIRT, "<gray>Shallower",
                        "<gray>Now reaches down to <white>y " + min,
                        "<dark_gray>+" + STEP + " blocks"),
                "The owner's to change",
                click -> move(Math.min(max - 1, min + STEP), max));

        band(MenuLayout.WHO, 5, allowed, Icons.of(Material.GLASS, "<gray>Lower ceiling",
                        "<gray>Now reaches up to <white>y " + max,
                        "<dark_gray>−" + STEP + " blocks"),
                "The owner's to change",
                click -> move(min, Math.max(min + 1, max - STEP)));

        band(MenuLayout.WHO, 6, allowed, Icons.of(Material.LIGHT_BLUE_STAINED_GLASS, "<green>Higher ceiling",
                        "<gray>Now reaches up to <white>y " + max,
                        "<dark_gray>+" + STEP + " blocks, sky is y " + ceiling),
                "The owner's to change",
                click -> move(min, Math.min(ceiling, max + STEP)));

        // Exact numbers, which is what somebody who already knows the answer wants. Asked in chat because
        // Core has no number chooser; the prompt is Core's, so it times out and cancels like every other one.
        band(MenuLayout.RULES, 2, allowed, Icons.of(Material.FILLED_MAP, "<white>Set the bottom",
                        "<gray>Currently <white>y " + min,
                        "<dark_gray>anything from y " + floor + " to y " + ceiling,
                        "",
                        "<dark_gray>click and type it"),
                "The owner's to change",
                click -> askFor("bottom", floor, ceiling, value -> move(value, max)));

        band(MenuLayout.RULES, 4, allowed, Icons.of(Material.FILLED_MAP, "<white>Set the top",
                        "<gray>Currently <white>y " + max,
                        "<dark_gray>anything from y " + floor + " to y " + ceiling,
                        "",
                        "<dark_gray>click and type it"),
                "The owner's to change",
                click -> askFor("top", floor, ceiling, value -> move(min, value)));

        // The four shapes people actually mean, worked out from where they are standing.
        band(MenuLayout.LAND, 1, allowed, Icons.of(Material.BEACON, "<green>Full world height",
                        "<gray>Bedrock to the build limit — nothing above",
                        "<gray>or below the claim is left unprotected.",
                        "<dark_gray>y " + floor + " to y " + ceiling),
                "The owner's to change",
                click -> move(floor, ceiling));

        band(MenuLayout.LAND, 3, allowed, Icons.of(Material.SPYGLASS, "<green>Surface only",
                        "<gray>From 8 blocks below your feet up to",
                        "<gray>the build limit.",
                        "<dark_gray>y " + Math.max(floor, standingAt() - 8) + " to y " + ceiling),
                "The owner's to change",
                click -> move(Math.max(floor, standingAt() - 8), ceiling));

        band(MenuLayout.LAND, 5, allowed, Icons.of(Material.SCULK_CATALYST, "<green>Hidden underground",
                        "<gray>From bedrock up to 8 blocks above your",
                        "<gray>feet — nobody on the surface notices.",
                        "<dark_gray>y " + floor + " to y " + Math.min(ceiling, standingAt() + 8)),
                "The owner's to change",
                click -> move(floor, Math.min(ceiling, standingAt() + 8)));

        band(MenuLayout.LAND, 7, allowed, Icons.of(Material.STRING, "<green>Around me",
                        "<gray>16 blocks below to 32 above your feet.",
                        "<dark_gray>y " + Math.max(floor, standingAt() - 16)
                                + " to y " + Math.min(ceiling, standingAt() + 32)),
                "The owner's to change",
                click -> move(Math.max(floor, standingAt() - 16),
                        Math.min(ceiling, standingAt() + 32)));

        toolbar(6, Icons.of(Material.SPYGLASS, "<white>Show me the border",
                        "<gray>Outline it where you are standing."),
                click -> {
                    viewer.closeInventory();
                    services().visualizer().showClaim(viewer, claim,
                            services().config().visualDurationSeconds());
                });
    }

    /** Where the viewer's feet are, which is what every preset is measured from. */
    private int standingAt() {
        return viewer.getLocation().getBlockY();
    }

    /**
     * Asks for one exact Y and applies it.
     *
     * <p>Out of range is refused rather than clamped: somebody who typed 400 on a world that stops at 319
     * meant something, and silently giving them 319 hides the fact that they were wrong about the world.
     */
    private void askFor(String which, int floor, int ceiling, java.util.function.IntConsumer apply) {
        viewer.closeInventory();
        tell("claim.ask-height", "which", which,
                "min", String.valueOf(floor), "max", String.valueOf(ceiling));

        boolean asked = services().prompts().ask(viewer.getUniqueId(), "Claims",
                java.time.Duration.ofSeconds(24),
                typed -> de.raindancer.core.platform.util.Scheduling.entity(
                        services().plugin(), viewer, () -> {
                            int value;
                            try {
                                value = Integer.parseInt(typed.strip());
                            } catch (NumberFormatException notANumber) {
                                tell("claim.height-not-a-number", "typed", typed.strip());
                                reopen();
                                return;
                            }
                            if (value < floor || value > ceiling) {
                                tell("claim.height-out-of-range", "typed", String.valueOf(value),
                                        "min", String.valueOf(floor), "max", String.valueOf(ceiling));
                                reopen();
                                return;
                            }
                            apply.accept(value);
                        }),
                () -> de.raindancer.core.platform.util.Scheduling.entity(
                        services().plugin(), viewer, this::reopen));
        if (!asked) {
            // Already answering something else. Reopened rather than left staring at a closed inventory.
            reopen();
        }
    }

    /** Applies a new vertical range and says what it became, so the numbers on screen are never stale. */
    private void move(int min, int max) {
        if (max - min + 1 < services().config().minClaimHeight()) {
            tell("error.claim-too-short", "minimum", String.valueOf(services().config().minClaimHeight()));
            return;
        }
        claim().verticalRange(min, max);
        services().claimService().saveAsync(claim());
        tell("claim.height-changed", "claim", claim().name(),
                "min-y", String.valueOf(min), "max-y", String.valueOf(max));
        refresh();
    }
}
