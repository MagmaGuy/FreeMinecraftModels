# Transform Pipeline Cleanup — Canonical Minecraft Coordinate System

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the scattered, inconsistent coordinate conversions throughout FMM with a clean pipeline: convert to Minecraft's coordinate system at parse time, use clean math at runtime, convert to entity-specific output at display time.

**Architecture:** Three layers — (1) Parser layer converts Blockbench → Minecraft coordinates at load time, (2) Runtime layer does pure math in Minecraft space with no hacks, (3) Output layer converts from internal matrices to armor stand / display entity specifics. All sign flips, axis conversions, and `rotateAroundY(PI)` hacks are eliminated from the runtime layer and consolidated into parser/output.

**Tech Stack:** JOML Matrix4d (replacing the dual manual+JOML matrix), existing Java codebase.

---

## Context: The Three Coordinate Systems

**Blockbench Editor (v5+):** Right-handed, Y-up. X and Y axes are inverted relative to v4. Positions in 1/16th-block units. Rotations in degrees.

**Blockbench v4 and earlier:** Right-handed, Y-up. No axis inversions. Same unit scale.

**Minecraft:** Y-up, but with specific conventions:
- Armor stands: head rotation uses `EulerAngle(-x, -y, z)` relative to internal
- Display entities (1.20+): rotation uses `EulerAngle(-x, y, -z)` relative to internal
- Entity yaw: 0 = south (+Z), increases clockwise. Needs `-(yaw + 180)` conversion
- Armor stand pivot: 1.438 blocks above feet

## Current Problems (for reference)

1. **AnimationBlueprint** applies BB version-specific sign flips during interpolation (X,Y negated for v5+ rotations; X negated for v5+ positions)
2. **BoneTransforms.rotateAnimation()** creates a Vector, negates Y and Z, then `rotateAroundY(Math.PI)` — a hack to align BB animation space with Minecraft
3. **BoneTransforms.translateAnimation()** negates X
4. **TransformationMatrix** maintains two parallel matrix representations (manual `double[][]` and JOML `Matrix4d`) with identical operations duplicated
5. `rotateAnimation()` and `rotateLocal()` in TransformationMatrix are identical methods
6. **IKChain.applyRotationsToBones()** negates X rotation as a one-off conversion
7. **Output methods** apply different negation patterns for armor stands vs display entities vs pre-1.20

## Plan Overview

| Task | What | Risk |
|------|------|------|
| 1 | Replace TransformationMatrix with pure JOML wrapper | Low — drop-in replacement, same math |
| 2 | Normalize animation data to Minecraft space at parse time | Medium — must get sign conventions exactly right |
| 3 | Normalize bone blueprint data to Minecraft space at parse time | Medium — pivot/center math is delicate |
| 4 | Clean up BoneTransforms runtime pipeline | High — this is where incorrect conversions become visible |
| 5 | Clean up IK pipeline | Medium — IK has its own sign flip |
| 6 | Clean up output methods | Low — isolated, well-understood |
| 7 | Full integration test | Critical — must match current behavior exactly |

---

### Task 1: Replace TransformationMatrix with Pure JOML

**Files:**
- Rewrite: `src/main/java/com/magmaguy/freeminecraftmodels/utils/TransformationMatrix.java`

**Why:** The current class maintains two parallel representations (`double[][]` and `Matrix4d`) doing identical work. The manual matrix is a maintenance liability. JOML is already a dependency and handles all needed operations.

**Step 1: Rewrite TransformationMatrix as a thin JOML wrapper**

The new class keeps the same public API but delegates entirely to `Matrix4d`:

