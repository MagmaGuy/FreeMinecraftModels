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
                return;
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                // Fall back to the UUID overload if a concrete implementation rejects the Player overload.
            }
        }

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
