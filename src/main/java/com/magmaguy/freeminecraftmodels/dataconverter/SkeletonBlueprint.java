package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkeletonBlueprint {
    //In BlockBench models are referred to by name for animations, and names are unique
    @Getter
    private final HashMap<String, BoneBlueprint> boneMap = new HashMap<>();
    //Map of bone UUIDs to bone blueprints (for IK chain lookup)
    @Getter
    private final HashMap<String, BoneBlueprint> boneByUuidMap = new HashMap<>();
    //Map of locator UUIDs to locator blueprints
    @Getter
    private final HashMap<String, LocatorBlueprint> locatorMap = new HashMap<>();
    //Map of null object UUIDs to null object blueprints
    @Getter
    private final HashMap<String, NullObjectBlueprint> nullObjectMap = new HashMap<>();
    //List of all IK chains in this model
    @Getter
    private final List<IKChainBlueprint> ikChains = new ArrayList<>();
    @Getter
    private final List<BoneBlueprint> mainModel = new ArrayList<>();
    @Getter
    private String modelName = "Default Name";
    @Getter
    private HitboxBlueprint hitbox;

    public SkeletonBlueprint(List<ParsedTexture> parsedTextures,
                             List outlinerJSON,
                             HashMap<String, Object> values,
                             HashMap<String, Map<String, Object>> locators,
                             HashMap<String, Map<String, Object>> nullObjects,
                             Map<String, Map<String, Object>> textureReferences,
                             String modelName,
                             String pathName,
                             double resolutionWidth,
                             double resolutionHeight) {
        this.modelName = modelName;

        //Create a root bone for everything
        BoneBlueprint rootBone = new BoneBlueprint(modelName, null, this);
        List<BoneBlueprint> rootChildren = new ArrayList<>();

        for (int i = 0; i < outlinerJSON.size(); i++) {
            if (!(outlinerJSON.get(i) instanceof Map<?, ?>)) continue;
            Map<String, Object> bone = (Map<String, Object>) outlinerJSON.get(i);
            if (((String) bone.get("name")).equalsIgnoreCase("hitbox"))
                hitbox = new HitboxBlueprint(bone, values, modelName, null);
            else {
                BoneBlueprint boneBlueprint = new BoneBlueprint(parsedTextures, bone, values, locators, nullObjects, textureReferences, modelName, rootBone, this, resolutionWidth, resolutionHeight);
                rootChildren.add(boneBlueprint);
                if (boneBlueprint.getMetaBone() != null)
                    rootChildren.add(boneBlueprint.getMetaBone());
            }
        }

        rootBone.setBoneBlueprintChildren(rootChildren);
        mainModel.add(rootBone);

        // Build IK chains after all bones, locators, and null objects are parsed
        buildIKChains();
    }

    /**
     * Builds IK chains from null objects that have valid IK configuration.
     * For each null object with ik_source and ik_target, finds the bone chain
     * by walking UP from target to source, then reverses for root-to-tip order.
     */
    private void buildIKChains() {
        for (NullObjectBlueprint nullObj : nullObjectMap.values()) {
            if (!nullObj.hasValidIKConfig()) {
                continue;
            }

            // Resolve ik_source to a bone
            BoneBlueprint sourceBone = boneByUuidMap.get(nullObj.getIkSourceUUID());
            if (sourceBone == null) {
                Logger.warn("IK chain in model " + modelName + ": Could not find source bone with UUID " + nullObj.getIkSourceUUID());
                continue;
            }
            nullObj.setIkSourceBone(sourceBone);

            // Resolve ik_target - could be a locator or a bone
            LocatorBlueprint targetLocator = locatorMap.get(nullObj.getIkTargetUUID());
            BoneBlueprint targetBone = boneByUuidMap.get(nullObj.getIkTargetUUID());

            if (targetLocator == null && targetBone == null) {
                Logger.warn("IK chain in model " + modelName + ": Could not find target with UUID " + nullObj.getIkTargetUUID());
                continue;
            }

            // Find the chain by walking from target to source
            List<BoneBlueprint> chainBones;
            if (targetLocator != null) {
                nullObj.setIkTargetLocator(targetLocator);
                // Start from the locator's parent bone
                BoneBlueprint startBone = targetLocator.getParentBone();
                chainBones = findChainBones(sourceBone, startBone, null);
            } else {
                nullObj.setIkTargetBone(targetBone);
                // Start from the target bone's parent (we don't include the target bone in the chain)
                BoneBlueprint startBone = targetBone.getParent();
                chainBones = findChainBones(sourceBone, startBone, targetBone);
            }

            if (chainBones.isEmpty()) {
                Logger.warn("IK chain in model " + modelName + ": Could not find path from source to target");
                continue;
            }

            // Create the IK chain blueprint
            IKChainBlueprint chain;
            if (targetLocator != null) {
                chain = new IKChainBlueprint(chainBones, targetLocator, nullObj);
            } else {
                chain = new IKChainBlueprint(chainBones, targetBone, nullObj);
            }

            ikChains.add(chain);
        }
    }

    /**
     * Finds the bone chain by walking UP from the target bone to the source bone.
     * The resulting list is reversed to get root-to-tip order.
     * If walking up fails (e.g., sibling bones), tries alternative methods.
     *
     * @param sourceBone       The root bone of the IK chain (ik_source)
     * @param startBone        The bone to start from (target's parent)
     * @param actualTargetBone The actual target bone (for sibling detection), can be null
     * @return List of bones from source to tip, or empty list if no path found
     */
    private List<BoneBlueprint> findChainBones(BoneBlueprint sourceBone, BoneBlueprint startBone, BoneBlueprint actualTargetBone) {
        List<BoneBlueprint> chain = new ArrayList<>();

        // First check if source and actual target are siblings (common case for flat IK setups)
        if (actualTargetBone != null && sourceBone != null && sourceBone.getParent() == actualTargetBone.getParent()) {
            return findSiblingChain(sourceBone, actualTargetBone);
        }

        if (startBone == null) {
            // For sibling bones or when target is a bone without a meaningful parent,
            // try to find chain by walking DOWN from source
            return findChainBonesDownward(sourceBone, actualTargetBone);
        }

        // Walk up from startBone to sourceBone
        BoneBlueprint current = startBone;
        int steps = 0;
        while (current != null) {
            chain.add(current);
            if (current == sourceBone) {
                // Found the source, reverse and return
                java.util.Collections.reverse(chain);
                return chain;
            }
            current = current.getParent();
            steps++;
            if (steps > 100) {
                break;
            }
        }

        // Didn't find source in the parent chain
        // Try walking DOWN from source to find the target
        BoneBlueprint searchTarget = actualTargetBone != null ? actualTargetBone : startBone;
        chain = findChainBonesDownward(sourceBone, searchTarget);
        if (!chain.isEmpty()) {
            return chain;
        }

        return new ArrayList<>();
    }

    /**
     * Finds a chain by walking DOWN from source through children to find target.
     *
     * @param sourceBone The source bone to start from
     * @param targetBone The target bone to find (or null to just return source's descendants)
     * @return List of bones from source to target, or empty list if not found
     */
    private List<BoneBlueprint> findChainBonesDownward(BoneBlueprint sourceBone, BoneBlueprint targetBone) {
        if (sourceBone == null) {
            return new ArrayList<>();
        }

        List<BoneBlueprint> chain = new ArrayList<>();
        chain.add(sourceBone);

        if (targetBone == null) {
            // No specific target, just return the source as a single-bone chain
            return chain;
        }

        // BFS to find path from source to target through children
        if (findPathToTarget(sourceBone, targetBone, chain)) {
            return chain;
        }

        return new ArrayList<>();
    }

    /**
     * Recursively finds a path from current bone to target through children.
     */
    private boolean findPathToTarget(BoneBlueprint current, BoneBlueprint target, List<BoneBlueprint> path) {
        for (BoneBlueprint child : current.getBoneBlueprintChildren()) {
            path.add(child);
            if (child == target) {
                return true;
            }
            if (findPathToTarget(child, target, path)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    /**
     * Creates a chain from sibling bones by finding bones between source and target
     * in their parent's children list.
     *
     * @param sourceBone The source bone
     * @param targetBone The target bone (or its parent for locator targets)
     * @return List of bones forming the chain
     */
    private List<BoneBlueprint> findSiblingChain(BoneBlueprint sourceBone, BoneBlueprint targetBone) {
        List<BoneBlueprint> chain = new ArrayList<>();

        BoneBlueprint parent = sourceBone.getParent();
        if (parent == null) {
            // Both are at root level - check mainModel
            // For root level siblings, we can only reliably include source
            // (we don't know the order or which bones are between)
            chain.add(sourceBone);
            return chain;
        }

        // Find indices of source and target in parent's children
        List<BoneBlueprint> siblings = parent.getBoneBlueprintChildren();
        int sourceIndex = -1;
        int targetIndex = -1;

        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i) == sourceBone) sourceIndex = i;
            if (siblings.get(i) == targetBone) targetIndex = i;
        }

        if (sourceIndex >= 0 && targetIndex >= 0) {
            // Add all bones between source and target (inclusive of source, exclusive of target)
            int start = Math.min(sourceIndex, targetIndex);
            int end = Math.max(sourceIndex, targetIndex);
            for (int i = start; i < end; i++) {
                chain.add(siblings.get(i));
            }
            // Reverse if needed so source is first
            if (sourceIndex > targetIndex) {
                java.util.Collections.reverse(chain);
            }
        } else {
            // Fallback: just use source
            chain.add(sourceBone);
        }

        return chain;
    }

    /**
     * Gets an IK chain by its controller's name.
     *
     * @param controllerName The name of the null object controller
     * @return The IK chain, or null if not found
     */
    public IKChainBlueprint getIKChainByControllerName(String controllerName) {
        for (IKChainBlueprint chain : ikChains) {
            if (chain.getController().getName().equals(controllerName)) {
                return chain;
            }
        }
        return null;
    }
}
