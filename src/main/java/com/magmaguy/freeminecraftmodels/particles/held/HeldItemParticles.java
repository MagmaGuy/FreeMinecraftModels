package com.magmaguy.freeminecraftmodels.particles.held;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.LocatorBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.ParticleKeyframe;
import com.magmaguy.freeminecraftmodels.particles.ParticleSprites;
import com.magmaguy.freeminecraftmodels.particles.ResolvedParticleEffect;
import com.magmaguy.freeminecraftmodels.particles.runtime.ParticleEmitter;
import com.magmaguy.freeminecraftmodels.particles.runtime.ParticleSpriteRenderer;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.util.AttributeManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays particles on held FMM items. An item's {@code held} animation (or, without one, its first
 * animation with particle keyframes) loops while the item is in either hand; emitters sit at the
 * estimated third-person position of their locator, the same for every viewer. Runs on the
 * primary thread because it reads inventories and NMS body rotation.
 */
public final class HeldItemParticles {
    private static final double VIEW_DISTANCE_SQUARED = 48 * 48;
    private static final double TICK_SECONDS = 1 / 20.0;
    private static final Vector3d ITEM_CENTER = new Vector3d(8, 8, 8);

    private static final Map<UUID, HandState[]> hands = new HashMap<>();
    private static final Map<String, Optional<Profile>> profiles = new ConcurrentHashMap<>();
    private static BukkitTask task;
    private static int tick;

    private record Profile(List<ParticleKeyframe> keyframes, int duration, Map<String, Vector3d> locatorPoints,
                           HandTipEstimator.ItemTransform right, HandTipEstimator.ItemTransform left,
                           FileModelConverter converter) {
    }

    private record HeldEmitter(int keyframe, ParticleEmitter emitter, ParticleSpriteRenderer renderer,
                               Vector3d itemPoint) {
    }

    private static final class HandState {
        final String itemId;
        final Profile profile;
        final World world;
        final List<HeldEmitter> emitters = new ArrayList<>();
        final Set<UUID> viewers = new HashSet<>();
        int frame;

        HandState(String itemId, Profile profile, World world) {
            this.itemId = itemId;
            this.profile = profile;
            this.world = world;
        }

        void removeAll() {
            for (HeldEmitter emitter : emitters) emitter.renderer().removeAll();
            emitters.clear();
            viewers.clear();
        }
    }

    private HeldItemParticles() {
    }

    public static void start(Plugin plugin) {
        shutdown();
        task = Bukkit.getScheduler().runTaskTimer(plugin, HeldItemParticles::tickAll, 1, 1);
    }

