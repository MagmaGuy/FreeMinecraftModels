package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * A modeled entity that visually replaces a player. The disguised
 * {@link Player} is held as a direct reference — this class does NOT set the
 * player as the {@code underlyingEntity}, so the modeled-entity-with-underlying
 * registry, packet interaction entity, and {@code RegisterModelEntity} PDC
 * tagging are all bypassed naturally (no special-casing needed in shared code).
 *
 * <p>Lifecycle is owned by {@link DisguiseManager}; instances should not be
 * created or destroyed directly outside the manager or the public API.
 */
public class PlayerDisguiseEntity extends ModeledEntity {
    @Getter
    private final Player disguisedPlayer;
    @Getter
    private final DisguiseAnimationController animationController;
    // Tracks whether THIS disguise applied the invisibility potion effect, so
    // we only strip it on remove() if we were the source. Avoids clobbering an
    // invisibility effect the player had from another source (existing potion,
    // /effect, another plugin) when the disguise ends.
    private boolean appliedInvisibilityEffect = false;

    private PlayerDisguiseEntity(String entityID, Player disguisedPlayer) {
        super(entityID, disguisedPlayer.getLocation());
        this.disguisedPlayer = disguisedPlayer;
        // Decorative model: every interaction path must be a no-op so attacks
        // pass through to the underlying player as if undisguised. The
        // default left/right-click handlers in InteractionComponent would
        // otherwise either try to attack the (null) underlying entity, or
        // mount the player onto the disguise model.
        setLeftClickCallback((player, entity) -> {});
        setRightClickCallback((player, entity) -> {});
        // hitbox-contact and projectile callbacks are null by default — the
        // null check at HitboxComponent.tick line 61 + 145 in
        // InteractionComponent skips dispatch entirely, so no override
        // needed. Keep them null.
        this.animationController = new DisguiseAnimationController(this);
    }

    /**
     * Disguises must not spawn a packet interaction entity — the model is
     * decorative and clicks should pass through to the underlying player.
     * Override skips the {@code createPacketInteractionEntity()} call that
     * the base class makes.
     */
    @Override
    protected void displayInitializer() {
        getSkeleton().generateDisplays();
    }

