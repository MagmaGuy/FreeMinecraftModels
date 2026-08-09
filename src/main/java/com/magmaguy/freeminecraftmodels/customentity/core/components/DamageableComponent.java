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

    // Deliberately does not tint: every public damage entry point tints exactly once
    // itself, so tinting here double-fired the flash for non-living entities.
    private void handleNonLivingEntityDamage(double amount) {
        internalHealth -= amount;
        if (internalHealth <= 0) {
            modeledEntity.removeWithDeathAnimation();
        }
    }

    public void damage(double amount) {
        if (modeledEntity.getUnderlyingEntity() instanceof LivingEntity livingEntity) {
            OBBHitDetection.applyDamage = true;
            try {
                livingEntity.damage(amount);
            } finally {
                OBBHitDetection.applyDamage = false;
            }
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
            OBBHitDetection.applyDamage = true;
            try {
                damagerLivingEntity.attack(livingEntity);
            } finally {
                OBBHitDetection.applyDamage = false;
            }
        } else {
            handleNonLivingEntityDamage(1);
        }
        modeledEntity.getSkeleton().tint();
    }

    public boolean damage(Projectile projectile) {
        if (projectile.getShooter() != null && projectile.getShooter().equals(modeledEntity.getUnderlyingEntity()))
            return false;

        if (!(projectile instanceof AbstractArrow arrow)) return false;

        double speed = arrow.getVelocity().length();
        double damage = Math.max(1.0, Math.ceil(speed * arrow.getDamage()));

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

        }

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
            // damage(Entity, double) already tints once — no extra tint here.
            damage((Entity) projectile, damage);
        } finally {
            OBBHitDetection.applyDamage = false;
            OBBHitDetection.bypassProjectileRedirect = false;
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
            try {
                underlyingLivingEntity.attack(target);
            } finally {
                OBBHitDetection.applyDamage = false;
            }
        } else {
            OBBHitDetection.applyDamage = true;
            try {
                target.damage(2, modeledEntity.getUnderlyingEntity());
            } finally {
                OBBHitDetection.applyDamage = false;
            }
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
        try {
            target.damage(damage, modeledEntity.getUnderlyingEntity());
        } finally {
            OBBHitDetection.applyDamage = false;
        }
    }

}
