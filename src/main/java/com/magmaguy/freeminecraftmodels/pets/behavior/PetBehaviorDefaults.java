package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.MagmaCore;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The shipped behavior modules on disk: writing one into the pet_behaviors folder, as {@link PetBehaviorCatalog#load}
 * asks its writer to, and clearing what an interrupted write left there.
 */
public final class PetBehaviorDefaults {
    /** A temporary file {@link #write} leaves only when the server stops in the middle of writing a default. */
    private static final Pattern LEFTOVER = Pattern.compile("[a-z0-9_]+\\.lua\\.[0-9a-z]{1,13}\\.tmp");
    private static final long LEFTOVER_MILLIS = 60_000L;

    private PetBehaviorDefaults() {
    }

    /**
     * Writes the shipped behavior module {@code name} to {@code folder/name.lua} from the resource
     * pet_behaviors/name.lua that {@code resources} opens (null when there is none). It goes to a temporary file
     * beside it first, which no load reads since its name does not end in .lua, forced to disk, and is then moved into
     * place in one step, or with a plain move that never replaces a file where the atomic move fails. So a crash never
     * leaves a partly written default, which the next load would keep as an owner's edit. Throws
     * {@link UncheckedIOException} when it cannot write; the catalog asks only where no file is.
     */
    public static void write(Path folder, String name, Function<String, InputStream> resources) {
        String resource = "pet_behaviors/" + name + ".lua";
        Path temporary = folder.resolve(name + ".lua."
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36) + ".tmp");
        // Whether the temporary file is ours and still there, so a failure removes it.
        boolean leftOver = false;
        try (InputStream source = resources.apply(resource)) {
            if (source == null) throw new FileNotFoundException("the plugin jar has no " + resource);
            // A file of our own rather than Files.createTempFile, which on Linux would leave the module readable by
            // the server's user only.
            try (FileChannel target = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                leftOver = true;
                source.transferTo(Channels.newOutputStream(target));
                // On disk before the move, so a crash just after it cannot leave an empty module behind.
                target.force(true);
            }
            // Windows can hold a file just written for a moment (Defender, the indexer); MagmaCore retries the move
            // through that, then moves atomically, or else plainly without replacing.
            MagmaCore.movePath(temporary, folder.resolve(name + ".lua"));
            leftOver = false;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } finally {
            if (leftOver) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Its name keeps every load from reading it.
                }
            }
        }
    }

    /**
     * Deletes the temporary files {@link #write} left directly in the folder when a server stopped in the middle of
     * writing a default, once they are a minute old, so one being written now is never touched. Only regular files
     * named exactly as it names them go; anything else stays, and so does what cannot be deleted, which no load reads
     * anyway.
     */
    public static void removeLeftovers(Path folder, long nowMillis) {
        if (!Files.isDirectory(folder)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                if (!LEFTOVER.matcher(file.getFileName().toString()).matches()) continue;
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isRegularFile()
                            && nowMillis - attributes.lastModifiedTime().toMillis() > LEFTOVER_MILLIS)
                        Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // It stays.
                }
            }
        } catch (IOException | DirectoryIteratorException ignored) {
            // The catalog reports a folder it cannot read.
        }
    }
}
