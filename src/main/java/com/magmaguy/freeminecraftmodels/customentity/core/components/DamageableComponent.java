package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.magmacore.util.AttributeManager;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

/**
 * Represents a component that can handle damage interactions, including applying damage from various sources
 * and managing the internal health of an associated modeled entity. This component is responsible for processing
 * both custom damage and Minecraft's native damage system, depending on the context and the underlying entity type.
 */
public class DamageableComponent {

    private final ModeledEntity modeledEntity;
    /**
     * Indicates whether the entity represented by this component is internally considered immortal.
     * When set to {@code true}, the entity will not experience health reduction or death through the
     * internal mechanics, regardless of damage dealt programmatically. This is typically used for
     * entities that should not be affected by players trying to punch it
     */
    @Getter
    private final boolean internallyImmortal = false;
    /**
     * Represents the internal health of an entity. This value is used to track the entity's health
     * internally when the entity is either not natively managed by the underlying system or is
     * marked as internally mortal. It serves as a fallback health mechanic
     */
    @Getter
    @Setter
    private double internalHealth = 1;

    public DamageableComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    private void handleNonLivingEntityDamage(double amount) {
        if (!internallyImmortal) {
            internalHealth -= amount;
            if (internalHealth <= 0) {
                modeledEntity.removeWithDeathAnimation();
            }
        }
        modeledEntity.getSkeleton().tint();
    }

    public void damage(double amount) {
        if (modeledEntity.getUnderlyingEntity() instanceof LivingEntity livingEntity) {
            OBBHitDetection.applyDamage = true;
            livingEntity.damage(amount);
            OBBHitDetection.applyDamage = false;
        } else {
            handleNonLivingEntityDamage(amount);
        }
        modeledEntity.getSkeleton().tint();
    }

    public void damage(Entity damager, double amount) {
        if (modeledEntity.getUnderlyingEntity() instanceof LivingEntity livingEntity) {
            livingEntity.damage(amount, damager);
        } else {
            handleNonLivingEntityDamage(amount);
        }
        modeledEntity.getSkeleton().tint();
    }

    public void damage(Entity damager) {
        if (modeledEntity.getUnderlyingEntity() instanceof LivingEntity livingEntity &&
                !livingEntity.getType().equals(EntityType.ARMOR_STAND) &&
                damager instanceof LivingEntity damagerLivingEntity) {
            damagerLivingEntity.attack(livingEntity);
        } else {
            handleNonLivingEntityDamage(1);
        }
        modeledEntity.getSkeleton().tint();
    }

    // Dedup: an arrow can reach here from both the OBB sweep and the vanilla-hitbox
    // redirect in the same tick (before arrow.remove() lands). Apply each projectile to
    // a modeled entity at most once, wiped after 1s — a live arrow shouldn't still be
    // colliding past that, and a returning trident re-thrown later still registers.
    private static final java.util.Set<java.util.UUID> recentProjectileHits =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean damage(Projectile projectile) {
        if (projectile.getShooter() != null && projectile.getShooter().equals(modeledEntity.getUnderlyingEntity()))
            return false;

        if (!(projectile instanceof AbstractArrow arrow)) return false;

        if (!recentProjectileHits.add(projectile.getUniqueId())) return false;
        final java.util.UUID dedupId = projectile.getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                com.magmaguy.freeminecraftmodels.MetadataHandler.PLUGIN,
                () -> recentProjectileHits.remove(dedupId), 20L);

        double speed = arrow.getVelocity().length();
        double damage = Math.max(1.0, Math.ceil(speed * arrow.getDamage()));

        boolean piercing = false;

        if (arrow.getShooter() instanceof LivingEntity shooter) {
            ItemStack bow = null;
            try {
                bow = arrow.getWeapon();
            } catch (Exception | NoSuchMethodError e) {
                // getWeapon() failed or is unavailable on this server API, bow remains null
            }

            if (bow != null && bow.containsEnchantment(Enchantment.POWER)) {
                int level = bow.getEnchantmentLevel(Enchantment.POWER);
                double bonus = Math.ceil(0.25 * (level + 1) * damage);
                damage += bonus;
            }

            if (bow != null && bow.containsEnchantment(Enchantment.PIERCING)) {
                piercing = true;
            }
        }

