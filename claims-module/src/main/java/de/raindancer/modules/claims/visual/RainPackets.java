package de.raindancer.modules.claims.visual;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends the rain and thunder <em>intensity</em> to a single player.
 * <p>
 * This is the one place in the plugin that reaches past the Bukkit API, and only because the API cannot
 * express it. {@code Player#setPlayerWeather} sends {@code START_RAINING}, which tells the client it is
 * raining — but the client then sets its rain gradient to zero and waits for a {@code RAIN_LEVEL_CHANGE}
 * packet to raise it. That packet is only ever broadcast from the world's own weather, so on a clear
 * world the override produces rain at strength zero: raining, invisibly. There is no per-player rain
 * level anywhere in Bukkit or Paper.
 * <p>
 * Everything is resolved reflectively once, against Mojang mappings (Paper ships those at runtime since
 * 1.20.5). If any part is missing — a future version renames something, a fork differs — the class
 * quietly reports itself unavailable and the caller falls back to plain {@code setPlayerWeather}. It
 * never throws into a scheduler tick.
 */
public final class RainPackets {

    private static volatile boolean resolved;
    private static volatile boolean available;

    private static Constructor<?> packetConstructor;
    private static Object rainLevelChange;
    private static Object thunderLevelChange;
    private static Method getHandle;
    private static Field connectionField;
    private static Method sendMethod;

    private RainPackets() {
    }

    /** Resolves the reflection targets once; safe to call from any thread. */
    public static synchronized boolean available(Logger logger) {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> packetClass =
                    Class.forName("net.minecraft.network.protocol.game.ClientboundGameEventPacket");
            Class<?> typeClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundGameEventPacket$Type");

            packetConstructor = packetClass.getConstructor(typeClass, float.class);
            rainLevelChange = packetClass.getField("RAIN_LEVEL_CHANGE").get(null);
            thunderLevelChange = packetClass.getField("THUNDER_LEVEL_CHANGE").get(null);

            // CraftPlayer#getHandle is on the implementation class, not on the interface.
            getHandle = Class.forName(craftClassName("entity.CraftPlayer")).getMethod("getHandle");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            connectionField = serverPlayer.getField("connection");
            // send() lives on the common listener, which the game listener extends.
            sendMethod = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl")
                    .getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));

            available = true;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            available = false;
            logger.log(Level.WARNING, "Per-player rain intensity is not available on this server "
                    + "build, so claim weather will change the sky but not show falling rain. "
                    + "Everything else keeps working.", unavailable);
        }
        return available;
    }

    private static String craftClassName(String suffix) throws ClassNotFoundException {
        // org.bukkit.craftbukkit.CraftServer on modern Paper; older builds carry a version package.
        Class<?> serverClass = org.bukkit.Bukkit.getServer().getClass();
        String packageName = serverClass.getPackage().getName();
        return packageName + "." + suffix;
    }

    /**
     * Pushes rain and thunder strength to one player.
     *
     * @param rain    0 for dry, 1 for a downpour
     * @param thunder 0 for none, 1 for a full storm sky
     * @return whether the packets went out
     */
    public static boolean send(Player player, float rain, float thunder, Logger logger) {
        if (!available(logger)) {
            return false;
        }
        try {
            Object handle = getHandle.invoke(player);
            Object connection = connectionField.get(handle);
            sendMethod.invoke(connection,
                    packetConstructor.newInstance(rainLevelChange, clamp(rain)));
            sendMethod.invoke(connection,
                    packetConstructor.newInstance(thunderLevelChange, clamp(thunder)));
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // One bad send must not kill the ambience loop; stop trying rather than spam the log.
            available = false;
            logger.log(Level.WARNING, "Sending per-player rain failed; falling back to plain weather.",
                    failure);
            return false;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
