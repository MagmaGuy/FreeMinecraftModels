# ***Before you start!***

FreeMinecraftModels (FMM) is currently in **alpha**! This means that several features are not yet done, and are actively
being worked on.

However, at this moment, the core of the plugin is fully functional - converting bbmodel files, generating resource
packs, spawning entities in-game and managing their animations is all working, if perhaps not 100% polished.

Consider supporting the development at https://www.patreon.com/magmaguy !

The exported resource pack contents are licensed under the CC0 license, no rights reserved. You are free to use,
distribute, modify for any purposes without restrictions or the need for attribution.

# Using this plugin

## What can FreeMinecraftModels (FMM) do for Minecraft server admins?

It can:

- Import .bbmodel or fmmodel (FFM's custom format) models
- Generate resource packs with models that exceed normal Minecraft resource pack model limits (up to ~~112x112x112~~
  106x106x106 units or 7x7x7 in-game blocks)
- Display these models in-game through the use of the command `/fmm spawn static <id>` where the id is the file name of
  the model, in lowercase and without the file extension
- Animate these models as they were configured to be animated in Blockbench
- Handle default state animations without requiring other plugins (walk, idle, death, attack, spawn)

### How do you add an existing model?

To import a model, just drag the .bbmodel to the imports folder and do `/fmm reload`. This will generate a .fmmodel file in the `models` folder and add the model to the resource pack in the `outputs` folder.

***You will need to use that resource pack to view the model correctly!*** It is a normal resource pack, so all you need to do is put it in your resource pack folder. Minecraft servers have a way to host resource packs on third party services such as google drive or a specialized service such as https://resourcepack.host/, that last website might be the easiest way of doing it.

### How do you view the model in-game?

There are two (planned) categories of models.

- `Static` models are for models that do not move (but can have animations), and serve more like decorations - think
  something like a tower or a Christmas tree.
- `Dynamic` models are for models that behave like Minecraft mobs, that is to say they move around and do various
  behaviors associated to mobs. Think something like custom boss models or adding completely new entity types to
  Minecraft.

#### Viewing static models in-game

To view static models in-game, use the command `/fmm spawn static <id>` where the id is the file name of the model, in lowercase and without the file extension.

#### Viewing dynamic models in-game

To view dynamic models in-game, use the command `/fmm spawn dynamic <id>` where the id is the file name of the model, in
lowercase and without the file extension.

## What can FreeMinecraftModels (FMM) do for modelers?

FMM follows the standard resource pack rules for resource pack generation. Furthermore, it tries to be as compatible
with models compatible with ModelEngine as possible in order to try to standardize model creation across plugins.

### Model generation features / restrictions

If you have ever created models for ModelEngine, you will be familiar with a lot of the Minecraft resource pack
generation restrictions:

#### **Cubes:**

Cubes are the same here as they are in Blockbench, they are the cubes that make up the model.

- Cubes can go up to ~~112x112x112~~ 106x106x106 "pixels" (Blockbench units) or 7x7x7 in-game blocks (normal Minecraft
  restrictions bypassed using display sizes, soon to be further bypassed for 1.19.4+ thanks to display entities)
- Legal rotations for cubes are 0, 22.5, -22.5, 45 and -45. No other rotation works.
- Cubes only rotate in one axis, meaning that a rotation of [22.5, 0, 0] is fine, a rotation of [22.5, 0, 45] will not
  fully work and only rotate on one axis.

#### **Bones:**

Bones are what Blockbench calls "groups". They serve to group the cubes together, and should be used to group bones
together for animationsBlueprint.

- Bones can go up to ~~112x112x112~~ 106x106x106 (should be 112, not sure why this is) "pixels" (Blockbench units) or
  7x7x7 in-game blocks. *Please note that the size of bones is set by what they have, so if you have cubes that are more
  than 7 blocks apart, you will probably exceed this size limit. Bypassing this limit is as easy as putting the blocks
  in a different boneBlueprint not contained in the first boneBlueprint!*
- Can have any rotation!

Bones are significantly more flexible than cubes, but you should use as few bones as possible! In FMM, due to Minecraft
limitations, each boneBlueprint is a different entity. At a scale, this will affect performance rather quickly! Always
use as few bones as you can, and be mindful of how many of that model you are planning to spawn - the more of it you
plan to have, the fewer bones you should have!

#### **Virtual Bones**

If you are coming from ModelEngine, you probably want to know if/how virtual bones are implemented in FMM. Virtual bones
have been earmarked, but are not currently implemented beyond very basic groundwork.

However, at the very least, the following virtual bones will be compatible with FMM soon:

- Hitboxes / eye height: a boneBlueprint called "hitbox" with a cubeBlueprint that defines the boundaries, and has the same x and z value (the largest value will be picked if they are not the same) defines the hitbox. The eye level is set at the pivot point of the hitbox's boneBlueprint.
- Name tag: a boneBlueprint whose name starts with "tag_". Honestly I would prefer being mode specific here and going with "tag_name" in order to use tags for other things, but that will be seriously considered later.

No other virtual boneBlueprint feature is guaranteed to be added in the immediate future.

#### **Safer, easier, uneditable file distribution**

One thing that FMM tries to tackle is users repurposing models they have obtained to edit them in ways the model creator did not want them to edit, specifically in order to reskin or otherwise slightly alter a model and potentially try to resell as an original creation.

To that end, FMM uses the `.fmmodel` file format which aims to strip `.bbmodel` files down to the point where they can
be used by the plugin but can not be edited in Blockbench.

As a modeler, you now have the choice whether you want to release an uneditable `.fmmodel` file, an editable `.bbmodel`
file or even do differential pricing or distribution terms of service for the two.

Generating an `.fmmodel` is as simple as putting your `.bbmodel` in the `~/plugins/FreeMinecraftModels/imports` folder
and reloading the plugin with `/fmm reload` or restarting the server. Your `.fmmodel` will then be in
the `~/plugins/FreeMinecraftModels/models` folder.

## What can FreeMinecraftModels (FMM) do for developers who want to integrate it in their plugins?

FMM has a maven repo!
Maven:

```xml

<repository>
    <id>ossrh-public</id>
    <url>https://s01.oss.sonatype.org/content/groups/snapshots/</url>
</repository>

<dependency>
<groupId>com.magmaguy</groupId>
<artifactId>FreeMinecraftModels</artifactId>
<version>1.1.3-SNAPSHOT</version>
<scope>provided</scope>
</dependency>
```

Gradle:

```kotlin
compileOnly group : 'com.magmaguy', name: 'FreeMinecraftModels', version: '1.1.2-SNAPSHOT'
```

*Note FreeMinecraftModels is mean to be used as an API, and will require installation of the plugin on the server. Do
not shade it into your plugin!*

FMM aims to be as easy as possible to use as an API.

Right now, there is only one class you need to know about if you wish to use FMM as an API for your plugin, and that
is `StaticEntity`.

Here is a snippet for handling a static model:

```java
public class FreeMinecraftModelsModel {
    private StaticEntity staticEntity = null;

    //Create the model
    public FreeMinecraftModelsModel(String id, Location location) {
        //This spawns the entity!
        staticEntity = StaticEntity.create(id, location);
        //This checks if the entity spawned correctly
        if (staticEntity == null) Logger.warningMessage("FMM failed to find a model named " + id + " !");
    }

    public void remove() {
        //This removes the entity
        staticEntity.remove();
    }
}
```

Keep in mind that static models are meant to stay in place and act as a decorative element in a fixed location (
animations don't count as 'movement' here). While it is possible to move them, consider whether you might instead want
to use a dynamic model if that is your purpose.

And here is how EliteMobs, my custom bosses plugin, uses dynamic entities:

```java
package com.magmaguy.elitemobs.thirdparty.custommodels.freeminecraftmodels;

import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity;
import com.magmaguy.elitemobs.thirdparty.custommodels.CustomModelInterface;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import lombok.Getter;
import org.bukkit.entity.LivingEntity;

public class CustomModelFMM implements CustomModelInterface {
    @Getter
    private DynamicEntity dynamicEntity;

    public CustomModelFMM(LivingEntity livingEntity, String modelName, String nametagName) {
        dynamicEntity = DynamicEntity.create(modelName, livingEntity);
        if (dynamicEntity == null) return;
        dynamicEntity.setName(nametagName);
    }

    public static void reloadModels() {
        ModeledEntityManager.reload();
    }

    public static boolean modelExists(String modelName) {
        return ModeledEntityManager.modelExists(modelName);
    }

    @Override
    public void shoot() {
        if (dynamicEntity.hasAnimation("attack_ranged")) dynamicEntity.playAnimation("attack_ranged", false);
        else dynamicEntity.playAnimation("attack", false);
    }

    @Override
    public void melee() {
        if (dynamicEntity.hasAnimation("attack_melee")) dynamicEntity.playAnimation("attack_melee", false);
        else dynamicEntity.playAnimation("attack", false);
    }

    @Override
    public void playAnimationByName(String animationName) {
        dynamicEntity.playAnimation(animationName, false);
    }

    @Override
    public void setName(String nametagName, boolean visible) {
        dynamicEntity.setName(nametagName);
        dynamicEntity.setNameVisible(visible);
    }

    @Override
    public void setNameVisible(boolean visible) {
        dynamicEntity.setNameVisible(visible);
    }

    @Override
    public void switchPhase() {
        dynamicEntity.stopCurrentAnimations();
    }
}
```

Dynamic models are built on top of a living entity, which can be provided either when using the create method as in the
example above, or when running the spawn method on a Dynamic entity.

While there is no formal API resource right now, all elements intended for API use are contained within ModeledEntity (
the base class for all entities), StaticEntity, DynamicEntity and ModeledEntityManager. 99% of developers should find
all the methods they need spread across those three classes.

# Contributing to the FreeMinecraftModels (FMM) project as a developer

FMM is distributed under the GPLV3 license and code contributions are welcome. Here are the basic contribution
guidelines:

- Follow the existing naming conventions, maintain the existing level of verbosity and add enough documentation that
  your contribution is easy to understand
- Keep contributions relevant to the scope of the plugin. If you don't know whether it will be relevant, feel free to
  ask ahead of time.
- Be mindful of the performance impact of your code. Some contributions may be turned away if they are either too
  unoptimized or otherwise cause too great of a performance impact.

## General plugin outline

To save you some time, here is a quick breakdown of the logic flow of FMM:

1) Read the `imports` folder
2) Move files from `imports` folder into the `models` folder. If the file is a `.bbmodel`, it gets converted
   to `.fmmodel` in the models folder.