        double hpBefore = modeledEntity.getUnderlyingEntity() instanceof LivingEntity le ? le.getHealth() : -1;
        if (OBBHitDetection.DEBUG_PROJECTILE_HITS)
            com.magmaguy.magmacore.util.Logger.warn("[FMM-ProjTrace] DamageableComponent.damage(Projectile)"
                    + " arrow=" + projectile.getUniqueId()
                    + " speed=" + String.format("%.4f", speed)
                    + " fmmDamageInput=" + String.format("%.2f", damage)
                    + " dealingAs=ARROW(cause=PROJECTILE) shooter="
                    + (projectile.getShooter() instanceof Entity e ? e.getType() : "null")
                    + " hpBefore=" + String.format("%.2f", hpBefore));

        // Deal the hit AS the arrow, not its shooter, so the resulting
        // EntityDamageByEntityEvent carries cause=PROJECTILE. Passing the shooter (a
        // player) made Bukkit classify the hit as ENTITY_ATTACK (melee); combat plugins
        // such as EliteMobs then ran their MELEE damage formula using the player's
        // mainhand — which for an archer is a bow, treated as an unarmed weapon —
        // collapsing every arrow hit on a modeled mob to a tiny (~0.17) value. The arrow
        // still resolves back to its shooter downstream for kill credit and skill XP.
        //
        // Protect this redirected hit from FMM's OWN EntityDamageByEntityEvent listeners:
        // applyDamage stops the LOWEST-priority cancel, bypassProjectileRedirect stops the
        // HIGHEST-priority re-redirect (which now fires because the damager is a projectile).
        // The default handleModeledEntityHitByProjectileEvent path already sets both; this
        // DynamicEntity callback path did not, so the redirected PROJECTILE event was being
        // cancelled/re-routed before any combat plugin could see it (no damage, no popup).
        OBBHitDetection.applyDamage = true;
        OBBHitDetection.bypassProjectileRedirect = true;
        try {
            damage((Entity) projectile, damage);
        } finally {
            OBBHitDetection.applyDamage = false;
            OBBHitDetection.bypassProjectileRedirect = false;
        }
        modeledEntity.getSkeleton().tint();

        if (OBBHitDetection.DEBUG_PROJECTILE_HITS && hpBefore >= 0
                && modeledEntity.getUnderlyingEntity() instanceof LivingEntity le2)
            com.magmaguy.magmacore.util.Logger.warn("[FMM-ProjTrace] APPLIED hpBefore="
                    + String.format("%.2f", hpBefore) + " hpAfter=" + String.format("%.2f", le2.getHealth())
                    + " actualApplied=" + String.format("%.4f", hpBefore - le2.getHealth())
                    + " §8(real HP lost after EliteMobs' formula override)");

        if (!piercing) {
            arrow.remove();
        }

        return true;
    }

    /**
     * This is the preferred way to attack a living entity if you have a living entity as the underlying entity.
     * It will simulate a real attack using Minecraft's damage system. If you do not have a living entity as the underlying entity,
     * it will default to doing 1 damage to the target, and at that point, you should use {@link #attack(LivingEntity, double)} instead.
     */
    public void attack(LivingEntity target) {
        Attribute attribute = AttributeManager.getAttribute("generic_attack_damage");
        if (modeledEntity.getUnderlyingEntity() instanceof LivingEntity underlyingLivingEntity &&
                attribute != null &&
                underlyingLivingEntity.getAttribute(attribute) != null) {
            OBBHitDetection.applyDamage = true;
            underlyingLivingEntity.attack(target);
            OBBHitDetection.applyDamage = false;
        } else {
            OBBHitDetection.applyDamage = true;
            target.damage(2, modeledEntity.getUnderlyingEntity());
            OBBHitDetection.applyDamage = false;
        }
    }

    /**
     * Beware, this damage uses custom damage which might get reduced somewhat randomly by Minecraft, so test it before using it.
     * Preferably, you should use {@link #attack(LivingEntity)} instead, and if you need to modify the damage, you can
     * hijack the damage event and modify the damage amount.
     *
     * @param target Target to attack
     * @param damage Damage to deal
     */
    public void attack(LivingEntity target, double damage) {
        OBBHitDetection.applyDamage = true;
        target.damage(damage, modeledEntity.getUnderlyingEntity());
        OBBHitDetection.applyDamage = false;
    }


}
