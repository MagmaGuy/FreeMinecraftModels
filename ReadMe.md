# FreeMinecraftModels (FMM)

FreeMinecraftModels displays custom 3D models in Minecraft using display entities. It converts Blockbench models into resource packs, spawns them in-game, and animates them -- all without client-side mods.

## Features

- **Model formats** -- Import `.bbmodel` files directly or use the optimized `.fmmodel` format
- **Resource pack generation** -- Automatically generates and manages server resource packs
- **Three entity types** -- Static (temporary decorations), Dynamic (living entity disguises), and Props (persistent world models)
- **Custom items** -- Models with a `material:` field become holdable/equippable items with Lua scripting
- **Lua scripting** -- Script props (8 hooks) and custom items (22 hooks) with the MagmaCore scripting engine
- **Menus** -- Player-facing craftable items browser and admin content management menu
- **Crafting recipes** -- Define custom crafting recipes for props and items
- **Mount points** -- `mount_` prefixed bones create rideable seat positions on models
- **Display models** -- Place a Blockbench Java Block/Item `.json` export next to a model for 3D item rendering (1.21.4+)
- **Animations** -- Walk, idle, death, attack, spawn, plus custom animations with IK support
- **Oriented hitboxes** -- Hitboxes rotate with models, unlike vanilla Minecraft AABBs
- **Performance** -- Async model processing with display entities for modern clients

## Quick Start

1. Drop `FreeMinecraftModels.jar` into your `plugins/` folder and restart the server
2. Place `.bbmodel` files in `plugins/FreeMinecraftModels/imports/` and run `/fmm reload`
3. Distribute the generated resource pack from `plugins/FreeMinecraftModels/output/FreeMinecraftModels.zip` (or install [Resource Pack Manager](https://www.spigotmc.org/resources/resource-pack-manager.118574/) for automatic handling)
4. Spawn models with `/fmm spawn static <id>`, `/fmm spawn dynamic <id>`, or `/fmm spawn prop <id>`

## Documentation

Full documentation is available on the [Nightbreak Wiki](https://nightbreak.io/wiki/freeminecraftmodels).

## Links

- [Nightbreak](https://nightbreak.io)
- [Patreon](https://www.patreon.com/magmaguy) -- Support development
- [Discord](https://discord.gg/nightbreak)

## API

FreeMinecraftModels can be used as a dependency in other plugins. See the [API & Developer Guide](https://nightbreak.io/wiki/freeminecraftmodels/api_and_developer_guide) for full details.

```xml
<repository>
    <id>magmaguy-repo-releases</id>
    <url>https://repo.magmaguy.com/releases</url>
</repository>

<dependency>
    <groupId>com.magmaguy</groupId>
    <artifactId>FreeMinecraftModels</artifactId>
    <version>LATEST.VERSION.HERE</version>
    <scope>provided</scope>
</dependency>
```

Do not shade FMM into your plugin -- it must be installed on the server as a standalone plugin.

## License

The exported resource pack contents are licensed under CC0 -- no rights reserved. You are free to use, distribute, and modify them for any purpose without restrictions or attribution.

The plugin source code is distributed under the GPLV3 license.
