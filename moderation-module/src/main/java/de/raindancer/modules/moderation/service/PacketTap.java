package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.moderation.AntiCheatSettings;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.UUID;

/**
 * The anti-ESP shield's one hand in each player's connection. Hiding a player through Bukkit also takes them off
 * the viewer's tab list; this drops that one packet when the shield is the only reason for it, and sends the
 * removal itself when vanish hides somebody the shield already hid. Nothing a client sends is read here — the
 * checks are Grim's. Paper has no packet API, so the server's classes are reached by reflection; when that does
 * not fit the server version, the tap switches itself off and hidden players merely drop off tab lists.
 */
public final class PacketTap implements IModerationService {

    public static final String HANDLER = "rains_anticheat";
    private static final String PACKET_HANDLER = "packet_handler";
    private static final String REMOVE = "net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket";
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    private final LogChannel log;
    private volatile boolean broken;
    private volatile Class<?> removeType;
    private volatile MethodHandle newRemove;
    private volatile MethodHandle removedIds;
    private volatile EspShield shield;
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    public PacketTap(LogChannel log) {
        this.log = log;
    }

    public void shieldWith(EspShield shield) {
        this.shield = shield;
    }

    /** @return whether the tap is now in this player's pipeline */
    public boolean inject(Player player) {
        if (broken || !settings.packetTap()) {
            return false;
        }
        try {
            Channel channel = channelOf(player);
            if (removeType == null) {
                Object handle = player.getClass().getMethod("getHandle").invoke(player);
                Class<?> type = Class.forName(REMOVE, true, handle.getClass().getClassLoader());
                newRemove = LOOKUP.findConstructor(type, MethodType.methodType(void.class, List.class));
                removedIds = LOOKUP.findVirtual(type, "profileIds", MethodType.methodType(List.class));
                removeType = type;
            }
            UUID viewer = player.getUniqueId();
            channel.eventLoop().execute(() -> {
                try {
                    if (channel.pipeline().get(HANDLER) != null) {
                        channel.pipeline().remove(HANDLER);
                    }
                    Handler handler = new Handler(viewer);
                    if (channel.pipeline().get(PACKET_HANDLER) != null) {
                        channel.pipeline().addBefore(PACKET_HANDLER, HANDLER, handler);
                    } else {
                        channel.pipeline().addLast(HANDLER, handler);
                    }
                } catch (RuntimeException failed) {
                    log.warn("Could not add the packet tap for {}: {}", player.getName(), failed.toString());
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            broken = true;
            log.warn("The packet tap does not fit this server version ({}); players the anti-ESP shield hides "
                    + "drop off tab lists while hidden.", failed.toString());
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
    public void unlist(Player viewer, UUID target) {
        MethodHandle make = newRemove;
        if (broken || make == null) {
            return;
        }
        try {
            Channel channel = channelOf(viewer);
            Object packet = make.invoke(List.of(target));
            channel.eventLoop().execute(() -> channel.writeAndFlush(packet, channel.voidPromise()));
        } catch (Throwable unexpected) {
            // At worst a stale tab entry until they relog.
        }
    }

    private static Channel channelOf(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = listener.getClass().getField("connection").get(listener);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    private final class Handler extends ChannelDuplexHandler {

        private final UUID viewer;

        Handler(UUID viewer) {
            this.viewer = viewer;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (msg.getClass() == removeType && droppedByShield(msg)) {
                    promise.setSuccess();
                    return;
                }
            } catch (Throwable unexpected) {
                // A tap that throws would take the player's connection with it.
            }
            super.write(ctx, msg, promise);
        }

        /** A tab-list removal caused only by the shield hiding these players: the client keeps them listed. */
        private boolean droppedByShield(Object msg) throws Throwable {
            EspShield current = shield;
            if (current == null) {
                return false;
            }
            List<?> ids = (List<?>) removedIds.invoke(msg);
            if (ids.isEmpty()) {
                return false;
            }
            for (Object id : ids) {
                if (!(id instanceof UUID uuid) || !current.hides(viewer, uuid)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void settings(ModerationSettings fresh) {
        settings(fresh.antiCheat());
    }

    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "keeping players the anti-ESP shield hides on tab lists";
    }
}
