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

    public boolean damage(Projectile projectile) {
        double damage = 0;

        if (projectile.getShooter() != null && projectile.getShooter().equals(modeledEntity.getUnderlyingEntity()))
            return false;

        if (!(projectile instanceof Arrow arrow)) return false;

        double speed = arrow.getVelocity().length();
        damage = Math.ceil(speed * arrow.getDamage());

        boolean piercing = false;

        if (arrow.getShooter() instanceof LivingEntity shooter) {
            ItemStack bow = null;
            try {
                bow = arrow.getWeapon();
            } catch (Exception e) {
                // getWeapon() failed, bow remains null
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

        if (projectile.getShooter() instanceof LivingEntity damager) {
            damage(damager, damage);
        } else {
            damage((Entity) projectile.getShooter(), damage);
        }
        modeledEntity.getSkeleton().tint();

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
