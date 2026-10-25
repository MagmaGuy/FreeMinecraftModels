package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.config.ContentFileSelector;
import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;
import com.magmaguy.magmacore.scripting.LuaMindModuleRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The pet behavior modules: every .lua file under the pet_behaviors folder, the shipped defaults among them. A
 * default is written when its file is missing, so a deleted default comes back and an edited one stays. A module's
 * id is fmm:pet/ and its file name, and a pet file lists modules by that file name. Each load opens a fresh module
 * owner, so an edited module needs no new revision across reloads.
 */
public final class PetBehaviorCatalog implements AutoCloseable {
    public static final String NAMESPACE = "fmm";
    public static final String ID_PREFIX = NAMESPACE + ":pet/";
    /** The shipped modules by file name, each a resource under pet_behaviors/ in the jar. */
    public static final List<String> DEFAULTS = List.of();
    private static final Pattern NAME = Pattern.compile("[a-z0-9._-]+");

    private final LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
    private final LuaMindModuleRegistry.OwnerScope owner = registry.openOwner(NAMESPACE);
    private final Map<String, LuaMindModuleDescriptor> modules = new TreeMap<>();

    private PetBehaviorCatalog() {
    }

    /**
     * Writes the missing defaults through {@code writeDefault}, then loads every module in the folder. A module that
     * cannot load is reported through {@code warn} and skipped; the rest still load.
     */
    public static PetBehaviorCatalog load(Path folder, List<String> defaults, Consumer<String> writeDefault,
                                          Consumer<String> warn) {
        PetBehaviorCatalog catalog = new PetBehaviorCatalog();
        try {
            Files.createDirectories(folder);
        } catch (IOException | RuntimeException failure) {
            warn.accept(display(folder, folder) + ": could not create the pet behaviors folder (" + failure.getMessage()
                    + "); no pet behaviors load.");
            return catalog;
        }
        for (String name : defaults) {
            try {
                if (!Files.exists(folder.resolve(name + ".lua"))) writeDefault.accept(name);
            } catch (RuntimeException failure) {
                warn.accept(display(folder, folder.resolve(name + ".lua")) + ": could not write this default pet behavior ("
                        + failure.getMessage() + "); it stays missing until the next load.");
            }
        }
        Map<String, Pending> pending = new LinkedHashMap<>();
        for (File file : ContentFileSelector.select(luaFiles(folder, warn), name -> name.toLowerCase(Locale.ROOT))) {
            String where = display(folder, file.toPath());
            String name = file.getName().substring(0, file.getName().length() - ".lua".length()).toLowerCase(Locale.ROOT);
            if (!NAME.matcher(name).matches()) {
                warn.accept(where + ": a pet behavior's file name may use only lower-case letters, digits, '.', '_'"
                        + " and '-'; this module is skipped.");
                continue;
            }
            try {
                String source = Files.readString(file.toPath());
                LuaMindModuleDescriptor declared = LuaMindModuleRegistry.inspectModule(where, source);
                String expected = ID_PREFIX + name;
                if (!declared.identifier().equals(expected)) {
                    warn.accept(where + ": declares id " + declared.identifier() + ", but a module's id is "
                            + ID_PREFIX + " followed by its file name, so it must be " + expected
                            + "; this module is skipped.");
                    continue;
                }
                pending.put(name, new Pending(where, source, declared.revision()));
            } catch (IOException | RuntimeException failure) {
                warn.accept(where + ": " + reason(where, failure) + "; this module is skipped.");
            }
        }
        // A module may depend on another, which must register first; register in passes until no registration advances.
        Map<String, String> failures = new LinkedHashMap<>();
        boolean advanced = true;
        while (advanced && !pending.isEmpty()) {
            advanced = false;
            for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Pending> entry = it.next();
                Pending module = entry.getValue();
                try {
                    catalog.modules.put(entry.getKey(), catalog.owner.registerModule(ID_PREFIX + entry.getKey(),
                            module.revision(), module.where(), module.source()));
                    failures.remove(entry.getKey());
                    it.remove();
                    advanced = true;
                } catch (RuntimeException failure) {
                    failures.put(entry.getKey(), module.where() + ": " + reason(module.where(), failure)
                            + "; this module is skipped.");
                }
            }
        }
        failures.values().forEach(warn);
        return catalog;
    }

    /** Every .lua file under the folder that really lies inside it. */
    private static List<File> luaFiles(Path folder, Consumer<String> warn) {
        List<Path> candidates;
        Path root;
        try (Stream<Path> walk = Files.walk(folder)) {
            root = folder.toRealPath();
            candidates = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lua")).toList();
        } catch (IOException | RuntimeException failure) {
            warn.accept(display(folder, folder) + ": could not read the pet behaviors folder (" + failure.getMessage()
                    + "); no pet behaviors load.");
            return List.of();
        }
        List<File> files = new ArrayList<>();
        for (Path path : candidates) {
            try {
                if (path.toRealPath().startsWith(root)) files.add(path.toFile());
                else warn.accept(display(folder, path) + ": links to a file outside the pet behaviors folder;"
                        + " this module is skipped.");
            } catch (IOException failure) {
                warn.accept(display(folder, path) + ": could not read this file (" + failure.getMessage()
                        + "); this module is skipped.");
            }
        }
        return files;
    }

    /**
     * A failure's message without the file name Lua puts in front of it, which the warning already starts with:
     * {@code [string "pet_behaviors/drift.lua"]:3: ...} and {@code pet_behaviors/drift.lua:3 ...} read as
     * {@code line 3: ...}.
     */
    private static String reason(String where, Exception failure) {
        String message = String.valueOf(failure.getMessage());
        return message.replaceFirst("^(?:\\[string \"" + Pattern.quote(where) + "\"]|" + Pattern.quote(where)
                + "):(\\d+):? ", "line $1: ");
    }

    /** A path as a server owner finds it: from the pet behaviors folder down, for example pet_behaviors/mine/drift.lua. */
    private static String display(Path folder, Path path) {
        Path name = folder.getFileName();
        Path relative = folder.relativize(path);
        Path shown = name == null ? relative : name.resolve(relative);
        return shown.toString().replace('\\', '/');
    }

    /** The module a pet file names, by its file name. */
    public Optional<LuaMindModuleDescriptor> module(String name) {
        return Optional.ofNullable(modules.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The file names of every module that loaded. */
    public Set<String> names() {
        return Collections.unmodifiableSet(modules.keySet());
    }

    /** The owner the modules are registered under, for composing them into programs. */
    LuaMindModuleRegistry.OwnerScope owner() {
        return owner;
    }

    @Override
    public void close() {
        registry.close();
    }

    private record Pending(String where, String source, long revision) {
    }
}
