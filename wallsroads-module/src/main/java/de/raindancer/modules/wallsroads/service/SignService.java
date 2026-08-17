package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadSign;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Road name-boards: where they go (pure, testable) and putting the text on a real sign block in the
 * world (not — a sign's text is block-entity state {@link de.raindancer.core.world.build.Ground}
 * deliberately does not carry, the same seam {@code BukkitGround}'s own javadoc describes for
 * directional/connected state).
 */
public final class SignService {

    /** Default text, and where a fresh road's signs go: one at each end, one at each open gate. */
    public List<RoadSign> defaultSigns(RoadPath road, Map<ColumnPolygon.Column, Integer> heights,
                                       List<Gate> gatesOnThisRoad) {
        List<RoadSign> signs = new ArrayList<>();
        List<ColumnPolygon.Column> points = road.path().points();
        if (!points.isEmpty()) {
            signs.add(endpointSign(road, points.get(0), heights));
            signs.add(endpointSign(road, points.get(points.size() - 1), heights));
        }
        for (Gate gate : gatesOnThisRoad) {
            if (gate.openingColumns().isEmpty()) {
                continue;
            }
            ColumnPolygon.Column at = gate.openingColumns().get(gate.openingColumns().size() / 2);
            Integer y = heights.get(at);
            int spotY = y == null ? road.fixedY() : y;
            signs.add(new RoadSign(UUID.randomUUID().toString(), road.id(),
                    new Spot(road.world(), at.x(), spotY, at.z()), "SOUTH", List.of(road.name())));
        }
        return signs;
    }

    private RoadSign endpointSign(RoadPath road, ColumnPolygon.Column at, Map<ColumnPolygon.Column, Integer> heights) {
        Integer y = heights.get(at);
        int spotY = y == null ? road.fixedY() : y;
        return new RoadSign(UUID.randomUUID().toString(), road.id(),
                new Spot(road.world(), at.x(), spotY, at.z()), "SOUTH", List.of(road.name()));
    }

    /** Places or updates the real sign block. */
    public boolean applyToWorld(RoadSign sign) {
        World world = Bukkit.getWorld(sign.spot().world());
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(sign.spot().x(), sign.spot().y(), sign.spot().z());
        if (block.getType() != Material.OAK_SIGN && block.getType() != Material.OAK_WALL_SIGN) {
            block.setType(Material.OAK_SIGN, false);
        }
        if (!(block.getState() instanceof Sign signState)) {
            return false;
        }
        List<String> lines = sign.lines();
        for (int i = 0; i < 4; i++) {
            Component line = i < lines.size() ? Component.text(lines.get(i)) : Component.empty();
            signState.getSide(Side.FRONT).line(i, line);
        }
        signState.update(true, false);
        return true;
    }

    /** Removes exactly the sign this module placed, leaving whatever is under it alone. */
    public void removeFromWorld(RoadSign sign) {
        World world = Bukkit.getWorld(sign.spot().world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(sign.spot().x(), sign.spot().y(), sign.spot().z());
        if (block.getType() == Material.OAK_SIGN || block.getType() == Material.OAK_WALL_SIGN) {
            block.setType(Material.AIR, false);
        }
    }
}
