package com.magmaguy.freeminecraftmodels.customentity;

import org.bukkit.event.Listener;

public class ModeledEntityEvents implements Listener {
    //todo: this solution was actually just planned for static models, and isn't functional yet, dynamic models can wander
//    private static final ArrayListMultimap<Integer, ModeledEntity> loadedModeledEntities = ArrayListMultimap.create();
//    private static final ArrayListMultimap<Integer, ModeledEntity> unloadedModeledEntities = ArrayListMultimap.create();
//
//    public static void addLoadedModeledEntity(ModeledEntity modeledEntity) {
//        loadedModeledEntities.put(modeledEntity.getChunkHash(), modeledEntity);
//    }
//
//    public static void addUnloadedModeledEntity(ModeledEntity modeledEntity) {
//        unloadedModeledEntities.put(modeledEntity.getChunkHash(), modeledEntity);
//    }
//
//    public static void removeLoadedModeledEntity(ModeledEntity modeledEntity) {
//        loadedModeledEntities.remove(modeledEntity.getChunkHash(), modeledEntity);
//    }
//    public static void removeUnloadedModeledEntity(ModeledEntity modeledEntity){
//        unloadedModeledEntities.remove(modeledEntity.getChunkHash(), modeledEntity);
//    }
//
//    @EventHandler (ignoreCancelled = true, priority = EventPriority.HIGHEST)
//    public void ChunkLoadEvent(ChunkLoadEvent event){
//        int chunkHash = ChunkHasher.hash(event.getChunk());
//        List<ModeledEntity> modeledEntities = unloadedModeledEntities.get(chunkHash);
//        if (modeledEntities == null) return;
//        unloadedModeledEntities.removeAll(chunkHash);
//        modeledEntities.forEach(ModeledEntity::loadChunk);
//        loadedModeledEntities.putAll(chunkHash, modeledEntities);
//    }
//
//    @EventHandler (ignoreCancelled = true, priority = EventPriority.HIGHEST)
//    public void ChunkUnloadEvent (ChunkUnloadEvent event) {
//        int chunkHash = ChunkHasher.hash(event.getChunk());
//        loadedModeledEntities.values().forEach(modeledEntity->{
//            if (modeledEntity.chunkHash != null && chunkHash == modeledEntity.chunkHash) {
//                modeledEntity.unloadChunk();
//                loadedModeledEntities.remove(chunkHash, modeledEntity);
//                unloadedModeledEntities.put(chunkHash, modeledEntity);
//            }
//        });
//    }
}
