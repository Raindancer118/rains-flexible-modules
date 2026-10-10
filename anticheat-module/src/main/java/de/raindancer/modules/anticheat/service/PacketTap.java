package de.raindancer.modules.anticheat.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.MoveSample;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sits in each player's Netty pipeline, just before the server's own packet handler, and reads what
 * the client sends — every movement (the server's move event skips a client hovering in place), the
 * client's tick clock, and the order in which attacks, swings and movement arrive.
 *
 * <p>Read-only: no packet is changed or dropped. Runs on the Netty thread, so it never touches the
 * world — it records into the player's {@link PlayerTrack} and the movement engine judges on the
 * player's own thread. Paper has no packet API, so the server's classes are reached by reflection;
 * if any of that fails, the tap switches itself off and every event-based check keeps working.
 */
public final class PacketTap implements IAntiCheatService {

    public static final String HANDLER = "rains_anticheat";
    private static final String PACKET_HANDLER = "packet_handler";
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    private final Tracks tracks;
    private final ClickService clicks;
    private final LogChannel log;
    private final Map<Class<?>, Reader> readers = new ConcurrentHashMap<>();
    private volatile boolean broken;
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    public PacketTap(Tracks tracks, ClickService clicks, LogChannel log) {
        this.tracks = tracks;
        this.clicks = clicks;
        this.log = log;
    }

    /** @return whether the tap is now in this player's pipeline */
    public boolean inject(Player player) {
        if (broken || !settings.packetTap()) {
            return false;
        }
        try {
            Channel channel = channelOf(player);
            PlayerTrack track = tracks.of(player);
            if (newPing == null) {
                Object handle = player.getClass().getMethod("getHandle").invoke(player);
                Class<?> ping = Class.forName("net.minecraft.network.protocol.common.ClientboundPingPacket", true,
                        handle.getClass().getClassLoader());
                newPing = LOOKUP.findConstructor(ping, MethodType.methodType(void.class, int.class));
                Class<?> unlist = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket", true,
                        handle.getClass().getClassLoader());
                newUnlist = LOOKUP.findConstructor(unlist, MethodType.methodType(void.class, java.util.List.class));
            }
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER) != null) {
                        channel.pipeline().remove(HANDLER);
                    }
                    Handler handler = new Handler(track);
                    if (channel.pipeline().get(PACKET_HANDLER) != null) {
                        channel.pipeline().addBefore(PACKET_HANDLER, HANDLER, handler);
                    } else {
                        channel.pipeline().addLast(HANDLER, handler);
                    }
                    track.packets.tapped = true;
                } catch (RuntimeException failed) {
                    log.warn("Could not add the packet tap for {}: {}", player.getName(), failed.toString());
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            broken = true;
            log.warn("The packet tap does not fit this server version ({}); packet checks are off and "
                    + "everything else carries on from server events.", failed.toString());
            return false;
        }
    }

    public void eject(Player player) {
        try {
            Channel channel = channelOf(player);
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(HANDLER) != null) {
                    channel.pipeline().remove(HANDLER);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Gone already: the channel closes with the player.
        }
    }

    public boolean broken() {
        return broken;
    }

    /** Takes one player off another's tab list, for the one case Bukkit no longer will. */
    public void unlist(Player viewer, java.util.UUID target) {
        MethodHandle make = newUnlist;
        if (broken || make == null) {
            return;
        }
        try {
            Channel channel = channelOf(viewer);
            Object packet = make.invoke(java.util.List.of(target));
            channel.eventLoop().execute(() -> channel.writeAndFlush(packet, channel.voidPromise()));
        } catch (Throwable unexpected) {
            // At worst a stale tab entry until they relog.
        }
    }

    /** Sends one of our pings to measure the real round trip. Safe from any thread. */
    public void probe(Player player) {
        PlayerTrack track = tracks.of(player);
        MethodHandle ping = newPing;
        if (broken || ping == null || !track.packets.tapped) {
            return;
        }
        try {
            Channel channel = channelOf(player);
            Object packet = ping.invoke(nextTransaction(track));
            channel.eventLoop().execute(() -> channel.writeAndFlush(packet, channel.voidPromise()));
        } catch (Throwable unexpected) {
            // The next probe tries again.
        }
    }

    private static int nextTransaction(PlayerTrack track) {
        synchronized (track.wire) {
            int id = TRANSACTION_BASE - (track.packets.nextTransaction++ & 0xFFFFF);
            track.packets.transactions.put(id, System.nanoTime());
            // Pings that never came back; block changes alone can send a few hundred a minute.
            while (track.packets.transactions.size() > 512) {
                track.packets.transactions.pollFirstEntry();
            }
            return id;
        }
    }

    private static Channel channelOf(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = listener.getClass().getField("connection").get(listener);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    // ------------------------------------------------------------------------------ reading

    private enum Kind { MOVE, TICK_END, ATTACK, SWING, ACTION, COMMAND, USE, INTERACT, CARRIED, HELD_SLOT_OUT, MOTION_OUT, BLOCK_OUT, BLOCKS_OUT, PONG, UNLIST_OUT, OTHER }

    private volatile EspShield shield;
    private volatile MethodHandle newUnlist;

    public void shieldWith(EspShield shield) {
        this.shield = shield;
    }

    /** Our own ping ids live far below anything vanilla or another plugin is likely to use. */
    private static final int TRANSACTION_BASE = -1_430_000_000;
    private volatile MethodHandle newPing;

    /** Accessors for one packet class, found once. */
    private record Reader(Kind kind, MethodHandle[] getters) {
    }

    private Reader reader(Class<?> type) {
        return readers.computeIfAbsent(type, this::describe);
    }

    private Reader describe(Class<?> type) {
        String name = type.getName();
        try {
            if (name.startsWith("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket")) {
                return new Reader(Kind.MOVE, new MethodHandle[]{
                        LOOKUP.findVirtual(type, "hasPosition", MethodType.methodType(boolean.class)),
                        LOOKUP.findVirtual(type, "hasRotation", MethodType.methodType(boolean.class)),
                        LOOKUP.findVirtual(type, "getX", MethodType.methodType(double.class, double.class)),
                        LOOKUP.findVirtual(type, "getY", MethodType.methodType(double.class, double.class)),
                        LOOKUP.findVirtual(type, "getZ", MethodType.methodType(double.class, double.class)),
                        LOOKUP.findVirtual(type, "getYRot", MethodType.methodType(float.class, float.class)),
                        LOOKUP.findVirtual(type, "getXRot", MethodType.methodType(float.class, float.class)),
                        LOOKUP.findVirtual(type, "isOnGround", MethodType.methodType(boolean.class)),
                        LOOKUP.findVirtual(type, "horizontalCollision", MethodType.methodType(boolean.class))});
            }
            return switch (name) {
                case "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket" -> new Reader(Kind.TICK_END, new MethodHandle[0]);
                case "net.minecraft.network.protocol.game.ServerboundAttackPacket" -> new Reader(Kind.ATTACK, new MethodHandle[]{
                        LOOKUP.findVirtual(type, "entityId", MethodType.methodType(int.class))});
                // 26.3 renamed the arm swing to a punch (and dropped the hand); the old name is 26.2 and before.
                case "net.minecraft.network.protocol.game.ServerboundPunchPacket",
                     "net.minecraft.network.protocol.game.ServerboundSwingPacket" -> new Reader(Kind.SWING, new MethodHandle[0]);
                case "net.minecraft.network.protocol.game.ServerboundPlayerActionPacket" -> new Reader(Kind.ACTION, new MethodHandle[]{
                        LOOKUP.findVirtual(type, "getAction", MethodType.methodType(type.getMethod("getAction").getReturnType()))});
                case "net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket" -> new Reader(Kind.COMMAND, new MethodHandle[]{
                        LOOKUP.findVirtual(type, "getAction", MethodType.methodType(type.getMethod("getAction").getReturnType()))});
                case "net.minecraft.network.protocol.game.ServerboundUseItemOnPacket",
                     "net.minecraft.network.protocol.game.ServerboundUseItemPacket" -> new Reader(Kind.USE, new MethodHandle[0]);
                case "net.minecraft.network.protocol.game.ServerboundInteractPacket" -> new Reader(Kind.INTERACT, new MethodHandle[0]);
                case "net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket" -> new Reader(Kind.CARRIED, new MethodHandle[]{
                        LOOKUP.findVirtual(type, "getSlot", MethodType.methodType(int.class))});
                case "net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket" -> new Reader(Kind.HELD_SLOT_OUT, new MethodHandle[]{
                        LOOKUP.findVirtual(type, "slot", MethodType.methodType(int.class))});
                case "net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket" -> {
                    if (newPing == null) {
                        Class<?> ping = Class.forName("net.minecraft.network.protocol.common.ClientboundPingPacket", true, type.getClassLoader());
                        newPing = LOOKUP.findConstructor(ping, MethodType.methodType(void.class, int.class));
                    }
                    yield new Reader(Kind.MOTION_OUT, new MethodHandle[]{
                            LOOKUP.findVirtual(type, "id", MethodType.methodType(int.class))});
                }
                case "net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket" -> {
                    pingsFrom(type);
                    Class<?> pos = type.getMethod("getPos").getReturnType();
                    yield new Reader(Kind.BLOCK_OUT, withCoordinates(LOOKUP.findVirtual(type, "getPos", MethodType.methodType(pos)), pos));
                }
                case "net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket" -> {
                    pingsFrom(type);
                    Class<?> pos = Class.forName("net.minecraft.core.BlockPos", true, type.getClassLoader());
                    yield new Reader(Kind.BLOCKS_OUT, withCoordinates(LOOKUP.findVirtual(type, "runUpdates",
                            MethodType.methodType(void.class, java.util.function.BiConsumer.class)), pos));
                }
                case "net.minecraft.network.protocol.common.ServerboundPongPacket" -> new Reader(Kind.PONG, new MethodHandle[]{
                        LOOKUP.findVirtual(type, "getId", MethodType.methodType(int.class))});
                case "net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket" -> {
                    newUnlist = LOOKUP.findConstructor(type, MethodType.methodType(void.class, java.util.List.class));
                    yield new Reader(Kind.UNLIST_OUT, new MethodHandle[]{
                            LOOKUP.findVirtual(type, "profileIds", MethodType.methodType(java.util.List.class))});
                }
                default -> new Reader(Kind.OTHER, new MethodHandle[0]);
            };
        } catch (ReflectiveOperationException | RuntimeException mismatch) {
            log.warn("Cannot read {} on this server version; that packet is ignored.", name);
            return new Reader(Kind.OTHER, new MethodHandle[0]);
        }
    }

    private void pingsFrom(Class<?> packet) throws ReflectiveOperationException {
        if (newPing == null) {
            Class<?> ping = Class.forName("net.minecraft.network.protocol.common.ClientboundPingPacket", true, packet.getClassLoader());
            newPing = LOOKUP.findConstructor(ping, MethodType.methodType(void.class, int.class));
        }
    }

    private static MethodHandle[] withCoordinates(MethodHandle first, Class<?> blockPos) throws ReflectiveOperationException {
        return new MethodHandle[]{first,
                LOOKUP.findVirtual(blockPos, "getX", MethodType.methodType(int.class)),
                LOOKUP.findVirtual(blockPos, "getY", MethodType.methodType(int.class)),
                LOOKUP.findVirtual(blockPos, "getZ", MethodType.methodType(int.class))};
    }

    /** How far from the player a changed block can still matter to their next moves. */
    private static final int BLOCK_REACH = 3;

    /**
     * Notes the block changes of one packet that lie near the player and returns the ping to send
     * right behind it, or null when none of them are near.
     */
    private Object blockChange(PlayerTrack track, Reader reader, Object msg) throws Throwable {
        MethodHandle[] g = reader.getters();
        PlayerTrack.Movement m = track.movement;
        int px = (int) Math.floor(m.x);
        int py = (int) Math.floor(m.y);
        int pz = (int) Math.floor(m.z);
        java.util.List<int[]> near = new java.util.ArrayList<>(4);
        java.util.function.Consumer<Object> consider = pos -> {
            try {
                int x = (int) g[1].invoke(pos);
                int y = (int) g[2].invoke(pos);
                int z = (int) g[3].invoke(pos);
                if (Math.abs(x - px) <= BLOCK_REACH && Math.abs(z - pz) <= BLOCK_REACH && y >= py - BLOCK_REACH && y <= py + BLOCK_REACH + 1) {
                    near.add(new int[]{x, y, z});
                }
            } catch (Throwable unreadable) {
                // Nothing to note.
            }
        };
        if (reader.kind() == Kind.BLOCK_OUT) {
            consider.accept(g[0].invoke(msg));
        } else {
            g[0].invoke(msg, (java.util.function.BiConsumer<Object, Object>) (pos, state) -> consider.accept(pos));
        }
        if (near.isEmpty() || newPing == null) {
            return null;
        }
        int id = nextTransaction(track);
        long now = track.now();
        for (int[] block : near) {
            track.packets.blocks.sent(block[0], block[1], block[2], id, now);
        }
        return newPing.invoke(id);
    }

    private final class Handler extends ChannelDuplexHandler {

        private final PlayerTrack track;

        Handler(PlayerTrack track) {
            this.track = track;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                read(msg);
            } catch (Throwable unexpected) {
                // A tap that throws would take the player's connection with it.
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object follow = null;
            try {
                Reader reader = reader(msg.getClass());
                if (reader.kind() == Kind.UNLIST_OUT && droppedByShield(reader, msg)) {
                    promise.setSuccess();
                    return;
                }
                if (reader.kind() == Kind.HELD_SLOT_OUT) {
                    int slot = (int) reader.getters()[0].invoke(msg);
                    synchronized (track.wire) {
                        track.packets.lastSlot = slot;
                    }
                } else if (reader.kind() == Kind.BLOCK_OUT || reader.kind() == Kind.BLOCKS_OUT) {
                    follow = blockChange(track, reader, msg);
                } else if (reader.kind() == Kind.MOTION_OUT && (int) reader.getters()[0].invoke(msg) == track.entityId) {
                    // Knockback for this very player: the ping right behind it is answered the moment
                    // the client has applied it, which pins down when the push really happened.
                    int id = nextTransaction(track);
                    follow = newPing.invoke(id);
                    synchronized (track.wire) {
                        for (var it = track.movement.velocities.descendingIterator(); it.hasNext(); ) {
                            double[] velocity = it.next();
                            if (velocity[6] == 0) {
                                velocity[6] = id;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable unexpected) {
                // As above.
            }
            super.write(ctx, msg, promise);
            if (follow != null) {
                ctx.write(follow, ctx.voidPromise());
            }
        }

        private void read(Object msg) throws Throwable {
            Reader reader = reader(msg.getClass());
            if (reader.kind() == Kind.OTHER || !settings.enabled()) {
                return;
            }
            long nanos = System.nanoTime();
            long millis = track.now();
            PlayerTrack.Packets p = track.packets;
            MethodHandle[] g = reader.getters();
            synchronized (track.wire) {
                switch (reader.kind()) {
                    case MOVE -> {
                        boolean hasPos = (boolean) g[0].invoke(msg);
                        boolean hasRot = (boolean) g[1].invoke(msg);
                        float yaw = (float) g[5].invoke(msg, Float.NaN);
                        float pitch = (float) g[6].invoke(msg, Float.NaN);
                        if (hasRot && Math.abs(pitch) > 90.0001f) {
                            track.pendingFlags.add(Flag.of(CheckType.BAD_PACKETS, 2,
                                    String.format(Locale.ROOT, "pitch %.1f, past straight up or down", pitch)));
                        }
                        // A passenger's client sends its rotation every tick, changed or not.
                        if (hasRot && !hasPos && !p.riding && yaw == p.lastYaw && pitch == p.lastPitch) {
                            track.pendingFlags.add(Flag.of(CheckType.BAD_PACKETS, 0.5, "sent the same rotation twice"));
                        }
                        if (hasRot) {
                            p.lastYaw = yaw;
                            p.lastPitch = pitch;
                        }
                        int ticks = 1;
                        if (p.sendsTickEnd) {
                            ticks = Math.max(1, Math.min(20, p.ticksSinceTickEnd));
                            p.ticksSinceTickEnd = 0;
                            p.movedThisTick = true;
                        } else {
                            track.timer.tick(nanos);
                        }
                        if (hasPos || hasRot) {
                            track.samples.add(new MoveSample(
                                    (double) g[2].invoke(msg, Double.NaN), (double) g[3].invoke(msg, Double.NaN),
                                    (double) g[4].invoke(msg, Double.NaN), yaw, pitch, hasPos, hasRot,
                                    (boolean) g[7].invoke(msg), (boolean) g[8].invoke(msg), ticks, nanos, true));
                        }
                    }
                    case TICK_END -> {
                        p.sendsTickEnd = true;
                        p.ticksSinceTickEnd++;
                        track.timer.tick(nanos);
                        p.movedThisTick = false;
                        p.usedThisTick = false;
                        track.combat.targetsThisTick.clear();
                        if (p.attacksAwaitingSwing > 0) {
                            p.attacksAwaitingSwing = 0;
                            if (!de.raindancer.modules.anticheat.rules.CombatRule.swingCovers(p.swung ? nanos - p.lastSwingNanos : Long.MAX_VALUE)) {
                                track.pendingFlags.add(Flag.of(CheckType.NO_SWING, "attacked without swinging in the same tick"));
                            }
                        }
                        p.startsThisTick = 0;
                    }
                    case ATTACK -> {
                        post("an attack");
                        int target = (int) g[0].invoke(msg);
                        if (p.sendsTickEnd) {
                            track.combat.targetsThisTick.add(target);
                            if (track.combat.targetsThisTick.size() >= 2) {
                                track.pendingFlags.add(Flag.of(CheckType.MULTI_AURA, 2,
                                        track.combat.targetsThisTick.size() + " different targets in one tick"));
                            }
                            p.attacksAwaitingSwing++;
                        }
                    }
                    case SWING -> {
                        post("a swing");
                        p.attacksAwaitingSwing = 0;
                        p.swung = true;
                        p.lastSwingNanos = nanos;
                        if (!p.digging && !p.usedThisTick) {
                            Flag clicked = clicks.click(track, millis);
                            if (clicked != null) {
                                track.pendingFlags.add(clicked);
                            }
                        }
                    }
                    case ACTION -> {
                        String action = String.valueOf(g[0].invoke(msg));
                        switch (action) {
                            case "START_DESTROY_BLOCK" -> {
                                post("starting to dig");
                                p.digging = true;
                                p.startsThisTick++;
                                if (p.sendsTickEnd && p.startsThisTick >= 3) {
                                    track.pendingFlags.add(Flag.of(CheckType.NUKER, 2, p.startsThisTick + " blocks started in one tick"));
                                }
                            }
                            case "STOP_DESTROY_BLOCK", "ABORT_DESTROY_BLOCK" -> {
                                post("digging");
                                p.digging = false;
                            }
                            case "DROP_ITEM", "DROP_ALL_ITEMS", "SWAP_ITEM_WITH_OFFHAND", "RELEASE_USE_ITEM" -> post("an item action");
                            default -> {
                            }
                        }
                    }
                    case COMMAND -> {
                        String action = String.valueOf(g[0].invoke(msg));
                        if (action.equals("START_SPRINTING")) {
                            if (p.sprintingByCommand) {
                                track.pendingFlags.add(Flag.of(CheckType.BAD_PACKETS, 0.5, "started sprinting twice"));
                            }
                            p.sprintingByCommand = true;
                        } else if (action.equals("STOP_SPRINTING")) {
                            p.sprintingByCommand = false;
                        }
                    }
                    case USE -> {
                        post("using an item");
                        p.usedThisTick = true;
                    }
                    case INTERACT -> post("an interaction");
                    case PONG -> {
                        int id = (int) g[0].invoke(msg);
                        Long sent = p.transactions.remove(id);
                        if (sent != null) {
                            p.roundTrips.add((nanos - sent) / 1_000_000.0);
                            p.blocks.confirmed(id, millis);
                            for (double[] velocity : track.movement.velocities) {
                                if (velocity[6] == id) {
                                    velocity[7] = millis;
                                }
                            }
                        }
                    }
                    case CARRIED -> {
                        int slot = (int) g[0].invoke(msg);
                        if (slot == p.lastSlot) {
                            track.pendingFlags.add(Flag.of(CheckType.BAD_PACKETS, 0.5, "selected slot " + slot + ", already held"));
                        }
                        p.lastSlot = slot;
                    }
                    default -> {
                    }
                }
            }
        }

        /** A tab-list removal caused only by the anti-ESP shield hiding these players: the client keeps them listed. */
        private boolean droppedByShield(Reader reader, Object msg) throws Throwable {
            EspShield current = shield;
            if (current == null) {
                return false;
            }
            java.util.List<?> ids = (java.util.List<?>) reader.getters()[0].invoke(msg);
            if (ids.isEmpty()) {
                return false;
            }
            for (Object id : ids) {
                if (!(id instanceof java.util.UUID uuid) || !current.hides(track.id(), uuid)) {
                    return false;
                }
            }
            return true;
        }

        /** Vanilla sends actions before the tick's movement; a killaura often sends them after. */
        private void post(String what) {
            if (track.packets.sendsTickEnd && track.packets.movedThisTick) {
                track.pendingFlags.add(Flag.of(CheckType.POST, what + " sent after the tick's movement"));
            }
        }
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "reading what each client sends, before the server handles it";
    }
}
