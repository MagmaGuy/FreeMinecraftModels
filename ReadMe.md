# FreeMinecraftModels (FMM)

FreeMinecraftModels is a free, open-source Bukkit/Paper model engine. It turns Blockbench models into a server resource pack, spawns them in-game using display entities (with an armor-stand fallback for older versions/Bedrock), and animates them: all without client-side mods.

[Download](https://nightbreak.io/plugin/freeminecraftmodels/) · [Modrinth](https://modrinth.com/plugin/free-minecraft-models) · [Documentation](https://wiki.nightbreak.io/) · [Support](https://discord.gg/nightbreak)

## Features

- **Blockbench import**: Reads `.bbmodel` files directly, or the optimized `.fmmodel` format
- **Resource pack generation**: Automatically builds and zips a server resource pack from imported models
- **Three entity types**: Static (temporary decorations), Dynamic (living-entity disguises/wrappers), and Props (persistent placed world models)
- **Player disguises**: Disguise yourself or other players as a model
- **Custom items**: Models with a `material:` field become placeable/holdable items, with optional Lua scripting
- **Lua scripting**: Script props and custom items via the MagmaCore scripting engine
- **Menus**: Player-facing craftable-items browser and an admin content/model browser
- **Crafting recipes**: Interactive in-game recipe builder for model props
- **Mount points**: `mount_`-prefixed bones become rideable seat positions; spawn a rideable model horse with one command
- **Display models**: Place a Blockbench Java Block/Item `.json` export beside a model for 3D item rendering (1.21.4+), including bow/crossbow draw states
- **Oriented hitboxes**: Hitboxes rotate with the model instead of using vanilla axis-aligned boxes
- **Furniture shop**: Optional Vault-backed shop for selling props/furniture
- **Region awareness**: Optional WorldGuard / GriefPrevention checks before placing props
- **API**: Usable as a dependency by other plugins (EliteMobs, BetterStructures, etc.); fires an `FmmReloadedEvent` so consumers can re-attach models after a reload

## Requirements

- Java 21 or the newer Java version required by your server
- A Spigot/Paper server on Minecraft 1.21.4+ (`api-version: 1.21.4`)
- MagmaCore: shaded into the plugin jar, no separate install needed

Optional integrations include: **WorldGuard**, **WorldEdit**, **GriefPrevention**, **Vault** (for the furniture shop's economy), **Geyser/Floodgate** for Bedrock identification, and **ResourcePackManager** for distributing the generated pack. Check the download page for server-version compatibility; Bedrock rendering uses a different backend and should be checked with your actual models.

## Installation

1. Drop `FreeMinecraftModels.jar` into your server's `plugins/` folder and start the server once to generate the data folder.
2. Place your `.bbmodel` (or `.fmmodel`) files in `plugins/FreeMinecraftModels/models/`, then run `/fmm reload`.
3. Distribute the generated resource pack from `plugins/FreeMinecraftModels/output/FreeMinecraftModels.zip`, or install [Resource Pack Manager](https://www.spigotmc.org/resources/resource-pack-manager.118574/) to host and serve it automatically.
4. Spawn models in-game, e.g. `/fmm spawn static <id>`, `/fmm spawn dynamic <id>`, or `/fmm spawn prop <id>`.

The plugin also exposes `/fmm setup`, `/fmm initialize`, and `/fmm downloadallcontent` for browsing and installing Nightbreak-managed model packs.

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
2. Drop the file into `plugins/FreeMinecraftModels/models/` (subfolders are scanned recursively). You may also place sibling `.yml`/`.json`/`.png` files next to a model: e.g. a Blockbench Java Block/Item `.json` export for a 3D display item.
3. Run `/fmm reload`. FMM converts the model, regenerates `output/FreeMinecraftModels/`, and rezips it to `output/FreeMinecraftModels.zip`.
4. Make sure players receive the generated resource pack (manually or via ResourcePackManager), then spawn/disguise/mount using the commands above. The model ID is the file name without its extension.

Special bones/conventions: bones named `hitbox` and `tag_name` are treated as collision/nametag anchors; `mount_`-prefixed bones become rideable seats.

## Equipment, interactions, and names

FMM also supports custom enchantments, authored weapon abilities, and magic staff and wand projectiles, with EliteMobs combat integration. Custom item definitions and model configuration live in the generated plugin data folder; keep related model, item, and resource-pack files together when sharing content.

Model right-clicks have their own permission decision. `ModeledEntityInteractEvent` lets Bukkit entity-protection listeners check the backing entity before FMM dispatches the cancellable `ModeledEntityRightClickEvent`. Cancelling a block interaction does not by itself grant or deny interaction with a model in front of it.

`ModeledEntity.setDisplayName` accepts explicit newlines, and `setDisplayNameLines` accepts rows in reading order. Additional lines extend above the bottom anchor. A model's nametag bone supplies its anchor when present. Java and Bedrock use different text renderers; native Bedrock labels do not support Java text scaling.

## Troubleshooting

If a model is invisible, confirm it appears in the loaded model list and that the player accepted the newly generated pack. Check import errors for the specific model before changing hosting settings. For placement or interaction failures, inspect protection permissions and plugin integration logs.

Include the model and its relevant configuration, the full import/runtime error, plugin and server versions, and whether the affected client is Java or Bedrock. Do not assume a model that renders correctly on one client platform has identical behavior on the other.

## Building from source

FreeMinecraftModels is a Maven project (Java 21). MagmaCore and a few other libraries are shaded in at the `package` phase.

```bash
mvn -DskipTests package
```

The built plugin jar is written to `target/FreeMinecraftModels.jar`. Set `MC_DIST_DIR` to mirror it into a shared output directory. Publish a changed MagmaCore dependency to Maven Local before rebuilding.

## Developer API

Models, interactions, disguises and item integration: [FreeMinecraftModels developer reference](https://wiki.nightbreak.io/FreeMinecraftModels/api_and_developer_guide). See the [Java API index](https://wiki.nightbreak.io/developers) for dependency setup and lifecycle guidance.

Maven: `com.magmaguy:FreeMinecraftModels:2.12.0` from [MagmaGuy's repository](https://repo.magmaguy.com/releases). Use `provided` or `compileOnly` scope for the installed plugin.

## Links

- [FreeMinecraftModels on Nightbreak](https://nightbreak.io/plugin/freeminecraftmodels/)
- [Resource Pack Manager (Spigot)](https://www.spigotmc.org/resources/resource-pack-manager.118574/)
- [Patreon](https://www.patreon.com/magmaguy): support development
- [Discord](https://discord.gg/nightbreak)

## License

Source files carry their applicable license notices. Imported models, textures, fonts, and content packs retain their authors' licenses; generating a resource pack does not change those rights.