```java
package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class TransformationMatrix {
    private Matrix4d matrix = new Matrix4d();

    public TransformationMatrix() {
        matrix.identity();
    }

    public static void multiplyMatrices(TransformationMatrix first, TransformationMatrix second, TransformationMatrix result) {
        first.matrix.mul(second.matrix, result.matrix);
    }

    public void resetToIdentityMatrix() {
        matrix.identity();
    }

    public void translateLocal(Vector3f vector) {
        translateLocal(vector.x(), vector.y(), vector.z());
    }

    public void translateLocal(float x, float y, float z) {
        matrix.translateLocal(x, y, z);
    }

    public void scale(double x, double y, double z) {
        matrix.scale(x, y, z);
    }

    // Rotation order: Z then Y then X (applied locally)
    public void rotateLocal(double x, double y, double z) {
        matrix.rotateLocalZ(z);
        matrix.rotateLocalY(y);
        matrix.rotateLocalX(x);
    }

    public void rotateY(float angleRadians) {
        matrix.rotateLocalY(angleRadians);
    }

    public void rotateX(float angleRadians) {
        matrix.rotateLocalX(angleRadians);
    }

    public double[] getScale() {
        Vector3d scale = new Vector3d();
        matrix.getScale(scale);
        return new double[]{scale.x, scale.y, scale.z};
    }

    public double[] getTranslation() {
        return new double[]{matrix.m30(), matrix.m31(), matrix.m32()};
    }

    public double[] getRotation() {
        Vector3d euler = new Vector3d();
        matrix.getEulerAnglesXYZ(euler);
        return new double[]{euler.x, euler.y, euler.z};
    }

    public void resetRotation() {
        // Preserve translation, reset rotation and scale to identity
        double tx = matrix.m30(), ty = matrix.m31(), tz = matrix.m32();
        matrix.m00(1).m01(0).m02(0);
        matrix.m10(0).m11(1).m12(0);
        matrix.m20(0).m21(0).m22(1);
        // Translation stays as-is (m30, m31, m32 unchanged)
    }
}
```

**Important notes:**
- JOML's `Matrix4d` stores translation in `m30/m31/m32` (column-major), while the old manual matrix stored it in `matrix[0][3]/[1][3]/[2][3]` (row-major). All `getTranslation()` callers already go through this method, so the change is contained.
- `getRotation()` switches from manual atan2 decomposition to JOML's `getEulerAnglesXYZ()`. Verify the Euler order matches the old behavior (the old code used a ZYX-ish extraction; JOML's XYZ extraction returns the same angles when the rotation was built in ZYX order).
- The `rotateAnimation()` method is removed — it was identical to `rotateLocal()`. All callers should use `rotateLocal()`.
- The `rotateX(double)` / `rotateY(double)` / `rotateZ(double)` public methods are removed. Only `rotateLocal(x,y,z)`, `rotateY(float)`, and `rotateX(float)` remain (the individual ones are only used by `BoneTransforms` for head/yaw).

**Step 2: Update all callers of removed methods**

- `BoneTransforms.rotateAnimation()` currently calls `localMatrix.rotateAnimation(...)` → change to `localMatrix.rotateLocal(...)`
- `BoneTransforms.updateGlobalTransform()` calls `globalMatrix.rotateY(float)` and `globalMatrix.rotateX(float)` — keep these, they're on the new API

**Step 3: Compile and verify**

Run: `mvn package -q`
Expected: Clean compilation, Lombok warnings only

**Step 4: Commit**

```bash
git add -A && git commit -m "refactor: replace TransformationMatrix dual representation with pure JOML Matrix4d"
```

---

