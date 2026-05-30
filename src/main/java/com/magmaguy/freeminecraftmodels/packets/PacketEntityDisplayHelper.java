package com.magmaguy.freeminecraftmodels.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketEntityInterface;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PacketEntityDisplayHelper {
    private static final Map<Class<?>, Method> playerDisplayMethodCache = new ConcurrentHashMap<>();
    private static final Method NO_PLAYER_DISPLAY_METHOD = getNoPlayerDisplayMethod();

    private PacketEntityDisplayHelper() {
    }

    public static void displayToPlayer(PacketEntityInterface packetEntity, Player player) {
        if (packetEntity == null || player == null || !player.isValid() || !player.isOnline()) return;

        Method displayMethod = playerDisplayMethodCache.computeIfAbsent(
                packetEntity.getClass(),
                PacketEntityDisplayHelper::findPlayerDisplayMethod
        );

        if (displayMethod != NO_PLAYER_DISPLAY_METHOD) {
            try {
                displayMethod.invoke(packetEntity, player);
                com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                        "PacketEntityDisplayHelper: invoked per-viewer displayTo(Player) on "
                                + packetEntity.getClass().getSimpleName()
                                + " for " + player.getName());
                return;
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                // Fall back to the UUID overload if a concrete implementation rejects the Player overload.
            }
        }

        // No per-viewer overload found OR it threw — fall back to the
        // broadcast UUID-keyed displayTo. For Bedrock viewers this is the
        // path that gets us "model spawns but rotates wrong" / "attachable
        // doesn't bind" symptoms because the broadcast path can't apply
        // per-platform packet tweaks (e.g. Bedrock Y-lift, Geyser-friendly
        // metadata sequencing). If you see this line firing for a Bedrock
        // player and models don't render, EMG's per-viewer overload is
        // either missing on that packet class or the cache populated from
        // an older EMG build — check getMethod search below.
        com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                "PacketEntityDisplayHelper: FALLBACK to UUID-broadcast displayTo on "
                        + packetEntity.getClass().getSimpleName()
                        + " for " + player.getName()
                        + " (per-viewer overload "
                        + (displayMethod == NO_PLAYER_DISPLAY_METHOD ? "NOT FOUND" : "threw on invoke")
                        + " — Bedrock-specific packet tweaks will not apply)");
        packetEntity.displayTo(player.getUniqueId());
    }

    private static Method findPlayerDisplayMethod(Class<?> packetEntityClass) {
        try {
            return packetEntityClass.getMethod("displayTo", Player.class);
        } catch (NoSuchMethodException e) {
            return NO_PLAYER_DISPLAY_METHOD;
        }
    }

    private static Method getNoPlayerDisplayMethod() {
        try {
            return PacketEntityDisplayHelper.class.getDeclaredMethod("missingDisplayToPlayer", Player.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Failed to initialize display helper sentinel.", e);
        }
    }

    @SuppressWarnings("unused")
    private static void missingDisplayToPlayer(Player player) {
    }
}
