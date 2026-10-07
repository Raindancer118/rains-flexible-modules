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

    private static Channel channelOf(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = listener.getClass().getField("connection").get(listener);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    // ------------------------------------------------------------------------------ reading

    private enum Kind { MOVE, TICK_END, ATTACK, SWING, ACTION, COMMAND, USE, INTERACT, CARRIED, HELD_SLOT_OUT, OTHER }

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
                case "net.minecraft.network.protocol.game.ServerboundSwingPacket" -> new Reader(Kind.SWING, new MethodHandle[0]);
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
                default -> new Reader(Kind.OTHER, new MethodHandle[0]);
            };
        } catch (ReflectiveOperationException | RuntimeException mismatch) {
            log.warn("Cannot read {} on this server version; that packet is ignored.", name);
            return new Reader(Kind.OTHER, new MethodHandle[0]);
        }
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
            try {
                Reader reader = reader(msg.getClass());
                if (reader.kind() == Kind.HELD_SLOT_OUT) {
                    int slot = (int) reader.getters()[0].invoke(msg);
                    synchronized (track) {
                        track.packets.lastSlot = slot;
                    }
                }
            } catch (Throwable unexpected) {
                // As above.
            }
            super.write(ctx, msg, promise);
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
            synchronized (track) {
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
                        if (hasRot && !hasPos && yaw == p.lastYaw && pitch == p.lastPitch) {
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
                            track.pendingFlags.add(Flag.of(CheckType.NO_SWING, "attacked without swinging in the same tick"));
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