### Task 2: Normalize Animation Data to Minecraft Space at Parse Time

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/dataconverter/AnimationBlueprint.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/core/Bone.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/core/BoneTransforms.java`

**Why:** Currently, BB version-specific sign flips happen during animation interpolation (AnimationBlueprint), then *additional* flips happen at runtime (BoneTransforms.rotateAnimation negates Y/Z and rotateAroundY(PI), translateAnimation negates X). We want all conversions at parse time so that AnimationFrame values are already in Minecraft space.

**The key insight:** We need to figure out what the net conversion is from raw BB keyframe data to what actually gets applied to the matrix. Then we apply that full conversion at parse time and remove the runtime hacks.

**Current net conversion for rotations (BB v5+):**
1. Parse: `xRot = -interpolated_x`, `yRot = -interpolated_y`, `zRot = interpolated_z` (AnimationBlueprint lines 241-243)
2. Bone stores: `toRadians(xRot), toRadians(yRot), toRadians(zRot)` (Bone.java line 58)
3. Runtime: `Vector(x, -y, -z).rotateAroundY(PI)` then apply as ZYX rotation (BoneTransforms lines 115-120)

The `rotateAroundY(PI)` on vector `(x, -y, -z)` yields `(-x, -y, z)`.

So the net effect on the matrix rotation input is:
- `matrixX = -toRadians(-interpolated_x) = toRadians(interpolated_x)`
- `matrixY = -toRadians(-interpolated_y) = toRadians(interpolated_y)`
- `matrixZ = toRadians(interpolated_z)`

**For BB v4:**
1. Parse: `xRot = interpolated_x`, `yRot = interpolated_y`, `zRot = interpolated_z`
2. Runtime same transform: Vector(toRadians(x), -toRadians(y), -toRadians(z)).rotateAroundY(PI)
3. Net: `matrixX = -toRadians(x)`, `matrixY = -toRadians(y)`, `matrixZ = toRadians(z)`

**Current net conversion for positions (BB v5+):**
1. Parse: `xPos = -interpolated_x / 16`, `yPos = interpolated_y / 16`, `zPos = interpolated_z / 16`
2. Runtime: translate `(-xPos, yPos, zPos)` (BoneTransforms line 107 negates X)
3. Net: `translateX = interpolated_x / 16`, `translateY = interpolated_y / 16`, `translateZ = interpolated_z / 16`

**For BB v4 positions:**
1. Parse: `xPos = interpolated_x / 16`, `yPos = interpolated_y / 16`, `zPos = interpolated_z / 16`
2. Runtime: translate `(-xPos, yPos, zPos)`
3. Net: `translateX = -interpolated_x / 16`, `translateY = interpolated_y / 16`, `translateZ = interpolated_z / 16`

**Step 1: Update AnimationBlueprint rotation interpolation to output Minecraft-space radians**

In `interpolateRotations()`, replace the version-branching with a single conversion that produces the net Minecraft-space values directly:

For BB v5+:
```java
// Net effect: toRadians(interpolated_x), toRadians(interpolated_y), toRadians(interpolated_z)
animationFramesArray[currentFrame].xRotation = (float) Math.toRadians(interpolateWithType(...));
animationFramesArray[currentFrame].yRotation = (float) Math.toRadians(interpolateWithType(...));
animationFramesArray[currentFrame].zRotation = (float) Math.toRadians(interpolateWithType(...));
```

For BB v4:
```java
// Net effect: -toRadians(x), -toRadians(y), toRadians(z)
animationFramesArray[currentFrame].xRotation = (float) -Math.toRadians(interpolateWithType(...));
animationFramesArray[currentFrame].yRotation = (float) -Math.toRadians(interpolateWithType(...));
animationFramesArray[currentFrame].zRotation = (float) Math.toRadians(interpolateWithType(...));
```

This means AnimationFrame rotation values are now in radians and in Minecraft space. The Bone no longer needs to convert degrees→radians.

**Step 2: Update AnimationBlueprint position interpolation to output Minecraft-space block units**

For BB v5+:
```java
// Net effect: interpolated_x / 16, interpolated_y / 16, interpolated_z / 16
animationFramesArray[currentFrame].xPosition = interpolateWithType(...) / 16f;
animationFramesArray[currentFrame].yPosition = interpolateWithType(...) / 16f;
animationFramesArray[currentFrame].zPosition = interpolateWithType(...) / 16f;
```

For BB v4:
```java
// Net effect: -interpolated_x / 16, interpolated_y / 16, interpolated_z / 16
animationFramesArray[currentFrame].xPosition = -interpolateWithType(...) / 16f;
animationFramesArray[currentFrame].yPosition = interpolateWithType(...) / 16f;
animationFramesArray[currentFrame].zPosition = interpolateWithType(...) / 16f;
```

**Step 3: Update Bone.java to stop converting degrees→radians**

Since AnimationFrame values are now already in radians and Minecraft space:

```java
public void updateAnimationRotation(double x, double y, double z) {
    // Values are already in radians and Minecraft coordinate space
    animationRotation = new Vector3f((float) x, (float) y, (float) z);
}
```

Remove the `Math.toRadians()` calls from `updateAnimationRotation`.

**Step 4: Clean up BoneTransforms runtime methods**

`rotateAnimation()` becomes:
```java
private void rotateAnimation() {
    org.joml.Vector3f effectiveRotation = bone.getEffectiveRotation();
    localMatrix.rotateLocal(
            effectiveRotation.x(),
            effectiveRotation.y(),
            effectiveRotation.z());
}
```

No more Vector creation, no more Y/Z negation, no more `rotateAroundY(PI)`.

`translateAnimation()` becomes:
```java
private void translateAnimation() {
    localMatrix.translateLocal(
            bone.getAnimationTranslation().get(0),
            bone.getAnimationTranslation().get(1),
            bone.getAnimationTranslation().get(2));
}
```

No more X negation.

**Step 5: Update last-frame and boundary-frame handling in AnimationBlueprint**

The same conversion pattern must be applied to the boundary keyframe handling and the "last frame" handling in `interpolateRotations()` (the blocks that handle the final frame and loop boundaries). Apply the identical net conversion to those code paths.

**Step 6: Update IK goal interpolation in AnimationBlueprint**

Apply the same position conversion pattern to `interpolateIKControllerKeyframes()` — same net conversion as positions above.

**Step 7: Compile and verify**

Run: `mvn package -q`

**Step 8: Commit**

```bash
git add -A && git commit -m "refactor: normalize animation data to Minecraft coordinate space at parse time"
```

---

### Task 3: Normalize Bone Blueprint Data to Minecraft Space at Parse Time

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/dataconverter/BoneBlueprint.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/core/BoneTransforms.java`

