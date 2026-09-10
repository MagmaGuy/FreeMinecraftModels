package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackBalance;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackResolver;
import com.magmaguy.freeminecraftmodels.api.magic.MagicResolutionOutcome;
import com.magmaguy.freeminecraftmodels.api.magic.MagicTargetRequest;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagicWeaponContractTest {

    @Test
    void entityCastHandlersNeverReceiveAlreadyCancelledProtectionEvents() throws NoSuchMethodException {
        EventHandler interact = MagicWeaponRuntime.class
                .getDeclaredMethod("onEntityInteract", PlayerInteractEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler interactAt = MagicWeaponRuntime.class
                .getDeclaredMethod("onEntityInteractAt", PlayerInteractAtEntityEvent.class)
                .getAnnotation(EventHandler.class);

        assertTrue(interact.ignoreCancelled());
        assertTrue(interactAt.ignoreCancelled());
    }

    @Test
    void modeledAndDirectCastHandlersClaimOnlyInputsThatSurviveProtection() throws NoSuchMethodException {
        EventHandler modeledLeft = MagicWeaponRuntime.class
                .getDeclaredMethod("onModeledLeftClick", ModeledEntityLeftClickEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler modeledRight = MagicWeaponRuntime.class
                .getDeclaredMethod("onModeledRightClick", ModeledEntityRightClickEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler directMelee = MagicWeaponRuntime.class
                .getDeclaredMethod("onDirectMelee", EntityDamageByEntityEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, modeledLeft.priority());
        assertTrue(modeledLeft.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, modeledRight.priority());
        assertTrue(modeledRight.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, directMelee.priority());
        assertTrue(directMelee.ignoreCancelled());
    }

    @Test
    void controlsMapEveryRequiredSurfaceToOneCanonicalAttack() {
        assertEquals(MagicAttackKind.STAFF_MELEE,
                MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.ENTITY_LEFT_CLICK).orElseThrow());
        assertEquals(MagicAttackKind.STAFF_FIREBALL,
                MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.AIR_RIGHT_CLICK).orElseThrow());
        assertEquals(MagicAttackKind.STAFF_FIREBALL,
                MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.BLOCK_RIGHT_CLICK).orElseThrow());
        assertEquals(MagicAttackKind.STAFF_FIREBALL,
                MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.ENTITY_RIGHT_CLICK).orElseThrow());
        assertEquals(MagicAttackKind.WAND_MISSILE,
                MagicWeaponInputRouter.route(MagicWeaponKind.WAND, MagicInput.AIR_LEFT_CLICK).orElseThrow());
        assertEquals(MagicAttackKind.WAND_MISSILE,
                MagicWeaponInputRouter.route(MagicWeaponKind.WAND, MagicInput.BLOCK_LEFT_CLICK).orElseThrow());
        assertEquals(MagicAttackKind.WAND_MISSILE,
                MagicWeaponInputRouter.route(MagicWeaponKind.WAND, MagicInput.ENTITY_LEFT_CLICK).orElseThrow());

        assertTrue(MagicWeaponInputRouter.route(MagicWeaponKind.STAFF, MagicInput.AIR_LEFT_CLICK).isEmpty());
        assertTrue(MagicWeaponInputRouter.route(MagicWeaponKind.WAND, MagicInput.AIR_RIGHT_CLICK).isEmpty());
    }

    @Test
    void mirroredBukkitInputsOnTheSameTickDispatchOnceButLaterClicksRemainValid() {
        UUID playerId = UUID.randomUUID();
        MagicInputDeduplicator deduplicator = new MagicInputDeduplicator();

        assertTrue(deduplicator.accept(playerId, MagicAttackKind.WAND_MISSILE, 40));
        assertFalse(deduplicator.accept(playerId, MagicAttackKind.WAND_MISSILE, 40));
        assertFalse(deduplicator.accept(playerId, MagicAttackKind.WAND_MISSILE, 41));
        assertTrue(deduplicator.accept(playerId, MagicAttackKind.WAND_MISSILE, 42));
        assertTrue(deduplicator.accept(playerId, MagicAttackKind.STAFF_MELEE, 42));
    }

    @Test
    void defaultCatalogCarriesConservativeBaselineAndGenericModifierSeam() {
        MagicWeaponDefinition wand = BuiltInMagicWeapons.catalog().require(BuiltInMagicWeapons.DEFAULT_WAND_ID);
        MagicWeaponDefinition staff = BuiltInMagicWeapons.catalog().require(BuiltInMagicWeapons.DEFAULT_STAFF_ID);

        assertEquals(MagicWeaponKind.WAND, wand.kind());
        assertEquals(MagicWeaponKind.STAFF, staff.kind());
        assertTrue(wand.basePower(MagicAttackKind.WAND_MISSILE) > 0D);
        assertTrue(staff.basePower(MagicAttackKind.STAFF_MELEE) > 0D);
        assertTrue(staff.basePower(MagicAttackKind.STAFF_MELEE)
                < staff.basePower(MagicAttackKind.STAFF_FIREBALL));
        assertEquals(1, wand.traits().missileCount());
        assertEquals(0D, wand.traits().spreadDegrees());
        assertEquals(0, wand.traits().ignitionTicks());
        assertTrue(staff.traits().impactRadius() > 0D);
        assertTrue(staff.reloadTicks(MagicAttackKind.STAFF_FIREBALL)
                > wand.reloadTicks(MagicAttackKind.WAND_MISSILE));
        assertTrue(staff.reloadTicks(MagicAttackKind.STAFF_MELEE)
                < staff.reloadTicks(MagicAttackKind.STAFF_FIREBALL));
    }

    @Test
    void externalResolverMayVetoTargetsWhileStandaloneKeepsTheGenericFallback() {
        Player attacker = proxy(Player.class);
        LivingEntity target = proxy(LivingEntity.class);
        MagicTargetRequest request = new MagicTargetRequest(
                UUID.randomUUID(), MagicAttackKind.WAND_MISSILE,
                attacker, target, new ItemStack(Material.BLAZE_ROD));
        MagicAttackResolver standaloneCompatible = (ignored, application) -> { };
        MagicAttackResolver veto = new MagicAttackResolver() {
            @Override
            public void resolve(com.magmaguy.freeminecraftmodels.api.magic.MagicAttackRequest ignored,
                                com.magmaguy.freeminecraftmodels.api.magic.MagicDamageApplication application) {
            }

            @Override
            public boolean isTargetEligible(MagicTargetRequest ignored) {
                return false;
            }
        };

        assertTrue(standaloneCompatible.isTargetEligible(request));
        assertFalse(veto.isTargetEligible(request));
    }

    @Test
    void nativeModeledAndProjectileHitInterleavingsCanClaimOneImpactOnly() {
        UUID projectileId = UUID.randomUUID();
        Object flight = new Object();
        MagicImpactLedger<Object> ledger = new MagicImpactLedger<>();
        ledger.register(projectileId, flight);

        assertEquals(flight, ledger.lookup(projectileId));
        assertTrue(ledger.claim(projectileId, flight));
        assertFalse(ledger.claim(projectileId, flight));
        assertFalse(ledger.claim(projectileId, flight));
    }

    @Test
    void missileTraitSeamProducesAStableSymmetricArcPattern() {
        assertEquals(0D, MagicProjectileEngine.arcSpreadOffset(0D, 12D, 0, 3));
        assertEquals(0D, MagicProjectileEngine.arcSpreadOffset(15D, 12D, 0, 1));

        double left = MagicProjectileEngine.arcSpreadOffset(15D, 12D, 0, 3);
        double center = MagicProjectileEngine.arcSpreadOffset(15D, 12D, 1, 3);
        double right = MagicProjectileEngine.arcSpreadOffset(15D, 12D, 2, 3);
        assertTrue(left < 0D);
        assertEquals(0D, center, 1.0E-9D);
        assertEquals(-left, right, 1.0E-9D);
    }

    @Test
    void standaloneAndExternalResolversShareOneOneShotDamageSink() {
        MagicDamageResolution resolution = new MagicDamageResolution();
        MagicAttackBalance balance = new MagicAttackBalance(4D, .5D, .75D);
        AtomicInteger applications = new AtomicInteger();
        AtomicReference<Double> appliedDamage = new AtomicReference<>();

        MagicResolutionOutcome standalone = resolution.resolve(
                balance,
                null,
                damage -> {
                    applications.incrementAndGet();
                    appliedDamage.set(damage);
                });
        assertEquals(MagicResolutionOutcome.APPLIED, standalone);
        assertEquals(1, applications.get());
        assertEquals(1.5D, appliedDamage.get(), 1.0E-9D);

        applications.set(0);
        MagicResolutionOutcome scaled = resolution.resolve(
                balance,
                sink -> {
                    sink.apply(17D);
                    sink.apply(99D);
                },
                damage -> {
                    applications.incrementAndGet();
                    appliedDamage.set(damage);
                });
        assertEquals(MagicResolutionOutcome.APPLIED, scaled);
        assertEquals(1, applications.get());
        assertEquals(17D, appliedDamage.get(), 1.0E-9D);

        applications.set(0);
        MagicResolutionOutcome fallback = resolution.resolve(
                balance,
                ignored -> { },
                damage -> {
                    applications.incrementAndGet();
                    appliedDamage.set(damage);
                });
        assertEquals(MagicResolutionOutcome.STANDALONE_FALLBACK, fallback);
        assertEquals(1, applications.get());
        assertEquals(1.5D, appliedDamage.get(), 1.0E-9D);
    }

    @Test
    void resolverCanIntentionallySuppressDamageWithoutTriggeringStandaloneFallback() {
        MagicDamageResolution resolution = new MagicDamageResolution();
        MagicAttackBalance balance = new MagicAttackBalance(4D, .5D, 1D);
        AtomicInteger applications = new AtomicInteger();

        MagicResolutionOutcome outcome = resolution.resolve(
                balance,
                sink -> {
                    sink.apply(0D);
                    sink.apply(99D);
                },
                ignored -> applications.incrementAndGet());

        assertEquals(MagicResolutionOutcome.NO_DAMAGE, outcome);
        assertEquals(0, applications.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type},
                (instance, method, arguments) -> {
                    if (method.getName().equals("toString")) return type.getSimpleName() + "Proxy";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
                    if (method.getName().equals("equals")) return instance == arguments[0];
                    Class<?> result = method.getReturnType();
                    if (!result.isPrimitive()) return null;
                    if (result == boolean.class) return false;
                    if (result == char.class) return '\0';
                    if (result == byte.class) return (byte) 0;
                    if (result == short.class) return (short) 0;
                    if (result == int.class) return 0;
                    if (result == long.class) return 0L;
                    if (result == float.class) return 0F;
                    if (result == double.class) return 0D;
                    return null;
                });
    }
}
