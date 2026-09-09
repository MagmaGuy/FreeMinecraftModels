package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackBalance;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackRequest;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackResolver;
import com.magmaguy.freeminecraftmodels.api.magic.MagicResolutionOutcome;
import com.magmaguy.freeminecraftmodels.api.magic.MagicTargetRequest;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponService;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponModifiers;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import com.magmaguy.freeminecraftmodels.interaction.InteractionProtectionPolicy;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** FMM's sole input, projectile, collision and damage owner for built-in magic weapons. */
public final class MagicWeaponRuntime implements Listener, MagicWeaponService, AutoCloseable {
    private static final int REQUIRED_CAPABILITY_VERSION = 2;

    private final Plugin plugin;
    private final MagicWeaponCatalog catalog;
    private final MagicWeaponDefinitionComposer definitionComposer;
    private final MagicInputDeduplicator deduplicator = new MagicInputDeduplicator();
    private final MagicDamageResolution damageResolution = new MagicDamageResolution();
    private final MagicProjectileEngine projectiles;
    private final Map<CooldownKey, Long> readyAtTick = new HashMap<>();
    private final ThreadLocal<Integer> applyingDamageDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<LivingEntity> noKnockbackTarget = new ThreadLocal<>();
    private final ThreadLocal<DamageAttempt> damageAttempt = new ThreadLocal<>();
    private Plugin resolverOwner;
    private MagicAttackResolver resolver;
    private volatile boolean contentReady;
    private volatile boolean paused;
    private boolean started;
    private boolean closed;
    private boolean fallbackWarningSent;
    private boolean contentWarningSent;
    private boolean compositionWarningSent;
    private boolean targetPolicyWarningSent;

    public MagicWeaponRuntime(Plugin plugin) {
        this(plugin, BuiltInMagicWeapons.catalog(), MagicWeaponDefinitionComposer.identity());
    }

    public MagicWeaponRuntime(
            Plugin plugin,
            MagicWeaponDefinitionComposer definitionComposer) {
        this(plugin, BuiltInMagicWeapons.catalog(), definitionComposer);
    }

    MagicWeaponRuntime(Plugin plugin, MagicWeaponCatalog catalog) {
        this(plugin, catalog, MagicWeaponDefinitionComposer.identity());
    }