**Why:** The bone's default rotation (`blueprintOriginalBoneRotation`) is currently stored as raw radians from BB, then applied via `rotateLocal()` at runtime. Since `rotateLocal()` uses ZYX order and the BB rotation data is already in the correct convention for this, there's less conversion needed here — BUT we need to verify this is consistent with the animation rotation convention we just established.

**Key question:** After Task 2, animation rotations go directly into `rotateLocal(x, y, z)`. The default bone rotation also goes into `rotateLocal(x, y, z)` via `rotateDefaultBoneRotation()`. Both need to be in the same convention. Currently bone rotations are `toRadians(bbDegrees)` with no sign flips. We need to verify whether the bone default rotation needs the same conversion as BB v5+ animation rotations.

**Step 1: Verify bone rotation convention**

The bone rotation in `.bbmodel` files follows the same coordinate system as the editor version. Since BB v5+ inverted axes, the bone rotation values from a v5+ model would already be in the inverted space — and the animation sign flips compensate for this.

The bone default rotation and animation rotation are *additive* in the same space. Since the `.bbmodel` origin/rotation values don't carry a version flag (they're just the raw values from the current BB version), and animations from the same model use the same convention, they should be consistent.

**However:** The current code applies `rotateLocal()` to bone rotation with no sign flips, while animation rotation went through the `rotateAroundY(PI)` hack. After Task 2, animation rotation is pre-converted. If bone rotation was working correctly *without* the hack, then it's in a different convention than what we've now made animation rotation be.

This needs careful testing. The approach:
- The bone rotation convention from BB is: X, Y, Z in degrees, same handedness as the editor
- For BB v5+, the animation net conversion ended up as `toRadians(x), toRadians(y), toRadians(z)` — meaning the sign flips in parsing and the runtime hack cancelled out
- The bone rotation is already `toRadians(x), toRadians(y), toRadians(z)` — so they're already in the same convention!

**This means no change is needed to BoneBlueprint.setBoneRotation() for the rotation values themselves.**

**Step 2: Verify pivot and model center calculations**

