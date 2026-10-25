package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.config.ContentFileSelector;
import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;
import com.magmaguy.magmacore.scripting.LuaMindModuleRegistry;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
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

/**
 * The pet behavior modules: every .lua file under the pet_behaviors folder, the shipped defaults among them. A
 * default is written when no file of its name exists anywhere in the folder, so a deleted default comes back and an
 * edited one stays, even after it moves into a subfolder. No default is written by a load that could not read the whole
 * folder, since the part it missed may hold an edited one. A module's id is fmm:pet/ and its file name, and a pet file
 * lists modules by that file name. Each load opens a fresh module owner, so an edited module needs no new revision
 * across reloads.
 */
public final class PetBehaviorCatalog implements AutoCloseable {
    public static final String NAMESPACE = "fmm";
    public static final String ID_PREFIX = NAMESPACE + ":pet/";
    /** The shipped modules by file name, each a resource under pet_behaviors/ in the jar. */
    public static final List<String> DEFAULTS = List.of();
    private static final Pattern NAME = Pattern.compile("[a-z0-9._-]+");
    /** Ends a warning about a part of the folder that could not be read. */
    private static final String UNTIL_READ = ", and missing default pet behaviors are not written"
            + " until it can be read.";
    private static final String ALREADY_READ = ": links to a folder that is already being read;"
            + " the pet behaviors in it are skipped.";

    private final LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
    private final LuaMindModuleRegistry.OwnerScope owner = registry.openOwner(NAMESPACE);
    private final Map<String, LuaMindModuleDescriptor> modules = new TreeMap<>();

    private PetBehaviorCatalog() {
    }

    /**
     * Writes the missing defaults through {@code writeDefault}, then loads every module in the folder. A module that
     * cannot load is reported through {@code warn} and skipped; the rest still load. When part of the folder cannot be
     * read, that is reported and no defaults are written.
     * <p>
     * {@code writeDefault} gets a default's name and must write exactly {@code folder.resolve(name + ".lua")},
     * throwing {@link UncheckedIOException} when it cannot. It should write atomically, for example to a temporary
     * file moved into place: a partly written file reads as an owner's edit and is never rewritten.
     */
    public static PetBehaviorCatalog load(Path folder, List<String> defaults, Consumer<String> writeDefault,
                                          Consumer<String> warn) {
        PetBehaviorCatalog catalog = new PetBehaviorCatalog();
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            warn.accept(display(folder, folder) + ": could not create the pet behaviors folder ("
                    + describe(failure, "write") + "); no pet behaviors load.");
            return catalog;
        }
        Found found = luaFiles(folder, warn);
        List<File> files = found.files();
        if (found.complete()) writeMissingDefaults(folder, defaults, writeDefault, warn, files);

