package com.magmaguy.freeminecraftmodels.thirdparty;

import org.bukkit.entity.Player;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

public class Geyser {

    public static boolean isBedrock(Player player) {
        GeyserConnection geyserConnection = GeyserApi.api().connectionByUuid(player.getUniqueId());
        return geyserConnection != null;
    }
}
