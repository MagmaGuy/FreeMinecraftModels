package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackBehavior;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registered attack behaviors and their Java weapon bindings, behind the public
 * {@link com.magmaguy.freeminecraftmodels.api.magic.MagicAttackBehaviors} facade.
 *
 * <p>The shared instance outlives the runtime, so an addon may register before FMM has finished
 * loading and its registrations survive content reloads. A registration counts only while its
 * owner is enabled.</p>
 */
public final class MagicAttackBehaviorRegistry {
    private static final MagicAttackBehaviorRegistry SHARED =
            new MagicAttackBehaviorRegistry(com.magmaguy.magmacore.util.Logger::warn);

    /** One registered behavior. Identity matters: a replaced registration is a different instance. */
    public static final class Registration {
        private final Plugin owner;
        private final NamespacedKey id;
        private final MagicAttackKind attackKind;
        private final MagicAttackBehavior behavior;

        private Registration(Plugin owner, NamespacedKey id, MagicAttackKind attackKind, MagicAttackBehavior behavior) {
            this.owner = owner;
            this.id = id;
            this.attackKind = attackKind;
            this.behavior = behavior;
        }

        public Plugin owner() { return owner; }
        public NamespacedKey id() { return id; }
        public MagicAttackKind attackKind() { return attackKind; }
        public MagicAttackBehavior behavior() { return behavior; }
    }

    private record BindingKey(String weaponId, MagicAttackKind attackKind) { }
    private record Binding(Plugin owner, NamespacedKey id) { }

    private final Map<NamespacedKey, Registration> behaviors = new LinkedHashMap<>();
    private final Map<BindingKey, Binding> bindings = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final List<Consumer<Registration>> removalListeners = new CopyOnWriteArrayList<>();
    private final Consumer<String> warnings;

    MagicAttackBehaviorRegistry(Consumer<String> warnings) {
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    public static MagicAttackBehaviorRegistry shared() {
        return SHARED;
    }

    public boolean register(Plugin owner, NamespacedKey id, MagicAttackKind attackKind, MagicAttackBehavior behavior) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(attackKind, "attackKind");
        Objects.requireNonNull(behavior, "behavior");
        String namespace = owner.getName().toLowerCase(Locale.ROOT);
        if (!id.getNamespace().equals(namespace))
            throw new IllegalArgumentException("Attack behavior " + id + " must use its owner's namespace: " + namespace);
        Registration replaced;
        synchronized (this) {
            if (!owner.isEnabled()) return false;
            replaced = behaviors.get(id);
            if (replaced != null && replaced.owner != owner && replaced.owner.isEnabled()) return false;
            if (replaced != null && replaced.owner != owner) bindings.values().removeIf(binding -> binding.id.equals(id));
            behaviors.put(id, new Registration(owner, id, attackKind, behavior));
            warned.clear();
        }
        if (replaced != null) notifyRemoved(replaced);
        return true;
    }

    public boolean bind(Plugin owner, String weaponId, NamespacedKey id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        String weapon = normalize(weaponId);
        if (weapon.isEmpty()) return false;
        synchronized (this) {
            Registration registration = behaviors.get(id);
            if (registration == null || registration.owner != owner || !owner.isEnabled()) return false;
            BindingKey key = new BindingKey(weapon, registration.attackKind);
            Binding existing = bindings.get(key);
            if (existing != null && existing.owner != owner && existing.owner.isEnabled()) return false;
            bindings.put(key, new Binding(owner, id));
            return true;
        }
    }

    public synchronized void unbind(Plugin owner, String weaponId, MagicAttackKind attackKind) {
        BindingKey key = new BindingKey(normalize(weaponId), attackKind);
        Binding existing = bindings.get(key);
        if (existing != null && existing.owner == owner) bindings.remove(key);
    }

    public void unregister(Plugin owner, NamespacedKey id) {
        Registration removed;
        synchronized (this) {
            removed = behaviors.get(id);
            if (removed == null || removed.owner != owner) return;
            behaviors.remove(id);
            bindings.values().removeIf(binding -> binding.id.equals(id));
            warned.clear();
        }
        notifyRemoved(removed);
    }

    public void unregisterAll(Plugin owner) {
        List<Registration> removed = new ArrayList<>();
        synchronized (this) {
            behaviors.values().removeIf(registration -> {
                if (registration.owner != owner) return false;
                removed.add(registration);
                return true;
            });
            bindings.values().removeIf(binding -> binding.owner == owner);
            if (!removed.isEmpty()) warned.clear();
        }
        removed.forEach(this::notifyRemoved);
    }

    public synchronized boolean isRegistered(NamespacedKey id) {
        Registration registration = behaviors.get(id);
        return registration != null && registration.owner.isEnabled();
    }

    /**
     * The behavior that stands in for this attack of this weapon, or null for the built-in attack.
     * A behavior named in the item file decides alone; a Java binding applies otherwise.
     *
     * @param warn whether an item file naming an unusable behavior is reported, once per weapon attack
     */
    synchronized Registration resolve(MagicWeaponDefinition definition, MagicAttackKind attackKind, boolean warn) {
        NamespacedKey named = definition.behaviors().get(attackKind);
        if (named != null) {
            Registration registration = behaviors.get(named);
            if (registration != null && registration.owner.isEnabled() && registration.attackKind == attackKind)
                return registration;
            if (warn && warned.add(definition.itemId() + '|' + attackKind + '|' + named)) {
                String reason = registration == null || !registration.owner.isEnabled()
                        ? "no enabled plugin has registered it"
                        : "that behavior replaces " + registration.attackKind;
                warnings.accept("Weapon " + definition.itemId() + " names attack behavior " + named + " for "
                        + attackKind + ", but " + reason + ". The built-in attack is cast instead.");
            }
            return null;
        }
        Binding binding = bindings.get(new BindingKey(normalize(definition.itemId()), attackKind));
        if (binding == null) return null;
        Registration registration = behaviors.get(binding.id);
        return registration != null && registration.owner == binding.owner && registration.owner.isEnabled()
                ? registration : null;
    }

    /** Lets a reloaded item catalog report its unusable behavior ids again. */
    synchronized void resetWarnings() {
        warned.clear();
    }

    void addRemovalListener(Consumer<Registration> listener) {
        removalListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void removeRemovalListener(Consumer<Registration> listener) {
        removalListeners.remove(listener);
    }

    private void notifyRemoved(Registration registration) {
        for (Consumer<Registration> listener : removalListeners) listener.accept(registration);
    }

    private static String normalize(String weaponId) {
        if (weaponId == null) return "";
        String id = weaponId.trim().toLowerCase(Locale.ROOT);
        return id.startsWith("freeminecraftmodels:") ? id.substring("freeminecraftmodels:".length()) : id;
    }
}
