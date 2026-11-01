package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.IKChain;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationFrame;
import com.magmaguy.freeminecraftmodels.dataconverter.ExpressionTrack;
import com.magmaguy.freeminecraftmodels.dataconverter.IKAnimationFrame;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Animation {
    @Getter
    private final AnimationBlueprint animationBlueprint;
    // Frames per bone in the manager's bone order. A missing track stays null, so that bone rests.
    @Getter
    private final AnimationFrame[][] tracks;
    // MoLang-keyed channels per bone in the same order, null for a bone without any; the whole array is null
    // when the animation has none, so the baked path never looks.
    @Getter
    private final ExpressionTrack[][] expressionTracks;
    // IK animation frames keyed by IKChain instance
    @Getter
    private final HashMap<IKChain, IKAnimationFrame[]> ikAnimationFrames = new HashMap<>();
    @Getter
    private final HashMap<IKChain, ExpressionTrack> ikExpressionTracks = new HashMap<>();
    // Whether sampling assigns MoLang variables, which persist in the entity's context for later evaluations.
    private final boolean writesVariables;

    public Animation(AnimationBlueprint animationBlueprint, Bone[] bones, Skeleton skeleton) {
        this.animationBlueprint = animationBlueprint;

        tracks = new AnimationFrame[bones.length][];
        for (int bone = 0; bone < bones.length; bone++)
            tracks[bone] = animationBlueprint.getAnimationFrames().get(bones[bone].getBoneBlueprint());

        ExpressionTrack[][] expressions = null;
        for (int bone = 0; bone < bones.length; bone++) {
            List<ExpressionTrack> keyed = animationBlueprint.getExpressionTracks().get(bones[bone].getBoneBlueprint());
            if (keyed == null) continue;
            if (expressions == null) expressions = new ExpressionTrack[bones.length][];
            expressions[bone] = keyed.toArray(new ExpressionTrack[0]);
        }
        expressionTracks = expressions;

        // Map IK controller names to IKChain instances
        for (Map.Entry<String, IKAnimationFrame[]> entry : animationBlueprint.getIkAnimationFrames().entrySet()) {
            IKChain chain = skeleton.getIKChain(entry.getKey());
            if (chain != null) ikAnimationFrames.put(chain, entry.getValue());
        }
        for (Map.Entry<String, ExpressionTrack> entry : animationBlueprint.getIkExpressionTracks().entrySet()) {
            IKChain chain = skeleton.getIKChain(entry.getKey());
            if (chain != null) ikExpressionTracks.put(chain, entry.getValue());
        }

        boolean writes = false;
        if (expressionTracks != null)
            for (ExpressionTrack[] channels : expressionTracks)
                if (channels != null) for (ExpressionTrack track : channels) writes |= track.assignsVariables();
        for (ExpressionTrack track : ikExpressionTracks.values()) writes |= track.assignsVariables();
        writesVariables = writes;
    }

    /**
     * Whether sampling this animation changes the entity's MoLang state, so each tick's sample must run in turn.
     * Any other animation's pose depends only on its frame and the entity's queries and can be sampled later.
     */
    public boolean writesVariables() {
        return writesVariables;
    }

    /** Whether any channel or IK goal is sampled per entity rather than read from baked frames. */
    public boolean usesExpressions() {
        return expressionTracks != null || !ikExpressionTracks.isEmpty();
    }
}
