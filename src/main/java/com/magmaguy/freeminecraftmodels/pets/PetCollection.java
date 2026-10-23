package com.magmaguy.freeminecraftmodels.pets;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player's pets, stored as a JSON list in the player's own persistent data container, so they travel with the
 * player's save and need no database. Only read and written on the server thread while the player is online.
 */
public final class PetCollection {
    private static final Gson GSON = new Gson();
    private static final Type LIST = new TypeToken<List<PetRecord>>() {
    }.getType();
    private static NamespacedKey key;

    private final Player player;
    private final List<PetRecord> pets;

    private PetCollection(Player player, List<PetRecord> pets) {
        this.player = player;
        this.pets = pets;
    }

    private static NamespacedKey key() {
        if (key == null) key = new NamespacedKey(MetadataHandler.PLUGIN, "pets");
        return key;
    }

    public static PetCollection load(Player player) {
        String json = player.getPersistentDataContainer().get(key(), PersistentDataType.STRING);
        List<PetRecord> pets = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                List<PetRecord> stored = GSON.fromJson(json, LIST);
                if (stored != null) for (PetRecord record : stored) if (record != null && record.id != null) pets.add(record);
            } catch (RuntimeException failure) {
                Logger.warn("Could not read the pets of " + player.getName() + ": " + failure.getMessage());
            }
        }
        return new PetCollection(player, pets);
    }

    public Player player() {
        return player;
    }

    public List<PetRecord> pets() {
        return pets;
    }

    public PetRecord find(UUID id) {
        for (PetRecord record : pets) if (record.uuid().equals(id)) return record;
        return null;
    }

    public List<PetRecord> equipped() {
        List<PetRecord> equipped = new ArrayList<>();
        for (PetRecord record : pets) if (record.equipped) equipped.add(record);
        return equipped;
    }

    public void add(PetRecord record) {
        pets.add(record);
        save();
    }

    public void remove(PetRecord record) {
        pets.removeIf(other -> other.id.equals(record.id));
        save();
    }

    public void save() {
        if (pets.isEmpty()) player.getPersistentDataContainer().remove(key());
        else player.getPersistentDataContainer().set(key(), PersistentDataType.STRING, GSON.toJson(pets, LIST));
    }
}