    public static void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (HandState[] states : hands.values())
            for (HandState state : states) if (state != null) state.removeAll();
        hands.clear();
        profiles.clear();
    }

    private static void tickAll() {
        if (!DefaultConfig.particleEffectsEnabled) {
            if (!hands.isEmpty()) shutdownHands();
            return;
        }
        tick++;
        NMSAdapter adapter = NMSManager.getAdapter();
        if (!NMSManager.isEnabled() || adapter == null) return;
        AbstractPacketBundle bundle = adapter.createPacketBundle();
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            HandState[] states = hands.get(player.getUniqueId());
            for (int hand = 0; hand < 2; hand++) {
                ItemStack item = hand == 0 ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
                String itemId = itemId(item);
                Profile profile = itemId == null ? null : profiles.computeIfAbsent(itemId, HeldItemParticles::profile).orElse(null);
                HandState state = states == null ? null : states[hand];
                if (state != null && (profile == null || !state.itemId.equals(itemId) || player.isDead()
                        || state.world != player.getWorld())) {
                    state.removeAll();
                    states[hand] = null;
                    state = null;
                }
                if (profile == null || player.isDead()) continue;
                if (states == null) hands.put(player.getUniqueId(), states = new HandState[2]);
                if (state == null) states[hand] = state = new HandState(itemId, profile, player.getWorld());
                tickHand(player, hand, state, adapter, bundle);
            }
        }
        hands.entrySet().removeIf(entry -> {
            if (online.contains(entry.getKey())) return false;
            for (HandState state : entry.getValue()) if (state != null) state.removeAll();
            return true;
        });
        bundle.send();
    }

    private static void shutdownHands() {
        for (HandState[] states : hands.values())
            for (HandState state : states) if (state != null) state.removeAll();
        hands.clear();
    }

    private static void tickHand(Player player, int hand, HandState state, NMSAdapter adapter, AbstractPacketBundle bundle) {
        Profile profile = state.profile;
        int frame = state.frame % profile.duration();
        state.frame++;
        for (int index = 0; index < profile.keyframes().size(); index++) {
            ParticleKeyframe keyframe = profile.keyframes().get(index);
            if (keyframe.tick() == frame) start(state, index, keyframe);
        }
        if (state.emitters.isEmpty()) return;

        Set<UUID> viewers = viewers(player);
        for (UUID former : state.viewers) {
            if (viewers.contains(former)) continue;
            for (HeldEmitter emitter : state.emitters) emitter.renderer().hideFrom(former);
        }
        state.viewers.clear();
        state.viewers.addAll(viewers);

        boolean mainHandRight = player.getMainHand() == MainHand.RIGHT;
        boolean leftArm = hand == 0 ? !mainHandRight : mainHandRight;
        Vector3d feet = new Vector3d(player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());
        float bodyYaw = adapter.getBodyRotation(player);
        double scale = scale(player);
        Vector3d position = new Vector3d();
        Quaterniond rotation = new Quaterniond();
        Iterator<HeldEmitter> iterator = state.emitters.iterator();
        while (iterator.hasNext()) {
            HeldEmitter held = iterator.next();
            HandTipEstimator.estimate(feet, bodyYaw, player.isSneaking(), leftArm, scale,
                    leftArm ? profile.left() : profile.right(), held.itemPoint(), position, rotation);
            held.emitter().tick(position, rotation, TICK_SECONDS);
            held.renderer().render(held.emitter(), position, rotation, player.getWorld(), viewers, bundle, tick);
            if (held.emitter().isFinished() && held.renderer().isEmpty()) iterator.remove();
        }
    }

    private static void start(HandState state, int index, ParticleKeyframe keyframe) {
        for (HeldEmitter held : state.emitters) {
            if (held.keyframe() == index && !held.emitter().isFinished()) {
                held.emitter().start();
                return;
            }
        }
        ResolvedParticleEffect effect = state.profile.converter().getParticleEffects().get(keyframe.effect());
        ParticleSprites sprites = state.profile.converter().getParticleSprites().get(keyframe.effect());
        if (effect == null || sprites == null) return;
        ParticleEmitter emitter = new ParticleEmitter(effect.blueprint(), new SplittableRandom(),
                DefaultConfig.maxParticlesPerEmitter);
        emitter.start();
        Vector3d point = state.profile.locatorPoints().getOrDefault(keyframe.locator(), ITEM_CENTER);
        state.emitters.add(new HeldEmitter(index, emitter, new ParticleSpriteRenderer(sprites, effect.blueprint(),
                location -> NMSManager.getAdapter().createPacketDisplayEntity(location), DefaultConfig.maxParticlesPerViewer),
                point));
    }

    private static Set<UUID> viewers(Player holder) {
        Set<UUID> viewers = new HashSet<>();
        for (Player player : holder.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(holder.getLocation()) > VIEW_DISTANCE_SQUARED) continue;
            if (BedrockChecker.isBedrock(player)) continue;
            viewers.add(player.getUniqueId());
        }
        return viewers;
    }

    private static double scale(Player player) {
        AttributeInstance attribute = player.getAttribute(AttributeManager.getAttribute("generic_scale"));
        return attribute == null ? 1 : attribute.getValue();
    }

    private static String itemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(ItemScriptManager.ITEM_ID_KEY, PersistentDataType.STRING);
    }

    private static Optional<Profile> profile(String itemId) {
        FileModelConverter converter = FileModelConverter.getModel(itemId);
        if (converter == null || converter.getParticleEffects().isEmpty() || converter.getAnimationsBlueprint() == null)
            return Optional.empty();
        AnimationBlueprint chosen = heldAnimation(converter);
        if (chosen == null) return Optional.empty();

        Map<String, Vector3d> points = new HashMap<>();
        for (ParticleKeyframe keyframe : chosen.getParticleKeyframes()) {
            LocatorBlueprint locator = converter.getSkeletonBlueprint().getLocatorByName(keyframe.locator());
            if (locator == null) continue;
            points.put(keyframe.locator(), HandTipEstimator.itemSpace(new Vector3d(
                    locator.getPosition().x, locator.getPosition().y, locator.getPosition().z)));
        }
        HandTipEstimator.ItemTransform right = null, left = null;
        File source = converter.getSourceFile();
        if (source != null) {
            String name = source.getName();
            File display = new File(source.getParentFile(), name.substring(0, name.lastIndexOf('.')) + ".json");
            if (display.isFile()) {
                try {
                    JsonObject transforms = JsonParser.parseString(Files.readString(display.toPath(), StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonObject("display");
                    right = transform(transforms, "thirdperson_righthand");
                    left = transform(transforms, "thirdperson_lefthand");
                    if (left == null) left = right;
                } catch (RuntimeException | java.io.IOException unreadable) {
                    // Without display transforms the item sits in the hand untransformed.
                }
            }
        }
        return Optional.of(new Profile(List.copyOf(chosen.getParticleKeyframes()), chosen.getDuration(), Map.copyOf(points),
                right, left, converter));
    }

    /**
     * The animation a held item loops for particles: {@code held} when it has particle keyframes,
     * otherwise the first animation by name that has some. Null when there is none.
     */
    public static AnimationBlueprint heldAnimation(FileModelConverter converter) {
        if (converter == null || converter.getAnimationsBlueprint() == null) return null;
        Map<String, AnimationBlueprint> animations = converter.getAnimationsBlueprint().getAnimations();
        AnimationBlueprint chosen = animations.get("held");
        if (chosen == null || chosen.getParticleKeyframes().isEmpty()) {
            chosen = animations.keySet().stream().sorted().map(animations::get)
                    .filter(animation -> !animation.getParticleKeyframes().isEmpty()).findFirst().orElse(null);
        }
        return chosen == null || chosen.getDuration() <= 0 ? null : chosen;
    }

    private static HandTipEstimator.ItemTransform transform(JsonObject display, String key) {
        if (display == null || !display.has(key)) return null;
        JsonObject transform = display.getAsJsonObject(key);
        return new HandTipEstimator.ItemTransform(vector(transform, "rotation", 0), vector(transform, "translation", 0),
                vector(transform, "scale", 1));
    }

    private static Vector3d vector(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 3)
            return new Vector3d(fallback);
        return new Vector3d(element.getAsJsonArray().get(0).getAsDouble(), element.getAsJsonArray().get(1).getAsDouble(),
                element.getAsJsonArray().get(2).getAsDouble());
    }
}