    MagicWeaponRuntime(
            Plugin plugin,
            MagicWeaponCatalog catalog,
            MagicWeaponDefinitionComposer definitionComposer) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.definitionComposer = java.util.Objects.requireNonNull(definitionComposer, "definitionComposer");
        this.projectiles = new MagicProjectileEngine(plugin, this::onImpact);
    }

    public void start() {
        if (closed || started) throw new IllegalStateException("Magic weapon runtime cannot start");
        refreshContentState();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        projectiles.start();
        started = true;
        // Publish only after the runtime can accept casts. FMM finishes initialization after
        // Bukkit's PluginEnableEvent, so ServiceRegisterEvent is the only readiness edge an
        // already-enabled consumer may observe.
        plugin.getServer().getServicesManager().register(
                MagicWeaponService.class, this, plugin, ServicePriority.Normal);
    }

    /** Re-evaluates the code-matched defaults after FMM content reloads. */
    public void refreshContentState() {
        contentReady = MagicWeaponIdentity.bundledContentReady();
        if (contentReady) {
            contentWarningSent = false;
        } else if (!contentWarningSent) {
            contentWarningSent = true;
            Logger.warn("Bundled FMM magic weapons are incomplete. Reinstall FreeMinecraftModels "
                    + "so Spellcaster can be enabled.");
        }
    }

    /** Stops new casts and retires in-flight markers before live content registries are cleared. */
    public void pauseForContentReload() {
        if (closed) return;
        paused = true;
        projectiles.cancelAll();
    }

    /** Reopens casting only after the rebuilt item and display-model registries are available. */
    public void resumeAfterContentReload() {
        if (closed) return;
        refreshContentState();
        paused = false;
    }

    @Override
    public boolean isOperational() {
        return started && !closed && !paused && contentReady;
    }

    @Override
    public boolean isBuiltInWeapon(String itemId) {
        return catalog.find(itemId).isPresent();
    }

    @Override
    public boolean applyBuiltInWeaponData(ItemStack itemStack, String itemId) {
        if (!isOperational()) return false;
        return catalog.find(itemId)
                .map(definition -> MagicWeaponIdentity.apply(itemStack, definition))
                .orElse(false);
    }

    @Override
    public synchronized boolean registerResolver(Plugin owner, MagicAttackResolver candidate) {
        if (!isOperational() || owner == null || candidate == null || !owner.isEnabled()) return false;
        if (resolverOwner != null && resolverOwner != owner) return false;
        resolverOwner = owner;
        resolver = candidate;
        fallbackWarningSent = false;
        targetPolicyWarningSent = false;
        return true;
    }

    @Override
    public synchronized void unregisterResolver(Plugin owner) {
        if (owner == null || resolverOwner != owner) return;
        resolverOwner = null;
        resolver = null;
        fallbackWarningSent = false;
        targetPolicyWarningSent = false;
        // A delayed cast must not silently switch from an integration's hostile-target policy
        // to FMM's broader standalone policy while it is in flight.
        projectiles.cancelAll();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        MagicInput input = switch (event.getAction()) {
            case LEFT_CLICK_AIR -> MagicInput.AIR_LEFT_CLICK;
            case LEFT_CLICK_BLOCK -> MagicInput.BLOCK_LEFT_CLICK;
            case RIGHT_CLICK_AIR -> MagicInput.AIR_RIGHT_CLICK;
            case RIGHT_CLICK_BLOCK -> MagicInput.BLOCK_RIGHT_CLICK;
            default -> null;
        };
        if (input == null) return;
        if (InteractionProtectionPolicy.isDenied(
                event.useInteractedBlock(), event.useItemInHand())) return;
        Optional<MagicWeaponDefinition> definition = resolveDefinition(event.getItem());
        if (definition.isEmpty() || MagicWeaponInputRouter.route(definition.get().kind(), input).isEmpty()) return;
        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        dispatch(event.getPlayer(), event.getItem(), definition.get(), input, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event instanceof PlayerInteractAtEntityEvent || event.getHand() != EquipmentSlot.HAND) return;
        dispatchEntityRightClick(event, event.getRightClicked());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        dispatchEntityRightClick(event, event.getRightClicked());
    }

    private void dispatchEntityRightClick(PlayerInteractEntityEvent event, Entity clicked) {
        // The modeled event after this permission check owns spell dispatch.
        if (event instanceof com.magmaguy.freeminecraftmodels.api.ModeledEntityInteractEvent) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        Optional<MagicWeaponDefinition> definition = resolveDefinition(held);
        if (definition.isEmpty() || MagicWeaponInputRouter.route(
                definition.get().kind(), MagicInput.ENTITY_RIGHT_CLICK).isEmpty()) return;
        event.setCancelled(true);
        dispatch(event.getPlayer(), held, definition.get(), MagicInput.ENTITY_RIGHT_CLICK,
                clicked instanceof LivingEntity living ? living : null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onModeledLeftClick(ModeledEntityLeftClickEvent event) {
        if (isApplyingDamage()) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        Optional<MagicWeaponDefinition> definition = resolveDefinition(held);
        if (definition.isEmpty()) return;
        Optional<MagicAttackKind> attack = MagicWeaponInputRouter.route(
                definition.get().kind(), MagicInput.ENTITY_LEFT_CLICK);
        if (attack.isEmpty()) return;
        event.setCancelled(true);
        Entity underlying = event.getEntity().getUnderlyingEntity();
        dispatch(event.getPlayer(), held, definition.get(), MagicInput.ENTITY_LEFT_CLICK,
                underlying instanceof LivingEntity living ? living : null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onModeledRightClick(ModeledEntityRightClickEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        Optional<MagicWeaponDefinition> definition = resolveDefinition(held);
        if (definition.isEmpty() || MagicWeaponInputRouter.route(
                definition.get().kind(), MagicInput.ENTITY_RIGHT_CLICK).isEmpty()) return;
        event.setCancelled(true);
        Entity underlying = event.getEntity().getUnderlyingEntity();
        dispatch(event.getPlayer(), held, definition.get(), MagicInput.ENTITY_RIGHT_CLICK,
                underlying instanceof LivingEntity living ? living : null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDirectMelee(EntityDamageByEntityEvent event) {
        if (isApplyingDamage() || !(event.getDamager() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<MagicWeaponDefinition> definition = resolveDefinition(held);
        if (definition.isEmpty()) return;
        Optional<MagicAttackKind> attack = MagicWeaponInputRouter.route(
                definition.get().kind(), MagicInput.ENTITY_LEFT_CLICK);
        if (attack.isEmpty()) return;
        event.setCancelled(true);
        dispatch(player, held, definition.get(), MagicInput.ENTITY_LEFT_CLICK,
                event.getEntity() instanceof LivingEntity living ? living : null);
    }

    private void dispatch(
            Player player,
            ItemStack weapon,
            MagicWeaponDefinition definition,
            MagicInput input,
            LivingEntity clickedTarget) {
        if (!isOperational() || player == null || !player.isOnline()) return;
        MagicAttackKind attackKind = MagicWeaponInputRouter.route(definition.kind(), input).orElse(null);
        if (attackKind == null) return;
        MagicAttackResolver resolver = activeResolver();
        if (resolver != null) {
            try {
                if (!resolver.canAttack(player, weapon, attackKind)) return;
            } catch (RuntimeException policyFailure) {
                return;
            }
        }
        long tick = projectiles.currentTick();
        if (!deduplicator.accept(player.getUniqueId(), attackKind, tick)) return;
        CooldownKey cooldownKey = new CooldownKey(player.getUniqueId(), definition.itemId(), attackKind);
        if (readyAtTick.getOrDefault(cooldownKey, 0L) > tick) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, .25F, .65F);
            return;
        }

        MagicCast cast = new MagicCast(UUID.randomUUID(), player, weapon, definition, attackKind);
        boolean launched = switch (attackKind) {
            case STAFF_MELEE -> strikeWithStaff(cast, clickedTarget);
            case WAND_MISSILE -> castWand(cast);
            case STAFF_FIREBALL -> castStaff(cast);
        };
        if (!launched) return;

        int reload = definition.reloadTicks(attackKind);
        readyAtTick.put(cooldownKey, tick + reload);
        player.setCooldown(weapon, reload);
        player.swingMainHand();
    }

    private boolean strikeWithStaff(MagicCast cast, LivingEntity target) {
        if (!targetEligible(cast, target)) return false;
        applyDamage(cast, target, 1D);
        World world = target.getWorld();
        world.spawnParticle(Particle.ENCHANTED_HIT, MagicProjectileEngine.center(target), 8, .25, .35, .25, .05);
        world.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, .55F, .8F);
        return true;
    }

    private boolean castWand(MagicCast cast) {
        MagicWeaponTraits traits = cast.definition().traits();
        List<LivingEntity> targets = projectiles.acquireTargets(
                cast.owner(), traits.range(), traits.aimAssistDegrees(),
                candidate -> targetEligible(cast, candidate),
                candidate -> targetPriority(cast, candidate), traits.missileCount());
        if (!projectiles.launchWand(cast, targets)) return false;
        var slowness = cast.owner().getPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
        if (slowness == null || slowness.getAmplifier() == 0
                && !slowness.isInfinite() && slowness.getDuration() < 20)
            cast.owner().addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.SLOWNESS, 20, 0));
        cast.owner().getWorld().playSound(
                cast.owner().getEyeLocation(), Sound.ENTITY_EVOKER_CAST_SPELL,
                targets.isEmpty() ? .4F : .55F, targets.isEmpty() ? 1.4F : 1.7F);
        return true;
    }

    private int targetPriority(MagicCast cast, LivingEntity target) {
        MagicAttackResolver active = activeResolver();
        if (active == null) return MagicAttackResolver.defaultTargetPriority(target);
        try {
            return active.targetPriority(new MagicTargetRequest(
                    cast.attackId(), cast.attackKind(), cast.owner(), target, cast.weapon()));
        } catch (Throwable policyFailure) {
            return MagicAttackResolver.defaultTargetPriority(target);
        }
    }

    private boolean castStaff(MagicCast cast) {
        Vector direction = cast.owner().getEyeLocation().getDirection();
        if (!projectiles.launchStaff(cast, direction)) return false;
        cast.owner().getWorld().playSound(
                cast.owner().getEyeLocation(), Sound.ENTITY_BLAZE_SHOOT, .8F, .72F);
        return true;
    }

    private void onImpact(MagicCast cast, LivingEntity directTarget, Location impact, Vector incoming) {
        if (cast.attackKind() == MagicAttackKind.WAND_MISSILE) {
            if (!targetEligible(cast, directTarget)) return;
            World world = directTarget.getWorld();
            world.spawnParticle(Particle.ENCHANTED_HIT, MagicProjectileEngine.center(directTarget),
                    12, .3, .45, .3, .08);
            world.playSound(directTarget.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .6F, 1.65F);
            applyDamage(cast, directTarget, 1D);
            return;
        }
        explodeStaff(cast, directTarget, impact, incoming);
    }

    private void explodeStaff(
            MagicCast cast,
            LivingEntity directTarget,
            Location impact,
            Vector incoming) {
        World world = impact.getWorld();
        if (world == null) return;
        MagicWeaponTraits traits = cast.definition().traits();
        double radius = traits.impactRadius();
        world.spawnParticle(Particle.EXPLOSION, impact, 3, .4, .4, .4, .02);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, impact, 34, 1.1, .7, 1.1, .08);
        for (int point = 0; point < 32; point++) {
            double angle = 2D * Math.PI * point / 32D;
            world.spawnParticle(Particle.FLAME, impact.clone().add(Math.cos(angle) * radius, .1D,
                    Math.sin(angle) * radius), 1, 0, 0, 0, 0);
        }
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, .85F, 1.05F);

        if (radius <= 0D) {
            if (targetEligible(cast, directTarget)) {
                if (applyDamage(cast, directTarget, 1D)) ignite(cast, directTarget, traits.ignitionTicks());
            }
            return;
        }

        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(impact, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)
                    || !targetEligible(cast, living)) continue;
            Location center = MagicProjectileEngine.center(living);
            if (center.distanceSquared(impact) > radius * radius) continue;
            if (living != directTarget && !explosionLine(impact, incoming, center)) continue;
            targets.add(living);
        }
        if (targetEligible(cast, directTarget) && !targets.contains(directTarget))
            targets.add(directTarget);

        for (LivingEntity target : targets) {
            double distance = Math.min(radius, MagicProjectileEngine.center(target).distance(impact));
            if (applyDamage(cast, target, .35D + .65D * (1D - distance / radius)))
                ignite(cast, target, traits.ignitionTicks());
        }
    }

    private void ignite(MagicCast cast, LivingEntity target, int ignitionTicks) {
        if (ignitionTicks <= 0 || target.isDead()) return;
        EntityCombustByEntityEvent event = new EntityCombustByEntityEvent(cast.owner(), target, ignitionTicks / 20F);
        plugin.getServer().getPluginManager().callEvent(event);
        if (!event.isCancelled())
            target.setFireTicks(Math.max(target.getFireTicks(), Math.min(200, Math.round(event.getDuration() * 20F))));
    }

    private static boolean explosionLine(Location impact, Vector incoming, Location target) {
        Location source = impact.clone();
        if (incoming != null && incoming.lengthSquared() > 1.0E-9D)
            source.subtract(incoming.clone().normalize().multiply(.12D));
        return MagicProjectileEngine.unobstructed(source, target);
    }

    private boolean applyDamage(MagicCast cast, LivingEntity target, double impactScale) {
        if (!MagicProjectileEngine.validTarget(cast.owner(), target)
                || !Double.isFinite(impactScale) || impactScale <= 0D) return false;
        MagicAttackBalance balance = new MagicAttackBalance(
                BuiltInMagicWeapons.STANDALONE_REFERENCE_DAMAGE,
                cast.definition().basePower(cast.attackKind()),
                impactScale);
        MagicAttackRequest request = new MagicAttackRequest(
                cast.attackId(), cast.attackKind(), cast.owner(), target, cast.weapon(), balance);
        boolean[] accepted = {false};
        MagicResolutionOutcome outcome = damageResolution.resolve(
                request, activeResolver(), damage -> accepted[0] = damageTarget(cast, target, damage));
        if (outcome == MagicResolutionOutcome.STANDALONE_FALLBACK && !fallbackWarningSent) {
            fallbackWarningSent = true;
            Logger.warn("The registered magic damage resolver did not resolve an impact. "
                    + "FreeMinecraftModels used conservative standalone damage instead.");
        } else if (outcome == MagicResolutionOutcome.FAILED) {
            Logger.warn("A magic weapon impact could not be applied to " + target.getType() + ".");
        }
        return accepted[0];
    }

    private Optional<MagicWeaponDefinition> resolveDefinition(ItemStack weapon) {
        Optional<MagicWeaponDefinition> base = MagicWeaponIdentity.resolve(weapon, catalog);
        if (base.isEmpty()) return Optional.empty();
        try {
            return Optional.of(MagicWeaponDefinitionComposition.compose(
                    weapon, base.get(), definitionComposer.andThen(this::applyResolverModifiers)));
        } catch (RuntimeException invalidComposition) {
            if (!compositionWarningSent) {
                compositionWarningSent = true;
                Logger.warn("A magic item modifier produced an invalid effective definition. "
                        + "FMM used the built-in mechanics instead: " + invalidComposition.getMessage());
            }
            return base;
        }
    }

    private boolean targetEligible(MagicCast cast, LivingEntity target) {
        if (!MagicProjectileEngine.validTarget(cast.owner(), target)) return false;
        MagicAttackResolver active = activeResolver();
        if (active == null) return true;
        try {
            return active.isTargetEligible(new MagicTargetRequest(
                    cast.attackId(), cast.attackKind(), cast.owner(), target, cast.weapon()));
        } catch (Throwable policyFailure) {
            if (!targetPolicyWarningSent) {
                targetPolicyWarningSent = true;
                Logger.warn("The registered magic target policy failed. FMM rejected its targets "
                        + "until the integration recovers: " + policyFailure.getMessage());
            }
            return false;
        }
    }

    private MagicAttackResolver activeResolver() {
        synchronized (this) {
            if (resolverOwner == null || resolver == null || !resolverOwner.isEnabled()) return null;
            return resolver;
        }
    }

    private MagicWeaponDefinition applyResolverModifiers(ItemStack weapon, MagicWeaponDefinition definition) {
        MagicAttackResolver active = activeResolver();
        if (active == null) return definition;
        MagicWeaponModifiers modifiers = java.util.Objects.requireNonNull(active.modifiers(weapon, definition.kind()));
        if (modifiers.equals(MagicWeaponModifiers.NONE)) return definition;
        MagicWeaponTraits traits = definition.traits();
        boolean wand = definition.kind() == MagicWeaponKind.WAND;
        MagicWeaponTraits effective = new MagicWeaponTraits(
                wand ? modifiers.missileCount() : traits.missileCount(), traits.spreadDegrees(),
                wand ? traits.impactRadius() : Math.min(6D, traits.impactRadius() * modifiers.blastRadiusMultiplier()),
                wand ? traits.ignitionTicks() : Math.max(traits.ignitionTicks(), modifiers.ignitionTicks()), traits.projectileSpeed(), traits.range(),
                traits.travelTicks(), traits.aimAssistDegrees());
        Map<MagicAttackKind, Double> powers = new HashMap<>(definition.basePowers());
        if (wand) powers.computeIfPresent(MagicAttackKind.WAND_MISSILE,
                (kind, power) -> power * modifiers.missileDamageMultiplier());
        return new MagicWeaponDefinition(definition.itemId(), definition.kind(), effective, powers, definition.reloadTicks());
    }

    private boolean damageTarget(MagicCast cast, LivingEntity target, double damage) {
        int previousDepth = applyingDamageDepth.get();
        boolean previousObbBypass = OBBHitDetection.applyDamage;
        LivingEntity previousNoKnockbackTarget = noKnockbackTarget.get();
        DamageAttempt previousAttempt = damageAttempt.get();
        DamageAttempt attempt = new DamageAttempt(target, cast.owner());
        damageAttempt.set(attempt);
        int previousInvulnerability = target.getNoDamageTicks();
        double previousLastDamage = target.getLastDamage();
        boolean multicast = cast.attackKind() == MagicAttackKind.WAND_MISSILE
                && cast.definition().traits().missileCount() > 1;
        if (cast.attackKind().weaponKind() == MagicWeaponKind.WAND) noKnockbackTarget.set(target);
        else noKnockbackTarget.remove();
        applyingDamageDepth.set(previousDepth + 1);
        OBBHitDetection.applyDamage = true;
        try {
            // Separate bolts must each resolve, including when all bolts hit the same enemy.
            if (multicast) target.setNoDamageTicks(0);
            target.damage(damage, cast.owner());
            return attempt.accepted;
        } finally {
            if (multicast) {
                target.setNoDamageTicks(previousInvulnerability);
                target.setLastDamage(previousLastDamage);
            }
            if (previousAttempt == null) damageAttempt.remove();
            else damageAttempt.set(previousAttempt);
            if (previousNoKnockbackTarget == null) noKnockbackTarget.remove();
            else noKnockbackTarget.set(previousNoKnockbackTarget);
            OBBHitDetection.applyDamage = previousObbBypass;
            if (previousDepth == 0) applyingDamageDepth.remove();
            else applyingDamageDepth.set(previousDepth);
        }
    }

    private boolean isApplyingDamage() {
        return applyingDamageDepth.get() > 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void observeMagicDamage(EntityDamageByEntityEvent event) {
        DamageAttempt attempt = damageAttempt.get();
        if (attempt != null && event.getEntity().equals(attempt.target) && event.getDamager().equals(attempt.owner))
            attempt.accepted = !event.isCancelled() && event.getFinalDamage() > 0D;
    }

    private static final class DamageAttempt {
        private final LivingEntity target;
        private final Player owner;
        private boolean accepted;
        private DamageAttempt(LivingEntity target, Player owner) { this.target = target; this.owner = owner; }
    }

    /** Wand damage must not add either horizontal knockback or the vanilla vertical lift. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWandKnockback(EntityKnockbackEvent event) {
        if (event.getEntity().equals(noKnockbackTarget.get())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        deduplicator.forget(playerId);
        readyAtTick.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        unregisterResolver(event.getPlugin());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        started = false;
        plugin.getServer().getServicesManager().unregister(MagicWeaponService.class, this);
        HandlerList.unregisterAll(this);
        projectiles.close();
        deduplicator.clear();
        readyAtTick.clear();
        synchronized (this) {
            resolverOwner = null;
            resolver = null;
        }
        applyingDamageDepth.remove();
    }

    public static int requiredCapabilityVersion() {
        return REQUIRED_CAPABILITY_VERSION;
    }

    private record CooldownKey(UUID playerId, String weaponId, MagicAttackKind attackKind) {
    }
}