The `getModelCenter()` and `getBlueprintModelPivot()` values feed into `shiftPivotPoint()`, `translateModelCenter()`, and `shiftPivotPointBack()`. These use `translateLocal()` which has no sign flips. The calculations convert BB units → block units using `1/16 * 2.5`. No coordinate system conversion is applied.

Since translations in BB and Minecraft share the same Y-up convention (just different units), the `/16` scaling is the only needed conversion, and it's already happening. No change needed here either.

**Step 3: Verify entity yaw conversion**

`rotateByEntityYaw()` uses `-(yaw + 180)`. Minecraft yaw: 0 = south, increases clockwise. The model's forward direction needs to face the entity's facing direction. The `+180` offset and negation handle this. This is a Minecraft→Minecraft conversion (entity yaw → model orientation) and is correct as-is.

**Step 4: Document that bone blueprint data is already in Minecraft-compatible space**

Add a brief comment to `BoneBlueprint.setBoneRotation()`:
```java
// Bone rotations from .bbmodel are in the same convention as Minecraft's
// rotation space (ZYX Euler order, same handedness). Only degrees→radians needed.
```

**Step 5: Commit**

```bash
git add -A && git commit -m "refactor: verify and document bone blueprint coordinate conventions"
```

---

### Task 4: Clean Up BoneTransforms Runtime Pipeline

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/core/BoneTransforms.java`

**Why:** After Tasks 1-3, the runtime pipeline should be clean. This task verifies the pipeline reads clearly and removes any remaining dead code.

**Step 1: Verify the clean pipeline reads as expected**

After all changes, `updateLocalTransform()` should be:
```java
public void updateLocalTransform() {
    localMatrix.resetToIdentityMatrix();
    shiftPivotPoint();           // translate by -pivot
    translateModelCenter();       // translate to bone center (relative to parent)
    translateAnimation();         // apply animation position offset
    rotateDefaultBoneRotation(); // apply bone's rest rotation
    rotateAnimation();           // apply animation rotation
    scaleAnimation();            // apply animation scale
    shiftPivotPointBack();       // translate by +pivot
    rotateByEntityYaw();         // rotate root bone by entity facing
}
```

Each method should be a single, clean call with no sign flips or axis hacks.

**Step 2: Verify head bone handling**

In `updateGlobalTransform()`, the head bone override resets rotation and applies head yaw/pitch. The `+180` and negations on yaw/pitch are Minecraft entity conventions, not BB conversions. These stay as-is.

**Step 3: Compile and verify**

Run: `mvn package -q`

**Step 4: Commit**

```bash
git add -A && git commit -m "refactor: clean BoneTransforms runtime pipeline — no coordinate hacks"
```

---

### Task 5: Clean Up IK Pipeline

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/core/IKChain.java`
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/dataconverter/AnimationBlueprint.java` (IK section, if not already handled in Task 2)

**Why:** IKChain.applyRotationsToBones() has `rotation.x = -rotation.x` as a one-off coordinate fix. After normalizing everything to Minecraft space, we need to verify whether this is still needed.

**Step 1: Analyze the IK rotation flow**

The IK solver:
1. Computes joint positions in model space
2. Solves FABRIK to find new positions
3. Converts position deltas to quaternion rotations
4. Converts quaternions to Euler angles
5. Negates X: `rotation.x = -rotation.x`
6. Sets on bone via `bone.setIKRotation(rotation)`

The X negation was needed because the IK solver works in "pure math" space and the output needed to match the convention that `rotateLocal()` expected. After our normalization, IK output should already be in the same space as bone rotations.

**Step 2: Determine if X negation is still needed**

The IK solver uses JOML's quaternion→euler conversion. JOML uses right-handed conventions. The `rotateLocal()` method applies ZYX order. If the IK solver's euler output is in the same convention that `rotateLocal()` consumes, no negation is needed.

Test this by removing the negation and checking if IK animations still work correctly. If they break, the negation compensates for a handedness difference in the IK solver and should stay (but be documented as to why).

**Step 3: If negation stays, document it clearly**

```java
// JOML's quaternionToEuler returns X rotation in opposite handedness
// to our ZYX rotateLocal application. Negate to compensate.
rotation.x = -rotation.x;
```

**Step 4: Compile and verify**

Run: `mvn package -q`

**Step 5: Commit**

```bash
git add -A && git commit -m "refactor: clean up IK rotation pipeline"
```

---

### Task 6: Clean Up Output Methods

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/customentity/core/BoneTransforms.java`

