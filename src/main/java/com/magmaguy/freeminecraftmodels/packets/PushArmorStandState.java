package com.magmaguy.freeminecraftmodels.packets;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PushArmorStandState {
    private PushArmorStandState() {
    }

    public static void push() {

    }

    public static void test(Player player, Location location) {
        PacketModelEntity packetArmorStandEntityInterface = NMSManager.getAdapter().createPacketDisplayEntity(player.getLocation());
        packetArmorStandEntityInterface.initializeModel(location, 1);
        packetArmorStandEntityInterface.displayTo(player);
    }

}
