package com.magmaguy.freeminecraftmodels.api.magic;

import com.magmaguy.freeminecraftmodels.magic.MagicAttackBehaviorRegistry;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Registry of custom staff and wand attacks supplied by other plugins.
 *
 * <p>A behavior replaces one built-in attack of the weapons it is bound to. The weapon keeps its
 * family, its inputs, its reload and its place in the progression integration registered through
 * {@link MagicWeaponAPI#registerResolver}: a behavior never replaces that integration. It reports
 * its hits with {@link MagicAttackCast#impact}, and FreeMinecraftModels applies them exactly as it
 * applies a built-in hit.</p>
 *
 * <p>A weapon is bound in one of two ways. Its item file can name the behavior:</p>
 * <pre>
 * weapon:
 *   type: WAND
 *   attacks:
 *     WAND_MISSILE:
 *       basePower: 0.672
 *       reloadTicks: 11
 *       behavior: myaddon:piercing_ray
 * </pre>
 * <p>or the plugin can call {@link #bind}. A behavior named in the item file wins over a Java
 * binding. While the named behavior is not registered, the weapon casts its built-in attack and
 * the console says so once, so a server without the addon keeps working.</p>
 *
 * <p>Inputs follow the attack the behavior stands in for: {@link MagicAttackKind#WAND_MISSILE} on a
 * wand's left click, {@link MagicAttackKind#STAFF_FIREBALL} on a staff's right click, both in air,
 * on a block or on an entity. A behavior standing in for {@link MagicAttackKind#STAFF_MELEE} runs on
 * a staff's left click on an entity and, unlike the built-in strike, on a left click in air or on a
 * block too.</p>
 *
 * <p>Register from {@code onEnable}; FreeMinecraftModels does not need to have finished loading.
 * Everything an owner registered is removed when its plugin disables. Call these methods on the
 * server thread.</p>
 *
 * <p>Example: a straight, non-homing ray that hits the first entity in its path and can be cast
 * into empty space.</p>
 * <pre>{@code
 * public final class RayAddon extends JavaPlugin {
 *     public void onEnable() {
 *         NamespacedKey id = new NamespacedKey(this, "piercing_ray");
 *         MagicAttackBehaviors.register(this, id, MagicAttackKind.WAND_MISSILE, cast -> {
 *             Player caster = cast.caster();
 *             Location eye = caster.getEyeLocation();
 *             Vector direction = eye.getDirection();
 *             RayTraceResult hit = caster.getWorld().rayTrace(eye, direction, cast.range(),
 *                     FluidCollisionMode.NEVER, true, 0.25,
 *                     entity -> entity instanceof LivingEntity living && cast.isTargetEligible(living));
 *             double length = hit == null ? cast.range() : hit.getHitPosition().distance(eye.toVector());
 *             for (double step = 1; step < length; step += 0.5)
 *                 caster.getWorld().spawnParticle(Particle.END_ROD,
 *                         eye.clone().add(direction.clone().multiply(step)), 1, 0, 0, 0, 0);
 *             if (hit != null && hit.getHitEntity() instanceof LivingEntity target)
 *                 cast.impact(target, 1.0);
 *             return true; // cast, hit or miss: the reload starts
 *         });
 *         // Or name it in the weapon's item file: weapon.attacks.WAND_MISSILE.behavior
 *         MagicAttackBehaviors.bind(this, "my_ray_wand", id);
 *     }
 * }
 * }</pre>
 * <p>An attack that lasts longer than the click (a projectile, a delayed mark) schedules its steps
 * with {@link MagicAttackCast#runTimer} or {@link MagicAttackCast#runLater}, removes its visuals in
 * {@link MagicAttackCast#onEnd} and calls {@link MagicAttackCast#end} when it is done.</p>
 *
 * <p>New in capability {@value MagicWeaponAPI#ATTACK_BEHAVIORS_CAPABILITY}: check
 * {@code MagicWeaponAPI.capabilityVersion() >= MagicWeaponAPI.ATTACK_BEHAVIORS_CAPABILITY} before
 * linking against this class on servers that may run an older FreeMinecraftModels. The contract is
 * meant to stay stable.</p>
 */
public final class MagicAttackBehaviors {

    private MagicAttackBehaviors() {
    }

    /**
     * Registers a behavior that stands in for one built-in attack.
     *
     * @param owner the enabled plugin that owns the behavior
     * @param id the behavior's id; its namespace must be the owner's, as
     * {@code new NamespacedKey(owner, "name")} produces
     * @param standsInFor the built-in attack whose input, reload and progression rules the behavior keeps
     * @return false when the owner is not enabled or another enabled plugin holds the id.
     * Registering an id again from the same owner replaces the behavior and ends its running casts.
     * @throws IllegalArgumentException when the id's namespace is not the owner's
     */
    public static boolean register(Plugin owner, NamespacedKey id, MagicAttackKind standsInFor,
                                   MagicAttackBehavior behavior) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(standsInFor, "standsInFor");
        Objects.requireNonNull(behavior, "behavior");
        return MagicAttackBehaviorRegistry.shared().register(owner, id, standsInFor, behavior);
    }

    /**
     * Binds one of the owner's registered behaviors to a weapon definition, in place of the
     * built-in attack the behavior stands in for. The weapon does not have to be loaded yet and
     * the binding survives content reloads. It has no effect on a weapon of the other family or
     * on an attack whose item file names a behavior.
     *
     * @param weaponId the weapon definition's id: its item file name without the extension, with
     * or without the {@code freeminecraftmodels:} prefix
     * @return false when the owner has not registered the id, or another enabled plugin already
     * bound that attack of that weapon
     */
    public static boolean bind(Plugin owner, String weaponId, NamespacedKey id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(weaponId, "weaponId");
        Objects.requireNonNull(id, "id");
        return MagicAttackBehaviorRegistry.shared().bind(owner, weaponId, id);
    }

    /** Removes the owner's Java binding for one attack of a weapon definition. Casts already running finish. */
    public static void unbind(Plugin owner, String weaponId, MagicAttackKind attackKind) {
        if (owner == null || weaponId == null || attackKind == null) return;
        MagicAttackBehaviorRegistry.shared().unbind(owner, weaponId, attackKind);
    }

    /** Removes one of the owner's behaviors with its Java bindings and ends its running casts. */
    public static void unregister(Plugin owner, NamespacedKey id) {
        if (owner == null || id == null) return;
        MagicAttackBehaviorRegistry.shared().unregister(owner, id);
    }

    /**
     * Removes every behavior and binding of the owner and ends their running casts.
     * FreeMinecraftModels does this by itself when the owner disables.
     */
    public static void unregisterAll(Plugin owner) {
        if (owner == null) return;
        MagicAttackBehaviorRegistry.shared().unregisterAll(owner);
    }

    /** Whether an enabled plugin currently has a behavior registered under this id. */
    public static boolean isRegistered(NamespacedKey id) {
        return id != null && MagicAttackBehaviorRegistry.shared().isRegistered(id);
    }
}
