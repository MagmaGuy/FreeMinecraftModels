package com.magmaguy.freeminecraftmodels.customentity;

import com.google.common.collect.ArrayListMultimap;
import com.magmaguy.freeminecraftmodels.utils.ChunkHasher;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.List;

public class ModeledEntityEvents implements Listener {
    private static ArrayListMultimap<Integer, ModeledEntity> loadedModeledEntities = ArrayListMultimap.create();
    private static ArrayListMultimap<Integer, ModeledEntity> unloadedModeledEntities = ArrayListMultimap.create();

    public static void addLoadedModeledEntity(ModeledEntity modeledEntity){
        loadedModeledEntities.put(modeledEntity.getChunkHash(), modeledEntity);
    }

    public static void addUnloadedModeledEntity(ModeledEntity modeledEntity){
        unloadedModeledEntities.put(modeledEntity.getChunkHash(), modeledEntity);
    }

    public static void removeLoadedModeledEntity(ModeledEntity modeledEntity){
        loadedModeledEntities.remove(modeledEntity.getChunkHash(), modeledEntity);
    }
    public static void removeUnloadedModeledEntity(ModeledEntity modeledEntity){
        unloadedModeledEntities.remove(modeledEntity.getChunkHash(), modeledEntity);
    }

    @EventHandler (ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void ChunkLoadEvent(ChunkLoadEvent event){
        int chunkHash = ChunkHasher.hash(event.getChunk());
        List<ModeledEntity> modeledEntities = unloadedModeledEntities.get(chunkHash);
        if (modeledEntities == null) return;
        unloadedModeledEntities.removeAll(chunkHash);
        modeledEntities.forEach(ModeledEntity::loadChunk);
        loadedModeledEntities.putAll(chunkHash, modeledEntities);
    }

    @EventHandler (ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void ChunkUnloadEvent (ChunkUnloadEvent event) {
        int chunkHash = ChunkHasher.hash(event.getChunk());
        loadedModeledEntities.values().forEach(modeledEntity->{
            if (modeledEntity.chunkHash != null && chunkHash == modeledEntity.chunkHash) {
                modeledEntity.unloadChunk();
                loadedModeledEntities.put(chunkHash, modeledEntity);
                unloadedModeledEntities.remove(chunkHash, modeledEntity);
            }
        });
    }
}
