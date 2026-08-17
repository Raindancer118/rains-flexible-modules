package de.raindancer.modules.wallsroads;

import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import org.bukkit.entity.Player;

/**
 * Opening one of this module's screens, without knowing which class draws it — the seam a command
 * built at bootstrap needs, the same reason every other module here has one of these.
 */
public interface IWallsRoadsScreensOpener {

    /** Every wall and road this player owns — what bare {@code /wallsroads} opens. */
    void list(Player viewer);

    /** One wall's page: material, height, thickness, corner style, its gates, build/teardown. */
    void wall(Player viewer, Wall wall);

    /** One road's page: material, width, elevation mode, its signs, build/teardown. */
    void road(Player viewer, RoadPath road);
}
