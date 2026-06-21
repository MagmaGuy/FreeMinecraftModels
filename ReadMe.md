# FreeMinecraftModels (FMM)

FreeMinecraftModels is a free, open-source Bukkit/Paper model engine and a drop-in alternative to ModelEngine. It turns Blockbench models into a server resource pack, spawns them in-game using display entities (with an armor-stand fallback for older versions/Bedrock), and animates them -- all without client-side mods.

## Features

- **Blockbench import** -- Reads `.bbmodel` files directly, or the optimized `.fmmodel` format
- **Resource pack generation** -- Automatically builds and zips a server resource pack from imported models
- **Three entity types** -- Static (temporary decorations), Dynamic (living-entity disguises/wrappers), and Props (persistent placed world models)
- **Player disguises** -- Disguise yourself or other players as a model
- **Custom items** -- Models with a `material:` field become placeable/holdable items, with optional Lua scripting
- **Lua scripting** -- Script props and custom items via the MagmaCore scripting engine
- **Menus** -- Player-facing craftable-items browser and an admin content/model browser
- **Crafting recipes** -- Interactive in-game recipe builder for model props
- **Mount points** -- `mount_`-prefixed bones become rideable seat positions; spawn a rideable model horse with one command
- **Display models** -- Place a Blockbench Java Block/Item `.json` export beside a model for 3D item rendering (1.21.4+), including bow/crossbow draw states
- **Oriented hitboxes** -- Hitboxes rotate with the model instead of using vanilla axis-aligned boxes
- **Furniture shop** -- Optional Vault-backed shop for selling props/furniture
- **Region awareness** -- Optional WorldGuard / GriefPrevention checks before placing props
- **API** -- Usable as a dependency by other plugins (EliteMobs, BetterStructures, etc.); fires an `FmmReloadedEvent` so consumers can re-attach models after a reload

## Requirements

- Java 21
- A Spigot/Paper server on Minecraft 1.21.4+ (`api-version: 1.21.4`)
- MagmaCore -- shaded into the plugin jar, no separate install needed

Optional soft dependencies (auto-detected at runtime): **WorldGuard**, **WorldEdit**, **GriefPrevention**, **Vault** (for the furniture shop's economy), and **ResourcePackManager** (FMM tells it to reload so it can distribute the generated pack automatically).

## Installation

1. Drop `FreeMinecraftModels.jar` into your server's `plugins/` folder and start the server once to generate the data folder.
2. Place your `.bbmodel` (or `.fmmodel`) files in `plugins/FreeMinecraftModels/models/`, then run `/fmm reload`.
3. Distribute the generated resource pack from `plugins/FreeMinecraftModels/output/FreeMinecraftModels.zip`, or install [Resource Pack Manager](https://www.spigotmc.org/resources/resource-pack-manager.118574/) to host and serve it automatically.
4. Spawn models in-game, e.g. `/fmm spawn static <id>`, `/fmm spawn dynamic <id>`, or `/fmm spawn prop <id>`.

The data folder also exposes `/fmm setup`, `/fmm initialize`, and `/fmm downloadallcontent` for browsing and installing Nightbreak-managed model packs.

## Commands

All commands are under `/freeminecraftmodels` (alias `/fmm`).

| Command | Description |
| --- | --- |
| `/fmm` | Opens the craftable-items menu; prints info to console |
| `/fmm reload` | Reloads the plugin and re-imports models |
| `/fmm spawn <static\|dynamic\|prop> <model>` | Spawns a model as a static decoration, dynamic entity, or placed prop |
| `/fmm disguise <model> [player]` | Disguises you (or a target player) as a model |
| `/fmm undisguise [player]` | Removes a disguise |
| `/fmm disguiselist` | Lists currently disguised players |
| `/fmm mount <model>` | Spawns a rideable model horse |
| `/fmm itemify <model> <material>` | Gives a placement item for a model, using the chosen material |
| `/fmm giveitem <item>` | Gives a defined custom FMM item |
| `/fmm craftify <model>` | Opens an interactive recipe builder for a model prop |
| `/fmm admin` | Opens the admin content browser |
| `/fmm stats` | Shows loaded-model and dynamic-entity counts |
| `/fmm version` | Shows the plugin version |
| `/fmm setup` / `/fmm initialize` / `/fmm downloadallcontent` | Browse / install Nightbreak-managed content |
| `/fmm downloadall` / `/fmm downloadpluginupdate` | Check plugin updates through Nightbreak |

Debug commands also exist (`hitboxdebug`, `locationdebug`, `bedrockdebug`, `deleteall`).

## Permissions

| Permission | Default | Grants |
| --- | --- | --- |
| `freeminecraftmodels.*` | op | All commands (includes the children below) |
| `freeminecraftmodels.admin` | op | Admin content browser and admin commands |
| `freeminecraftmodels.disguise.self` | op | Disguise/undisguise yourself |
| `freeminecraftmodels.disguise.others` | op | Disguise/undisguise other players, plus `disguiselist` |
| `freeminecraftmodels.bypassregionprotection` | op | Place props inside WorldGuard regions / GriefPrevention claims |
| `freeminecraftmodels.menu` | true | Open the craftable-items menu |
| `freeminecraftmodels.shop` | true | Open the furniture shop |

## Importing and using a model

1. Export your model from Blockbench as a `.bbmodel` (or use a prebuilt `.fmmodel`).
2. Drop the file into `plugins/FreeMinecraftModels/models/` (subfolders are scanned recursively). You may also place sibling `.yml`/`.json`/`.png` files next to a model -- e.g. a Blockbench Java Block/Item `.json` export for a 3D display item.
3. Run `/fmm reload`. FMM converts the model, regenerates `output/FreeMinecraftModels/`, and rezips it to `output/FreeMinecraftModels.zip`.
4. Make sure players receive the generated resource pack (manually or via ResourcePackManager), then spawn/disguise/mount using the commands above. The model ID is the file name without its extension.

Special bones/conventions: bones named `hitbox` and `tag_name` are treated as collision/nametag anchors; `mount_`-prefixed bones become rideable seats.

## Building from source

FreeMinecraftModels is a Maven project (Java 21). MagmaCore and a few other libraries are shaded in at the `package` phase.

```bash
mvn clean package
```

The built plugin jar is written to `target/FreeMinecraftModels.jar`.

## API

FreeMinecraftModels can be used as a dependency in other plugins. Do **not** shade FMM into your plugin -- it must be installed on the server as a standalone plugin.

```xml
<repository>
    <id>magmaguy-repo-releases</id>
    <url>https://repo.magmaguy.com/releases</url>
</repository>

<dependency>
    <groupId>com.magmaguy</groupId>
    <artifactId>FreeMinecraftModels</artifactId>
    <version>2.7.1</version>
    <scope>provided</scope>
</dependency>
```

## Links

- [FreeMinecraftModels on Nightbreak](https://nightbreak.io/plugin/freeminecraftmodels/)
- [Resource Pack Manager (Spigot)](https://www.spigotmc.org/resources/resource-pack-manager.118574/)
- [Patreon](https://www.patreon.com/magmaguy) -- support development
- [Discord](https://discord.gg/nightbreak)

## License

The plugin source code is distributed under the **GPLv3** license.

Exported resource-pack contents are licensed under **CC0** (no rights reserved) -- free to use, distribute, and modify for any purpose without restriction or attribution.
