package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.model.Gate;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadSegment;
import de.raindancer.modules.wallsroads.model.RoadSign;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Road name-boards: where they go and which way they face (pure, testable), and putting the text on
 * a real sign block (not — a sign's text is block-entity state {@link Ground} deliberately does not
 * carry).
 *
 * <p>Every sign stands <em>beside</em> the road on the block above its surface, never on the surface
 * itself: the old code put them at the paving's own height, which is inside the road.
 */
public final class SignService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Where a fresh road's signs go: one at each end, one at each gate it passes through. */
    public List<RoadSign> defaultSigns(RoadPath road, List<RoadSegment> plan, List<Gate> gatesOnThisRoad,
                                       Ground ground) {
        List<RoadSign> signs = new ArrayList<>();
        if (plan.isEmpty()) {
            return signs;
        }
        signs.add(endpointSign(road, plan, 0, ground));
        signs.add(endpointSign(road, plan, plan.size() - 1, ground));

        for (Gate gate : gatesOnThisRoad) {
            if (gate.openingColumns().isEmpty()) {
                continue;
            }
            Column at = gate.openingColumns().get(gate.openingColumns().size() / 2);
            int index = nearestIndex(plan, at);
            signs.add(signAt(road, plan, index, List.of(road.name(), "", "gate", ""), ground));
        }
        return signs;
    }

    /**
     * A signpost where this road meets another: which way each one goes, and how far to its far end.
     *
     * <p>A junction with no sign is where somebody stands and guesses, and a road network nobody can
     * navigate is a decoration.
     */
    public List<RoadSign> junctionSigns(RoadPath road, List<RoadSegment> plan,
                                        List<RoadPath> others, Ground ground) {
        List<RoadSign> signs = new ArrayList<>();
        for (RoadPath other : others) {
            if (other.id().equals(road.id()) || !other.world().equals(road.world())) {
                continue;
            }
            var otherFootprint = other.path().footprint(other.width());
            for (int index = 0; index < plan.size(); index++) {
                if (!otherFootprint.contains(plan.get(index).column())) {
                    continue;
                }
                int metres = (int) Math.round(other.path().length());
                signs.add(signAt(road, plan, index,
                        List.of(road.name(), "", other.name(), metres + " blocks"), ground));
                break;
            }
        }
        return signs;
    }

    private RoadSign endpointSign(RoadPath road, List<RoadSegment> plan, int index, Ground ground) {
        return signAt(road, plan, index, List.of(road.name(), "", "", ""), ground);
    }

    /** One sign beside the road at this step, turned to face along it. */
    private RoadSign signAt(RoadPath road, List<RoadSegment> plan, int index, List<String> lines,
                            Ground ground) {
        RoadSegment segment = plan.get(Math.max(0, Math.min(plan.size() - 1, index)));
        Column previous = plan.get(Math.max(0, index - 1)).column();
        Column next = plan.get(Math.min(plan.size() - 1, index + 1)).column();

        double dx = next.x() - previous.x();
        double dz = next.z() - previous.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) {
            dx = 1;
            dz = 0;
            length = 1;
        }
        int aside = (int) Math.ceil(road.width() / 2.0) + 1;
        Column beside = segment.column().offset(
                (int) Math.round(-dz / length * aside), (int) Math.round(dx / length * aside));

        Spot spot = new Spot(road.world(), beside.x(), segment.surfaceY() + 1, beside.z());
        String replaced = ground == null ? "AIR" : ground.materialAt(spot);
        return new RoadSign(UUID.randomUUID().toString(), road.id(), spot,
                rotationFacing(dx, dz), lines, replaced == null ? "AIR" : replaced);
    }

    /**
     * Which of a standing sign's sixteen turns faces a reader coming along the road.
     *
     * <p>Sixteen, not four: a sign on a curve turned to the nearest compass point is visibly askew
     * next to a road that is not.
     */
    public static int rotationFacing(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int turn = (int) Math.round(degrees / 22.5);
        return ((turn % 16) + 16) % 16;
    }

    private static int nearestIndex(List<RoadSegment> plan, Column column) {
        int best = 0;
        long closest = Long.MAX_VALUE;
        for (int i = 0; i < plan.size(); i++) {
            Column at = plan.get(i).column();
            long dx = at.x() - column.x();
            long dz = at.z() - column.z();
            long distance = dx * dx + dz * dz;
            if (distance < closest) {
                closest = distance;
                best = i;
            }
        }
        return best;
    }

    /** Places or updates the real sign block. */
    public boolean applyToWorld(RoadSign sign) {
        World world = Bukkit.getWorld(sign.spot().world());
        if (world == null) {
            return false;
        }
        Block block = world.getBlockAt(sign.spot().x(), sign.spot().y(), sign.spot().z());
        if (block.getType() != Material.OAK_SIGN) {
            block.setType(Material.OAK_SIGN, false);
        }
        if (block.getBlockData() instanceof Rotatable rotatable) {
            rotatable.setRotation(faceOf(sign.rotation()));
            block.setBlockData(rotatable, false);
        }
        if (!(block.getState() instanceof Sign signState)) {
            return false;
        }
        List<String> lines = sign.lines();
        for (int i = 0; i < 4; i++) {
            Component line = i < lines.size() ? MINI.deserialize(lines.get(i)) : Component.empty();
            signState.getSide(Side.FRONT).line(i, line);
            signState.getSide(Side.BACK).line(i, line);
        }
        signState.update(true, false);
        return true;
    }

    /** Puts back whatever the sign was standing in, rather than leaving a hole where it stood. */
    public void removeFromWorld(RoadSign sign) {
        World world = Bukkit.getWorld(sign.spot().world());
        if (world == null) {
            return;
        }
        Block block = world.getBlockAt(sign.spot().x(), sign.spot().y(), sign.spot().z());
        if (block.getType() != Material.OAK_SIGN && block.getType() != Material.OAK_WALL_SIGN) {
            return;
        }
        Material previous = Material.matchMaterial(sign.replaced() == null ? "AIR" : sign.replaced());
        block.setType(previous == null || !previous.isBlock() ? Material.AIR : previous, false);
    }

    private static org.bukkit.block.BlockFace faceOf(int rotation) {
        org.bukkit.block.BlockFace[] turns = {
                org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.SOUTH_SOUTH_WEST,
                org.bukkit.block.BlockFace.SOUTH_WEST, org.bukkit.block.BlockFace.WEST_SOUTH_WEST,
                org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.WEST_NORTH_WEST,
                org.bukkit.block.BlockFace.NORTH_WEST, org.bukkit.block.BlockFace.NORTH_NORTH_WEST,
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.NORTH_NORTH_EAST,
                org.bukkit.block.BlockFace.NORTH_EAST, org.bukkit.block.BlockFace.EAST_NORTH_EAST,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.EAST_SOUTH_EAST,
                org.bukkit.block.BlockFace.SOUTH_EAST, org.bukkit.block.BlockFace.SOUTH_SOUTH_EAST};
        return turns[((rotation % 16) + 16) % 16];
    }
}
