package de.raindancer.modules.ctf;

import org.bukkit.entity.Player;

/** Opening a screen, so nothing else depends on the screens. */
public interface ICtfScreensOpener {

    void main(Player viewer);

    void vote(Player viewer);

    void classes(Player viewer);

    void sides(Player viewer);

    void stats(Player viewer, java.util.UUID whose);

    void top(Player viewer);

    void looks(Player viewer);

    void challenges(Player viewer);

    void admin(Player viewer);

    void setup(Player viewer);
}