    /**
     * Factory used by {@link DisguiseManager}. Returns {@code null} if the
     * model ID is not loaded.
     *
     * <p>Package-private — only callable via {@link DisguiseManager}.
     */
    @Nullable
    static PlayerDisguiseEntity create(String entityID, Player player) {
        FileModelConverter converter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (converter == null) return null;
        PlayerDisguiseEntity entity = new PlayerDisguiseEntity(entityID, player);
        // Spawn at the player's location, with NO underlying entity. The
        // Location-only spawn path skips setUnderlyingEntity, which is what
        // we want — the player must not be wired into
        // loadedModeledEntitiesWithUnderlyingEntities or get the
        // RegisterModelEntity PDC tag.
        entity.spawn(player.getLocation());
        // setVisibleByDefault(false) covers all current AND future viewers
        // via a single Paper API call — no need to iterate online players
        // or listen for joins/world-changes. The previous prototype's
        // per-viewer hideEntity loop only covered same-world players online
        // at disguise time, which is why yongzhun reported the disguise
        // "doesn't display properly".
        player.setVisibleByDefault(false);
        // Bedrock fallback: setVisibleByDefault doesn't reliably hide the
        // player from Bedrock viewers (Geyser's EntityCache / sendPlayer
        // paths can re-materialize the player independent of Java's tracker
        // state). An invisibility potion effect propagates cleanly through
        // Geyser's metadata translation and hides the player on both Java
        // and Bedrock. Only apply if the player doesn't already have it from
        // another source — otherwise remove() would strip a non-ours effect.
        if (!player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY,
                    PotionEffect.INFINITE_DURATION,
                    0,
                    false,  // ambient
                    false,  // particles
                    false   // icon
            ));
            entity.appliedInvisibilityEffect = true;
        }
        // Armor / handheld items are rendered independently of the player
        // body — Minecraft keeps drawing them even when the entity is
        // invisible (vanilla quirk: invisibility potion hides skin but
        // not equipment; setVisibleByDefault hides the whole entity from
        // the tracker but Geyser still echoes equipment to Bedrock from
        // its own cache). Without this they clip through the disguise
        // model. Hide all six equipment slots from every current viewer
        // here; future viewers are handled by the join/world-change
        // listeners in DisguiseEquipmentListener.
        entity.broadcastHiddenEquipment();
        return entity;
    }

    /**
     * Empty-slot map used for {@link Player#sendEquipmentChange(org.bukkit.entity.LivingEntity, Map)}.
     * AIR / null are treated as "no item" by the underlying packet, which
     * is what we want for the disguised player's six visible slots. BODY
     * is omitted on purpose — it's the wolf-armor slot and doesn't apply
     * to players (sending it would be a no-op but pollutes the packet).
     */
    private static Map<EquipmentSlot, ItemStack> emptyEquipmentMap() {
        Map<EquipmentSlot, ItemStack> map = new EnumMap<>(EquipmentSlot.class);
        ItemStack air = new ItemStack(Material.AIR);
        map.put(EquipmentSlot.HAND, air);
        map.put(EquipmentSlot.OFF_HAND, air);
        map.put(EquipmentSlot.HEAD, air);
        map.put(EquipmentSlot.CHEST, air);
        map.put(EquipmentSlot.LEGS, air);
        map.put(EquipmentSlot.FEET, air);
        return map;
    }

    /**
     * Builds a snapshot of the player's CURRENT equipment in all six
     * visible slots. Used when restoring real equipment to viewers on
     * undisguise — we have to ship the actual items, not just "tell the
     * client to refresh", because the previous {@code sendEquipmentChange}
     * overrode the client's notion of what's worn.
     */
    private Map<EquipmentSlot, ItemStack> realEquipmentMap() {
        Map<EquipmentSlot, ItemStack> map = new EnumMap<>(EquipmentSlot.class);
        PlayerInventory inv = disguisedPlayer.getInventory();
        map.put(EquipmentSlot.HAND, safeCopy(inv.getItemInMainHand()));
        map.put(EquipmentSlot.OFF_HAND, safeCopy(inv.getItemInOffHand()));
        map.put(EquipmentSlot.HEAD, safeCopy(inv.getHelmet()));
        map.put(EquipmentSlot.CHEST, safeCopy(inv.getChestplate()));
        map.put(EquipmentSlot.LEGS, safeCopy(inv.getLeggings()));
        map.put(EquipmentSlot.FEET, safeCopy(inv.getBoots()));
        return map;
    }

    private static ItemStack safeCopy(@Nullable ItemStack stack) {
        return stack == null ? new ItemStack(Material.AIR) : stack.clone();
    }

    /**
     * Sends an empty-equipment override to {@code viewer} so the disguised
     * player's armor / held items don't render on the viewer's screen.
     * Self-view excluded: the disguised player still needs to see their
     * own armor in the inventory UI / first-person hand, and viewer ==
     * disguised player is the third-person F5 case which we leave alone
     * (the player's own client decides whether to show their body via
     * the visibility flag anyway).
     */
    public void hideEquipmentFor(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return;
        if (viewer.getUniqueId().equals(disguisedPlayer.getUniqueId())) return;
        if (!disguisedPlayer.isOnline()) return;
        viewer.sendEquipmentChange(disguisedPlayer, emptyEquipmentMap());
    }

    /**
     * Re-sends the disguised player's actual equipment to {@code viewer}
     * so the client picks up what they're really wearing. Called from
     * {@link #remove()} for each viewer; vanilla doesn't push an
     * equipment refresh on its own because, as far as the server's entity
     * tracker is concerned, nothing changed.
     */
    public void restoreEquipmentFor(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return;
        if (viewer.getUniqueId().equals(disguisedPlayer.getUniqueId())) return;
        if (!disguisedPlayer.isOnline()) return;
        viewer.sendEquipmentChange(disguisedPlayer, realEquipmentMap());
    }

    /**
     * Iterates every online viewer (we don't filter by world — the
     * tracker handles distance / world culling, and a stray packet for an
     * entity the viewer can't see is a no-op on the client) and sends
     * the empty-equipment override. Must run on the main thread:
     * {@code sendEquipmentChange} is not async-safe on Spigot.
     */
    private void broadcastHiddenEquipment() {
        Runnable task = () -> {
            if (!disguisedPlayer.isOnline()) return;
            Map<EquipmentSlot, ItemStack> empty = emptyEquipmentMap();
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(disguisedPlayer.getUniqueId())) continue;
                viewer.sendEquipmentChange(disguisedPlayer, empty);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, task);
        }
    }

    /**
     * Inverse of {@link #broadcastHiddenEquipment()} — called from
     * {@link #remove()}. Snapshot the player's real equipment ONCE before
     * scheduling the sync task, because by the time the task runs (next
     * tick) the disguise has already been torn down and {@code remove()}
     * has returned; we want the equipment state at the moment of
     * undisguise.
     */
    private void broadcastRestoredEquipment() {
        if (!disguisedPlayer.isOnline()) return;
        final Map<EquipmentSlot, ItemStack> snapshot = realEquipmentMap();
        Runnable task = () -> {
            if (!disguisedPlayer.isOnline()) return;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(disguisedPlayer.getUniqueId())) continue;
                viewer.sendEquipmentChange(disguisedPlayer, snapshot);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, task);
        }
    }

    @Override
    public void remove() {
        if (disguisedPlayer.isOnline()) {
            disguisedPlayer.setVisibleByDefault(true);
            if (appliedInvisibilityEffect) {
                disguisedPlayer.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
            // Push the real equipment back to every viewer. Without this,
            // clients keep the empty-slot override we sent on disguise
            // creation until the player triggers a fresh equipment update
            // (e.g. swaps items), which leaves the player visually naked
            // post-undisguise.
            broadcastRestoredEquipment();
        }
        super.remove();
    }

    /**
     * Re-applies the invisibility potion effect if this disguise originally
     * applied it. Called from a PlayerRespawnEvent listener — vanilla Minecraft
     * clears all potion effects on death, so a disguised player who died would
     * respawn visible (the disguise itself persists per design, but the
     * invisibility is gone). No-op if this disguise didn't add the effect in
     * the first place (e.g. the player already had it from another source).
     */
    public void reapplyInvisibilityIfOurs() {
        if (!appliedInvisibilityEffect) return;
        if (!disguisedPlayer.isOnline()) return;
        if (disguisedPlayer.hasPotionEffect(PotionEffectType.INVISIBILITY)) return;
        disguisedPlayer.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                PotionEffect.INFINITE_DURATION,
                0,
                false,  // ambient
                false,  // particles
                false   // icon
        ));
    }

    @Override
    public Location getLocation() {
        if (disguisedPlayer.isOnline()) {
            return disguisedPlayer.getLocation();
        }
        return null;
    }

    @Override
    public void tick(AbstractPacketBundle bundle) {
        if (!disguisedPlayer.isOnline()) {
            // Liveness check: drop the disguise cleanly if the player object
            // became invalid mid-tick (e.g. kicked between the quit event
            // firing and the next async tick). Per design Q6 the only
            // auto-undisguise trigger is logout — death, damage, and world
            // change all preserve the disguise, so we deliberately do NOT
            // check isDead() here.
            DisguiseManager.undisguise(disguisedPlayer);
            return;
        }

        // Player.getEyeLocation() is safe to call async — it returns a copy
        // of the cached state, same pattern as DynamicEntity.syncSkeletonWithEntity.
        Location eye = disguisedPlayer.getEyeLocation();
        getSkeleton().setCurrentHeadPitch(eye.getPitch());
        getSkeleton().setCurrentHeadYaw(eye.getYaw());
        animationController.tick();
        super.tick(bundle);
    }
}
