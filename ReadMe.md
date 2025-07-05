# ***Before you start!***

FreeMinecraftModels (FMM) is currently in **active development**! This means that some features are not yet done, and
are actively
being worked on.

However, at this moment, the core of the plugin is fully functional - converting bbmodel files, generating resource
packs, spawning entities in-game and managing their animations, the ability to place persistent prop, is all mostly
working.

Consider supporting the development at https://www.patreon.com/magmaguy !

The exported resource pack contents are licensed under the CC0 license on FreeMinecraftModels' end, no rights reserved.
You are free to use,
distribute, modify for any purposes without restrictions or the need for attribution.

# Using this plugin

## What can FreeMinecraftModels (FMM) do for Minecraft server admins?

It can:

- Import .bbmodel or fmmodel (FFM's custom format) models
- Generate resource packs with models that exceed normal Minecraft resource pack model limits (up to 112x112x112 units
  or 7x7x7 in-game blocks, functionally unlimited when using multiple bones)
- Display these models in-game, sending specific bedrock-compatible packets to bedrock clients while also sending
  display entities to 1.19.4+ java clients
- Animate these models as they were configured to be animated in Blockbench
- Handle default state animations without requiring other plugins (walk, idle, death, attack, spawn)
- Handle hitboxes that rotate with the underlying entity and have a different x and z axis
- Manage three types of models: static, dynamic and props
    - Props are persistent and can be placed in the world in such a way that will persist even if the server is
      restarted, and it is possible to distribute maps with props to other servers
    - Dynamic models are for models that need an underlying living entity to function, ideally used by custom boss
      plugins or pets plugins
    - Static models are for non-persistent models that should not move around, so basically temporary decorations or
      effects

### How do you add an existing model?

To import a model, just drag the .bbmodel to the imports folder and do `/fmm reload`. This will generate a .fmmodel file
in the `models` folder and add the model to the resource pack in the `outputs` folder.

***You will need to use that resource pack to view the model correctly!*** It is a normal resource pack, so all you need
to do is put it in your resource pack folder. Minecraft servers have a way to host resource packs. I recommend using my
plugin, [ResourcePackManager](https://www.spigotmc.org/resources/resource-pack-manager.118574/), which automatically
grabs the files and hosts them remotely for you, even merging them with the files of other plugins.

### How do you view the model in-game?

It is important to note that while FreeMinecraftModels can be used as a standalone plugin for viewing props (basically
custom models that you can place in the world), the plugin is usually at its best when paired with a plugin such
as [EliteMobs](https://www.spigotmc.org/resources/elitemobs.40090/) where the models are actively used for something
concrete, in this case boss fights.

There are three types of models: static, dynamic and props.

- Props are persistent and can be placed in the world in such a way that will persist even if the server is restarted,
  and it is possible to distribute maps with props to other servers
- Dynamic models are for models that need an underlying living entity to function, ideally used by custom boss plugins
  or pets plugins
- Static models are for non-persistent models that should not move around, so basically temporary decorations or effects

#### Viewing static models in-game

To view static models in-game, use the command `/fmm spawn static <id>` where the id is the file name of the model, in
lowercase and without the file extension.

#### Viewing dynamic models in-game

To view dynamic models in-game, use the command `/fmm spawn dynamic <id>` where the id is the file name of the model, in
lowercase and without the file extension.

#### Viewing props in-game

To view dynamic models in-game, use the command `/fmm spawn prop <id>` where the id is the file name of the model, in
lowercase and without the file extension.

## What can FreeMinecraftModels (FMM) do for modelers?

FMM follows the standard resource pack rules for resource pack generation. Furthermore, it tries to be as compatible
with models compatible with ModelEngine as possible in order to try to standardize model creation across plugins.

### Model generation features / restrictions

If you have ever created models for ModelEngine, you will be familiar with a lot of the Minecraft resource pack
generation restrictions:

#### **Cubes:**

Cubes are the same here as they are in Blockbench, they are the cubes that make up the model.

- Cubes can go up to 112x112x112 "pixels" (Blockbench units) or 7x7x7 in-game blocks (normal Minecraft
  restrictions bypassed using display sizes, soon to be further bypassed for 1.19.4+ thanks to display entities)
- Minecraft rotations for cubes are 0, 22.5, -22.5, 45 and -45.
- As of FMM 2.3.0, it is also possible to do any multiple of 22.5 for rotations, which will automatically be converted
  by the plugin, though not recommended. Especially for +90 and -90, or rotations around those values, there will be
  problems with flipped textures that may or may not be fixed in the future.
- Cubes only rotate in one axis, meaning that a rotation of [22.5, 0, 0] is fine, a rotation of [22.5, 0, 45] will not
  fully work and only rotate on one axis.

#### **Bones:**

Bones are what Blockbench calls "groups". They serve to group the cubes together, and should be used to group bones
together for animationsBlueprint.

- Bones can go up to 112x112x112 "pixels" (Blockbench units) or
  7x7x7 in-game blocks. *Please note that the size of bones is set by what they have, so if you have cubes that are more
  than 7 blocks apart, you will probably exceed this size limit. Bypassing this limit is as easy as putting the blocks
  in a different boneBlueprint not contained in the first boneBlueprint!*
- Can have any rotation! However, it is recommended to avoid using default rotations of 90, -90, 180 and -180, as these
  can often lead to unexpected behavior. Note that this does not really apply to animations, just the default resting
  position of the bones.

Bones are significantly more flexible than cubes, but you should use as few bones as possible! In FMM, due to Minecraft
limitations, each bone is a different entity. At a scale, this will affect performance rather quickly! Always
use as few bones as you can, and be mindful of how many of that model you are planning to spawn - the more of it you
plan to have, the fewer bones you should have!

#### **Virtual Bones**

Virtual Bones is model engine terminology for bones that have a specific metadata, usually in the form of a specific
name, which is used for a specific purpose.

The following virtual bones have been implemented in FreeMinecraftModels:

- Hitboxes / eye height: a bone called "hitbox" with a cubeBlueprint that defines the boundaries, and has the same x and
  z value (the largest value will be picked if they are not the same) defines the hitbox. The eye level is set at the
  pivot point of the hitbox's boneBlueprint.
- Name tag: a bone whose name starts with "tag_". Honestly I would prefer being mode specific here and going with "
  tag_name" in order to use tags for other things, but that will be seriously considered later.
- Head: a bone whose name starts with h_ . This is a virtual bone that is used to define the head of the model, which
  will rotate based on the rotation of the head of the underlying entity.

#### **Safer, easier, uneditable file distribution**

One thing that FMM tries to tackle is users repurposing models they have obtained to edit them in ways the model creator
did not want them to edit, specifically to reskin or otherwise slightly alter a model and potentially try to
resell as an original creation.

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
    <id>magmaguy-repo-releases</id>
    <name>MagmaGuy's Repository</name>
    <url>https://repo.magmaguy.com/releases</url>
</repository>

<dependency>
<groupId>com.magmaguy</groupId>
<artifactId>FreeMinecraftModels</artifactId>
<version>LATEST.VERSION.HERE</version>
<scope>provided</scope>
</dependency>
```

Gradle:

```kotlin
maven {
    name = "magmaguyRepoReleases"
    url = uri("https://repo.magmaguy.com/releases")
}

compileOnly group : 'com.magmaguy', name: 'FreeMinecraftModels', version: 'LATEST.VERSION.HERE'
```

*Note FreeMinecraftModels is meant to be used as an API, and will require installation of the plugin on the server. Do
not shade it into your plugin!*

## API usage

FMM aims to be as easy as possible to use as an API.

Right now, if you wish to use FreeMinecraftModels as an API to have access to using custom models, there's only four
classes you need to know about:

- `ModeledEntity` - the base class for all entities
- `StaticEntity` - for when you want to use a non-permanent static model
- `DynamicEntity` - for when you want to disguise another living entity with a model
- `PropEntity` - for when you want to place a model in the world that persists even if the server is restarted

Here is a snippet for handling a static model:

```java
import org.bukkit.Bukkit;

public class FreeMinecraftModelsModel {
    private StaticEntity staticEntity = null;

    //Create the model
    public FreeMinecraftModelsModel(String id, Location location) {
        //This spawns the entity!
        staticEntity = StaticEntity.create(id, location);
        //This checks if the entity spawned correctly
        if (staticEntity == null) Bukkit.getLogger().warning(("FMM failed to find a model named " + id + " !"));
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

import com.magmaguy.elitemobs.thirdparty.custommodels.CustomModelInterface;
import api.com.magmaguy.freeminecraftmodels.ModeledEntityManager;
import customentity.com.magmaguy.freeminecraftmodels.DynamicEntity;
import lombok.Getter;
import org.bukkit.entity.LivingEntity;

public class CustomModelFMM implements CustomModelInterface {
    @Getter
    private final DynamicEntity dynamicEntity;

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

### Click events

FreeMinecraftModels provides custom events for when a player left of right-clicks a modeled entity, or enters its
hitbox.

Those are events are:

- `ModeledEntityLeftClickEvent`
- `ModeledEntityRightClickEvent`
- `ModeledEntityHitboxContactEvent` (entity must be set to tick collision checks if not dynamic)
- `PropLeftClickEvent`
- `PropRightClickEvent`
- `PropEntityHitboxContactEvent` (entity must be set to tick collision checks)
- `StaticEntityLeftClickEvent`
- `StaticEntityRightClickEvent`
- `StaticEntityHitboxContactEvent` (entity must be set to tick collision checks)
- `DynamicEntityLeftClickEvent`
- `DynamicEntityRightClickEvent`
- `DynamicEntityHitboxContactEvent`

They are all `Cancellable` and can be cancelled. Additionally, they all have a `Player` as their first parameter, and
expose the entity that was clicked.

Note that cancelling the events will not do anything as far as FreeMinecraftModels is concerned, as FMM does not handle
the actual interaction with the model at this time.

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
   to `.fmmodel` in the `models` folder.
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

The tricks used here are fairly well-established and standardized, but will be listed nonetheless because they can be
counter-intuitive.

Please note that these tricks are all completely invisible to users and model makers; restrictions and workarounds are
only listed to help you understand how FMM bypasses various Minecraft limitations.

- All models are scaled up 4x and then the size and pivot point is readjusted in code in order to extend the theoretical
  maximum size of the model
- Because resource pack models can only have models go from -16 to +32 in size, models are shifted in the background.
  This is completely invisible to players.
- Leather horse armor is used to create models with a hue that can be influenced through code (i.e. for damage
  indications). The horse armor must be set to white to display the correct colors!
- Blockbench uses a specific system of IDs for the textures, but actually reads the textures sequentially from config.
  IDs are assigned here based on their position in the list of textures, following how Blockbench does it.
- Each bone is a different entity due to Minecraft limitations
- Leather horse armor is on the head slot of the armor stand
- Both armor stands and display entities are used for the default static items; bedrock clients get the armor stands,
  and 1.19.4+ clients get the display entities (older clients will get armor stands)

# Contributing to the FreeMinecraftModels (FMM) project in general

FMM is actually crowdfunded by the lovely people over at https://www.patreon.com/magmaguy ! All contributions help more
than you'd imagine ;)

# Currently planned features:

- Bedrock client RSP generation
- RSP management with geyser integration
- tag_projectile as meta bones from which projectiles can be shot (can have more than one per model)

# Current weird limitations that it would be nice to fix:

- The TransformationMatrix is a mess, but no better solutions have been developed yet. They need some work from someone
  who is good at matrices.