**Why:** The output methods (`getArmorStandEntityRotation`, `getDisplayEntityRotation`) apply entity-type-specific sign negations. These are the *correct* place for Minecraft-specific conversions — they translate from our internal representation to what each entity type expects. But they should be clearly documented.

**Step 1: Document the output conversions**

```java
/**
 * Converts internal rotation to Minecraft armor stand head rotation.
 * Armor stands expect: pitch inverted, yaw inverted, roll as-is.
 */
protected EulerAngle getArmorStandEntityRotation() {
    double[] rotation = globalMatrix.getRotation();
    return new EulerAngle(-rotation[0], -rotation[1], rotation[2]);
}

/**
 * Converts internal rotation to Minecraft display entity rotation.
 * Display entities (1.20+) expect: pitch inverted, yaw as-is, roll inverted.
 */
protected EulerAngle getDisplayEntityRotation() {
    double[] rotation = globalMatrix.getRotation();
    if (VersionChecker.serverVersionOlderThan(20, 0))
        return new EulerAngle(rotation[0], rotation[1], rotation[2]);
    else
        return new EulerAngle(-rotation[0], rotation[1], -rotation[2]);
}
```

**Step 2: Document location output**

Add comments to `getArmorStandTargetLocation()` explaining the `setYaw(180)` and the pivot height subtraction.

**Step 3: Commit**

```bash
git add -A && git commit -m "refactor: document output coordinate conversions for armor stand and display entity"
```

---

### Task 7: Full Integration Test

**Files:**
- All modified files from Tasks 1-6

**Why:** This is the most critical task. The refactor must produce *identical visual output* to the current code. Any difference means a conversion was wrong.

**Step 1: Compile**

Run: `mvn package -q`
Expected: Clean build

**Step 2: Manual in-game test with the desk globe**

1. Deploy the built jar to the 1.21.11 testbed
2. Place the desk model with the globe
3. Verify the globe rotates with its intended tilt (the original bug that started this)
4. Verify static models render correctly (no offset, no rotation errors)
5. Verify animated models render correctly (walk cycles, etc.)
6. Verify head tracking works (head bones follow player look)

**Step 3: Test with multiple models**

Test with at least:
- A static model (no animation)
- A model with walk/idle animation
- The desk with globe (scripted animation with tilted bone)
- A model with IK chains (if available in testbed)
- A model from BB v4 format (if available)

**Step 4: If any visual difference is found**

The worktree approach makes rollback trivial:
```bash
git checkout master  # abandon the branch entirely
```

Or fix the specific conversion and re-test.

**Step 5: If all tests pass, commit and merge**

```bash
git add -A && git commit -m "refactor: transform pipeline cleanup complete — verified in-game"
```

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Net conversion math is wrong for one axis | Medium | High — models render incorrectly | Git worktree for easy rollback, manual testing |
| JOML euler extraction differs from manual code | Low | Medium — rotations off by small amount | Compare old vs new getRotation() output numerically |
| BB v4 models break | Medium | Medium — older models affected | Test with v4 model if available |
| IK chains break | Medium | Medium — IK animations wrong | Test IK models specifically |
| Scale factor chain breaks | Low | High — models wrong size | Verify scale constants are preserved |

## Files Changed Summary

| File | Change Type |
|------|-------------|
| `TransformationMatrix.java` | Rewrite (JOML wrapper) |
| `AnimationBlueprint.java` | Modify (parse-time conversion) |
| `Bone.java` | Modify (remove toRadians) |
| `BoneTransforms.java` | Modify (remove runtime hacks) |
| `IKChain.java` | Modify (verify/document X negation) |