        // Every file name that failed to load, so a module needing one of them can say so.
        Set<String> failed = new HashSet<>();
        Map<String, Pending> pending = new LinkedHashMap<>();
        for (File file : ContentFileSelector.select(files, name -> name.toLowerCase(Locale.ROOT))) {
            String where = display(folder, file.toPath());
            String name = stem(file);
            if (!NAME.matcher(name).matches()) {
                warn.accept(where + ": a pet behavior's file name may use only letters, digits, '.', '_' and '-';"
                        + " this module is skipped.");
                continue;
            }
            String source;
            try {
                source = Files.readString(file.toPath());
            } catch (IOException failure) {
                warn.accept(where + ": could not read this file (" + describe(failure, "read")
                        + "); this module is skipped.");
                failed.add(name);
                continue;
            }
            try {
                LuaMindModuleDescriptor declared = LuaMindModuleRegistry.inspectModule(where, source);
                String expected = ID_PREFIX + name;
                if (!declared.identifier().equals(expected)) {
                    warn.accept(where + ": declares id " + declared.identifier() + ", but a module's id is "
                            + ID_PREFIX + " followed by its file name, so it must be " + expected
                            + "; this module is skipped.");
                    failed.add(name);
                    continue;
                }
                pending.put(name, new Pending(where, source, declared.revision(), declared.dependencies()));
            } catch (RuntimeException failure) {
                warn.accept(where + ": " + reason(where, failure) + "; this module is skipped.");
                failed.add(name);
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
                    failures.put(entry.getKey(), reason(module.where(), failure));
                }
            }
        }
        failed.addAll(failures.keySet());
        Set<String> reported = new HashSet<>(failed);
        reported.removeAll(failures.keySet());
        failures.forEach((name, reason) -> {
            Pending module = pending.get(name);
            String missing = missingDependency(name, module, catalog.modules.keySet());
            String note = missing == null || !failed.contains(missing) ? ""
                    : " (it failed to load too, see " + (reported.contains(missing) ? "above" : "below") + ")";
            warn.accept(module.where() + ": " + reason + note + "; this module is skipped.");
            reported.add(name);
        });
        return catalog;
    }

    /**
     * Writes each default no file in the folder already carries the name of, wherever it sits, and adds what it wrote
     * to {@code files}.
     */
    private static void writeMissingDefaults(Path folder, List<String> defaults, Consumer<String> writeDefault,
                                             Consumer<String> warn, List<File> files) {
        Set<String> present = new HashSet<>();
        for (File file : files) present.add(stem(file));
        for (String name : defaults) {
            String key = name.toLowerCase(Locale.ROOT);
            if (present.contains(key)) continue;
            Path file = folder.resolve(name + ".lua");
            String where = display(folder, file);
            // Something at the path that the walk skipped is not replaced. The walk reported every such file, but it
            // read a folder of this name as a folder, so that is reported here.
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(file))
                    warn.accept(where + ": is a folder, not a pet behavior file; this default pet behavior is not"
                            + " written over it and stays missing.");
                continue;
            }
            try {
                writeDefault.accept(name);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(file.toFile());
                    present.add(key);
                }
            } catch (UncheckedIOException failure) {
                warn.accept(where + ": could not write this default pet behavior (" + describe(failure.getCause(), "write")
                        + "); it stays missing until the next load.");
            } catch (RuntimeException failure) {
                warn.accept(where + ": could not write this default pet behavior (" + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage() + "); it stays missing until the next load.");
            }
        }
    }

    /**
     * Every .lua file under the folder, following links, that really lies inside it, and whether the whole folder was
     * read. A folder or file that links out of the folder, or a folder that links back into one already read, is
     * reported and skipped. A folder or file that cannot be read is reported and skipped too, and leaves the read
     * incomplete, since it may hold an edited default. The rest are still read.
     */
    private static Found luaFiles(Path folder, Consumer<String> warn) {
        List<File> files = new ArrayList<>();
        Path root;
        try {
            root = folder.toRealPath();
        } catch (IOException failure) {
            warn.accept(display(folder, folder) + ": could not read the pet behaviors folder ("
                    + describe(failure, "read") + "); no pet behaviors load" + UNTIL_READ);
            return new Found(files, false);
        }
        Set<Path> visited = new HashSet<>();
        var walk = new SimpleFileVisitor<Path>() {
            boolean complete = true;

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String where = display(folder, directory);
                try {
                    Path real = directory.toRealPath();
                    if (!real.startsWith(root)) {
                        warn.accept(where + ": links to a folder outside the pet behaviors folder;"
                                + " the pet behaviors in it are skipped.");
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!visited.add(real)) {
                        warn.accept(where + ALREADY_READ);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                } catch (IOException failure) {
                    warn.accept(where + ": could not read this folder (" + describe(failure, "read")
                            + "); the pet behaviors in it are skipped" + UNTIL_READ);
                    complete = false;
                    return FileVisitResult.SKIP_SUBTREE;
                }
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!isLua(file)) return FileVisitResult.CONTINUE;
                String where = display(folder, file);
                // Links are followed, so a link that still arrives as one leads to nothing the server can reach.
                if (!attributes.isRegularFile()) {
                    warn.accept(where + (attributes.isSymbolicLink()
                            ? ": links to a file that is missing or cannot be reached" : ": is not a regular file")
                            + "; this module is skipped.");
                    return FileVisitResult.CONTINUE;
                }
                // A linked file arrives with its target's attributes, so check where it really lies.
                try {
                    if (file.toRealPath().startsWith(root)) files.add(file.toFile());
                    else warn.accept(where + ": links to a file outside the pet behaviors folder;"
                            + " this module is skipped.");
                } catch (IOException failure) {
                    warn.accept(where + ": could not read this file (" + describe(failure, "read")
                            + "); this module is skipped" + UNTIL_READ);
                    complete = false;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                String where = display(folder, file);
                // A link back into a folder above it: what it leads to is read already.
                if (failure instanceof FileSystemLoopException) {
                    warn.accept(where + ALREADY_READ);
                } else if (Files.isDirectory(file)) {
                    warn.accept(where + ": could not read this folder (" + describe(failure, "read")
                            + "); the pet behaviors in it are skipped" + UNTIL_READ);
                    complete = false;
                } else if (isLua(file)) {
                    warn.accept(where + ": could not read this file (" + describe(failure, "read")
                            + "); this module is skipped" + UNTIL_READ);
                    complete = false;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                if (failure != null) {
                    warn.accept(display(folder, directory) + ": could not finish reading this folder ("
                            + describe(failure, "read") + "); some pet behaviors in it may be skipped" + UNTIL_READ);
                    complete = false;
                }
                return FileVisitResult.CONTINUE;
            }
        };
        try {
            Files.walkFileTree(folder, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, walk);
        } catch (IOException failure) {
            // The visitor handles every failure itself, so this is not expected; keep what was found.
            warn.accept(display(folder, folder) + ": could not read the pet behaviors folder ("
                    + describe(failure, "read") + "); some pet behaviors may be skipped" + UNTIL_READ);
            walk.complete = false;
        }
        return new Found(files, walk.complete);
    }

    private static boolean isLua(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lua");
    }

    /** A file's name without .lua, lower-cased: the name a pet file uses for the module. */
    private static String stem(File file) {
        return file.getName().substring(0, file.getName().length() - ".lua".length()).toLowerCase(Locale.ROOT);
    }

    /**
     * The file name of the dependency a failed registration stopped at: the first one, in declared order, that did not
     * register. Null when a dependency lies outside the fmm:pet/ modules or is the module itself, since the failure is
     * then about that.
     */
    private static String missingDependency(String name, Pending module, Set<String> registered) {
        String missing = null;
        for (String dependency : module.dependencies()) {
            if (!dependency.startsWith(ID_PREFIX)) return null;
            String dependencyName = dependency.substring(ID_PREFIX.length());
            if (dependencyName.equals(name)) return null;
            if (missing == null && !registered.contains(dependencyName)) missing = dependencyName;
        }
        return missing;
    }

    /** Why a file or folder could not be read or written, in words a server owner can act on. */
    private static String describe(IOException failure, String access) {
        if (failure instanceof AccessDeniedException) return "the server may not " + access + " it";
        if (failure instanceof CharacterCodingException) return "it is not saved as UTF-8";
        if (failure instanceof NoSuchFileException) return "it disappeared while loading";
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
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

    private record Pending(String where, String source, long revision, List<String> dependencies) {
    }

    /** The .lua files a walk of the folder found, and whether it read the whole folder. */
    private record Found(List<File> files, boolean complete) {
    }
}
