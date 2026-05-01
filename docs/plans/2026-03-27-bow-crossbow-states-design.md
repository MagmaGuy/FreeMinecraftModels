# Bow & Crossbow Draw States Design

## Purpose

Support bow and crossbow pull animation states in FMM custom items, so content creators can provide separate models for each draw state and FMM auto-generates the correct conditional resource pack JSON.

## Naming Convention

**Bow (4 files):**
- `{name}_idle` — in hand, not drawing
- `{name}_draw_start` — just started pulling (pulling_0)
- `{name}_draw_half` — halfway drawn (pulling_1)
- `{name}_draw_full` — fully drawn (pulling_2)

**Crossbow (5 files):**
- `{name}_idle` — in hand, not loaded
- `{name}_draw_start` — just started pulling
- `{name}_draw_half` — halfway pulled
- `{name}_draw_full` — fully pulled
- `{name}_charged` — loaded, ready to fire

Each state needs a `.bbmodel` and `.json` sibling file.

## Detection

Automatic — no config needed. During model processing, FMM looks for `_idle` suffix models with matching `_draw_start`, `_draw_half`, `_draw_full` siblings. If `_charged` also exists → crossbow. Otherwise → bow.

## Resource Pack Output

- All state models get their display JSON in `models/display/` (existing sibling .json processing).
- Only the `_idle` model gets an item definition JSON in `items/display/`.
- Draw/charged models do NOT get item definitions — they're only referenced by the idle model's conditional JSON.

### Bow Item Definition

```json
{
  "model": {
    "type": "minecraft:condition",
    "property": "minecraft:using_item",
    "on_false": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_idle"},
    "on_true": {
      "type": "minecraft:range_dispatch",
      "property": "minecraft:use_duration",
      "scale": 0.05,
      "fallback": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_draw_start"},
      "entries": [
        {"threshold": 0.65, "model": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_draw_half"}},
        {"threshold": 0.9, "model": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_draw_full"}}
      ]
    }
  }
}
```

### Crossbow Item Definition

```json
{
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:charge_type",
    "cases": [
      {"when": "arrow", "model": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_charged"}},
      {"when": "rocket", "model": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_charged"}}
    ],
    "fallback": {
      "type": "minecraft:condition",
      "property": "minecraft:using_item",
      "on_false": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_idle"},
      "on_true": {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:crossbow/pull",
        "fallback": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_draw_start"},
        "entries": [
          {"threshold": 0.58, "model": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_draw_half"}},
          {"threshold": 1.0, "model": {"type": "minecraft:model", "model": "freeminecraftmodels:display/{name}_draw_full"}}
        ]
      }
    }
  }
}
```

## YML Config Generation

- Only ONE YML is generated per bow/crossbow set, using the base name without suffix (e.g., `cool_bow.yml` not `cool_bow_idle.yml`).
- State suffixes (`_idle`, `_draw_start`, `_draw_half`, `_draw_full`, `_charged`) are stripped before YML generation.
- State models are NOT registered as separate custom items during `ItemScriptManager.scanForCustomItems()`.

## DisplayModelRegistry

Only the `_idle` model ID is registered. Draw/charged models are not registered — they exist only as display models referenced by the idle model's item definition.