3) Read the files in the `models` folder.
4) Interpret all model structures, creating `Skeleton`s which contain groups of `Bone`s, and these bones contain groups
   of child `Bone`s and `Cube`s. `Cube`s and `Bone`s generate the JSON resource pack data they are each related to. This
   means that `Cube`s generate the JSON specific to cubes and `Bone`s generate the outline and individual boneBlueprint
   files. Note that one boneBlueprint results in one resource pack file. Models are added to a list as they are
   generated.
5) Still in the `Skeleton`, interpret all `Animations` in the model, if any
6) All data has now been initialized, the resource pack was generated in the `outputs` folder and the plugin is ready to
   be used.

## Tricks used in this plugin:

The tricks used here are fairly well-established and standardized, but will be listed nonetheless because they can be counter-intuitive.

Please note that these tricks are all completely invisible to users and model makers; restrictions and workarounds are only listed to help you understand how FMM bypasses various Minecraft limitations.

- All models are scaled up 4x and then the size and pivot point is readjusted in code in order to extend the theoretical maximum size of the model
- Because resource pack models can only have models go from -16 to +32 in size, models are shifted in the background. This is completely invisible to players.
- Leather horse armor is used to create models with a hue that can be influenced through code (i.e. for damage indications). The horse armor must be set to white to display the correct colors!
- Blockbench uses a specific system of IDs for the textures, but actually reads the textures sequentially from config. IDs are assigned here based on their position in the list of textures, following how Blockbench does it.
- Each boneBlueprint is a different armor stand entity due to Minecraft limitations
- Leather horse armor is on the head slot of the armor stand
- Armor stands are used for the default static items. //todo: soon I'll have to implement the new alternative display
  system from MC 1.19.4+, it's way more efficient
- To avoid collisions with other plugins which modify leather horse armor, FMM uses custom model data values starting at
  50,000

# Contributing to the FreeMinecraftModels (FMM) project in general

FMM is actually crowdfunded by the lovely people over at https://www.patreon.com/magmaguy ! All contributions help more than you'd imagine ;)

# Currently planned features:
- Bedrock client RSP generation
- Server properties-independent RSP management with geyser integration
- Custom entities (?)
- tag_projectile as meta bones from which projectiles can be shot (can have more than one per model)

# Current weird limitations that need to be fixed:
- If the pivot point (origin) of a boneBlueprint is set to be over 67ish the model starts floating