package com.magmaguy.freeminecraftmodels.seasonal;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeParseException;
import java.util.Collection;

/**
 * Puts a Christmas hat on the head of every living model from December 1st to January 1st.
 *
 * <p>The hat is a bundled model of its own, installed into the models folder like any default, so it goes through
 * the normal resource pack pipeline and an administrator can swap the file for another hat. When the models load,
 * each model with a head gets the hat fitted to it (see {@link HatFit}) as an attachment of its head bone; a model
 * then wears it if it is built in season for a living entity and the config allows it. A model built out of season
 * stays bare until it is built again.
 *
 * <p>Java clients on display entities see the hat. Bedrock clients and armor stand models do not.
 */
public final class ChristmasHats {
    public static final String MODEL_ID = "fmm_christmas_hat";
    public static final String RESOURCE = "models/fmm_default_seasonal/" + MODEL_ID + ".bbmodel";
    /**
     * A date, as 2026-12-25, to use in place of today's when deciding whether hats are in season: a JVM system
     * property, for trying the hats outside December.
     */
    public static final String DATE_PROPERTY = "freeminecraftmodels.date";

    private ChristmasHats() {
    }

    /** Copies the hat into the models folder when it is missing, preserving an administrator's own file. */
    public static void installDefault(JavaPlugin plugin) {
        if (!new File(plugin.getDataFolder(), RESOURCE).isFile()) plugin.saveResource(RESOURCE, false);
    }

    /** Whether a date falls from December 1st to January 1st, both included. */
    public static boolean inSeason(LocalDate date) {
        return date.getMonth() == Month.DECEMBER || date.getMonth() == Month.JANUARY && date.getDayOfMonth() == 1;
    }

    /** Whether a model built now wears the hat fitted to it. */
    public static boolean wornBy(ModeledEntity entity) {
        return (entity instanceof DynamicEntity || entity instanceof PlayerDisguiseEntity)
                && DefaultConfig.christmasHats && inSeason(today());
    }

    private static LocalDate today() {
        String date = System.getProperty(DATE_PROPERTY);
        if (date != null) {
            try {
                return LocalDate.parse(date.trim());
            } catch (DateTimeParseException invalid) {
                // An unreadable date falls back to the real one.
            }
        }
        return LocalDate.now();
    }

    /** Fits the hat to every loaded model with a head. Runs once per model generation, after the models parse. */
    public static void fit(Collection<FileModelConverter> models) {
        FileModelConverter hat = null;
        for (FileModelConverter model : models) if (MODEL_ID.equals(model.getID())) hat = model;
        if (hat == null || hat.getSkeletonBlueprint() == null) return;
        SkeletonBlueprint hatSkeleton = hat.getSkeletonBlueprint();
        HatFit.Brim brim = HatFit.brim(hatSkeleton);
        if (brim == null) {
            Logger.warn("The Christmas hat model " + MODEL_ID + " has no cubes, so no model wears it.");
            return;
        }
        for (FileModelConverter model : models) {
            if (model == hat || model.getSkeletonBlueprint() == null) continue;
            for (BoneBlueprint head : HatFit.heads(model.getSkeletonBlueprint())) {
                HatFit.Placement placement = HatFit.place(head, brim);
                if (placement != null) head.getAttachments().add(wear(head, hatSkeleton, brim, placement));
            }
        }
    }

    private static BoneBlueprint wear(BoneBlueprint head, SkeletonBlueprint hat, HatFit.Brim brim,
                                      HatFit.Placement placement) {
        // Named after its head, so a model with two heads holds two hats apart.
        BoneBlueprint worn = BoneBlueprint.attachmentRoot(MODEL_ID + "_on_" + head.getOriginalBoneName(), head,
                placement.anchor(), brim.anchor(), placement.rotation(), placement.scale());
        for (BoneBlueprint hatRoot : hat.getMainModel()) {
            // The hat's own root only holds cubes when the hat has cubes outside every group.
            if (!hatRoot.getCubeBlueprintChildren().isEmpty()) {
                worn.getBoneBlueprintChildren().add(BoneBlueprint.attachmentCopy(hatRoot, worn, worn));
                continue;
            }
            for (BoneBlueprint bone : hatRoot.getBoneBlueprintChildren())
                if (!bone.isNameTag() && !bone.isMountPoint())
                    worn.getBoneBlueprintChildren().add(BoneBlueprint.attachmentCopy(bone, worn, worn));
        }
        return worn;
    }
}
