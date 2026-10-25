package com.magmaguy.freeminecraftmodels.pets.behavior;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetBehaviorCatalogTest {
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    @TempDir
    Path folder;
    private final List<String> warnings = new ArrayList<>(), written = new ArrayList<>();

    static String module(String name, String settings) {
        return "return ai.module { id = 'fmm:pet/" + name + "', revision = 1, settings = { " + settings + " }, "
                + "behaviors = { ai.behavior { id = 'fmm:pet/" + name + "', tick = function(c) end } } }";
    }

    private void write(String relative, String source) {
        try {
            Path file = folder.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private PetBehaviorCatalog load(List<String> defaults) {
        return PetBehaviorCatalog.load(folder, defaults, name -> {
            written.add(name);
            write(name + ".lua", module(name, "radius = 12"));
        }, warnings::add);
    }

    @Test
    void writesMissingDefaultsAndKeepsEditedOnes() {
        write("drift.lua", module("drift", "radius = 3"));
        try (PetBehaviorCatalog catalog = load(List.of("drift", "hover"))) {
            assertEquals(List.of("hover"), written);
            assertEquals(Set.of("drift", "hover"), catalog.names());
            assertEquals(3D, catalog.module("drift").orElseThrow().settings().get("radius"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aDefaultMovedIntoASubfolderIsNotWrittenAgain() {
        write("mine/drift.lua", module("drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of("drift"))) {
            assertEquals(List.of(), written);
            assertEquals(Set.of("drift"), catalog.names());
            assertEquals(5D, catalog.module("drift").orElseThrow().settings().get("radius"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aDefaultMatchesAFileOfItsNameInAnyCase() {
        write("mine/Drift.lua", module("drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of("drift"))) {
            assertEquals(List.of(), written);
            assertEquals(Set.of("drift"), catalog.names());
            assertEquals(5D, catalog.module("drift").orElseThrow().settings().get("radius"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aDefaultThatCannotBeWrittenIsReportedAndTheRestLoad() {
        write("hover.lua", module("hover", "height = 1"));
        try (PetBehaviorCatalog catalog = PetBehaviorCatalog.load(folder, List.of("drift"), name -> {
            throw new UncheckedIOException(new AccessDeniedException(folder.resolve(name + ".lua").toString()));
        }, warnings::add)) {
            assertEquals(Set.of("hover"), catalog.names());
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith(folder.getFileName() + "/drift.lua: ")
                && warnings.getFirst().contains("may not write it"), warnings.getFirst());
    }

    @Test
    void aFolderInTheWayOfADefaultIsReported() throws IOException {
        Files.createDirectory(folder.resolve("drift.lua"));
        try (PetBehaviorCatalog catalog = load(List.of("drift"))) {
            assertEquals(List.of(), written);
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith(folder.getFileName() + "/drift.lua: is a folder"),
                warnings.getFirst());
    }

    @Test
    void aLinkToNothingIsReportedAndNotWrittenThrough() throws IOException {
        symlink(folder.resolve("drift.lua"), folder.resolve("gone.lua"));
        try (PetBehaviorCatalog catalog = load(List.of("drift"))) {
            assertEquals(List.of(), written);
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith(folder.getFileName() + "/drift.lua: links to a file that is missing"),
                warnings.getFirst());
    }

    @Test
    void aLinkedFolderIsReadThroughTheLink() throws IOException {
        write("store/mine/drift.lua", module("drift", "radius = 5"));
        Path linked = folder.resolve("pet_behaviors");
        symlink(linked, folder.resolve("store"));
        try (PetBehaviorCatalog catalog = PetBehaviorCatalog.load(linked, List.of("drift"), written::add,
                warnings::add)) {
            assertEquals(List.of(), written);
            assertEquals(5D, catalog.module("drift").orElseThrow().settings().get("radius"));
        } finally {
            Files.delete(linked);
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFolderLinkingBackAboveItselfIsReadOnce() throws IOException, InterruptedException {
        write("mine/drift.lua", module("drift", "radius = 5"));
        Path loop = folder.resolve("mine/loop");
        linkFolder(loop, folder);
        try (PetBehaviorCatalog catalog = load(List.of("drift", "hover"))) {
            assertEquals(List.of("hover"), written);
            assertEquals(Set.of("drift", "hover"), catalog.names());
        } finally {
            unlinkFolder(loop);
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith(folder.getFileName()
                + "/mine/loop: links to a folder that is already being read"), warnings.getFirst());
    }

    @Test
    void aFolderThatCannotBeReadKeepsEveryDefaultUnwritten() throws IOException {
        write("mine/drift.lua", module("drift", "radius = 5"));
        Undo undo = denyListing(folder.resolve("mine"));
        try (PetBehaviorCatalog catalog = load(List.of("drift", "hover"))) {
            assertEquals(List.of(), written);
            assertTrue(catalog.names().isEmpty());
        } finally {
            undo.run();
        }
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith(folder.getFileName()
                + "/mine: could not read this folder (the server may not read it)")
                && warnings.getFirst().contains("not written until it can be read"), warnings.getFirst());
    }

    @Test
    void loadsUserModulesInSubfolders() {
        write("mine/my_drift.lua", module("my_drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertEquals(Set.of("my_drift"), catalog.names());
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFileNameOutsideTheNameRuleIsSkipped() {
        write("my drift.lua", module("my_drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("my drift.lua") && warnings.getFirst().contains("letters"),
                warnings.getFirst());
    }

    @Test
    void aFileNotSavedAsUtf8IsSkipped() throws IOException {
        Files.write(folder.resolve("latin.lua"), new byte[]{'-', '-', ' ', (byte) 0xE9, '\n'});
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("UTF-8"), warnings.getFirst());
    }

    @Test
    void aModulesIdMustMatchItsFileName() {
        write("my_drift.lua", module("drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("fmm:pet/my_drift"), warnings.getFirst());
    }

    @Test
    void aBrokenModuleIsSkippedAndTheRestLoad() {
        write("broken.lua", "return ai.module { id = ");
        write("hover.lua", module("hover", "height = 1"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertEquals(Set.of("hover"), catalog.names());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("broken.lua") && warnings.getFirst().contains("line 1"),
                warnings.getFirst());
    }

    @Test
    void aModuleNeedingOneThatFailedSaysSo() {
        write("a.lua", "return ai.module { id = 'fmm:pet/a', revision = 1, dependencies = { 'fmm:pet/broken' } }");
        write("broken.lua", "return ai.module { id = ");
        write("c1.lua", "return ai.module { id = 'fmm:pet/c1', revision = 1, dependencies = { 'fmm:pet/c2' } }");
        write("c2.lua", "return ai.module { id = 'fmm:pet/c2', revision = 1, dependencies = { 'fmm:pet/c1' } }");
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith(folder.getFileName() + "/broken.lua: "), warnings.get(0));
        assertTrue(warnings.get(1).contains("fmm:pet/broken (it failed to load too, see above)"), warnings.get(1));
        assertTrue(warnings.get(2).contains("fmm:pet/c2 (it failed to load too, see below)"), warnings.get(2));
        assertTrue(warnings.get(3).contains("fmm:pet/c1 (it failed to load too, see above)"), warnings.get(3));
    }

    @Test
    void dependenciesLoadWhateverTheFileOrder() {
        write("a.lua", "return ai.module { id = 'fmm:pet/a', revision = 1, dependencies = { 'fmm:pet/z' } }");
        write("z.lua", "return ai.module { id = 'fmm:pet/z', revision = 1 }");
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertEquals(Set.of("a", "z"), catalog.names());
        }
        assertEquals(List.of(), warnings);
    }

    /** Makes a symbolic link, aborting the test where this account may not, as on Windows outside developer mode. */
    private static void symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException failure) {
            Assumptions.abort("this account may not make symbolic links (" + failure.getMessage() + ")");
        }
    }

    /** Links a folder to another: a junction on Windows, which needs no privilege, elsewhere a symbolic link. */
    private static void linkFolder(Path link, Path target) throws IOException, InterruptedException {
        if (!WINDOWS) {
            Files.createSymbolicLink(link, target);
            return;
        }
        assertEquals(0, new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(),
                "mklink /J " + link);
    }

    /** Removes a folder link on its own, never what it leads to. */
    private static void unlinkFolder(Path link) throws IOException, InterruptedException {
        if (!WINDOWS) {
            Files.delete(link);
            return;
        }
        assertEquals(0, new ProcessBuilder("cmd", "/c", "rmdir", link.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(),
                "rmdir " + link);
    }

    private interface Undo {
        void run() throws IOException;
    }

    /**
     * Stops this account listing a folder until undone, aborting the test where it cannot, as for root on Linux. On
     * Windows a deny entry for the folder's owner does it, which needs no privilege.
     */
    private static Undo denyListing(Path directory) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        Undo undo;
        if (acl != null) {
            List<AclEntry> original = acl.getAcl();
            List<AclEntry> denied = new ArrayList<>(original);
            denied.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(directory))
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
            acl.setAcl(denied);
            undo = () -> acl.setAcl(original);
        } else {
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
            Files.setPosixFilePermissions(directory, Set.of());
            undo = () -> Files.setPosixFilePermissions(directory, original);
        }
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
            undo.run();
            return Assumptions.abort("this account can still list " + directory + " after being denied");
        } catch (AccessDeniedException expected) {
            return undo;
        }
    }
}
