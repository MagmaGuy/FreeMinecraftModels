# Furniture Shop — Design & Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add an optional, Vault-backed "buy furniture" shop to FMM. A craftable item (any model with a registered recipe) is purchasable in the shop unless explicitly excluded. Defaults: shop system **off**, per-item shop entry **on**, default price **100**.

**Constraints from the user:**
- Optional feature; off by default at the global level.
- Default per-item price is 100, configurable per recipe.
- Per-item flag to exclude from shop, configurable per recipe.
- Unified menu modeled on EliteMobs' `CustomShopMenu` (purchase only, no selling).
- Clone EliteMobs patterns for the menu, fund-checks, and Vault integration; use MagmaCore primitives wherever possible.
- Vault is **mandatory** when the shop is enabled — no internal-currency fallback. If Vault or an economy provider is missing, the shop disables itself silently with a console warning.
- New `/fmm shop` subcommand and `freeminecraftmodels.shop` permission. Permission default `true` (every player gets it when the shop is on).
- Configurable user-facing messages.

**Architecture:** A single new package `com.magmaguy.freeminecraftmodels.shop` houses everything except the new `ShopConfig` (which lives under `config/` next to `DefaultConfig`). Existing `PropRecipeConfig` gains two fields. Init wires shop components in only when both `ShopConfig.isEnabled()` and `VaultEconomyHook.isEnabled()` are true.

**Tech stack:** Spigot Inventory API, MagmaCore `ConfigurationFile` / `ConfigurationEngine` / `AdvancedCommand` / `ItemStackGenerator` / `ChatColorConverter`, Vault API (`provided` scope, soft-depend).

---

## Task 1 — Add Vault dependency, soft-depend, and permission node

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/plugin.yml`

**Steps:**

1. In `pom.xml`, add the JitPack repository (Vault is hosted there):
   ```xml
   <repository>
       <id>jitpack.io</id>
       <url>https://jitpack.io</url>
   </repository>
   ```
2. Add the Vault API dependency (provided scope — Vault is shipped by the server admin):
   ```xml
   <dependency>
       <groupId>com.github.MilkBowl</groupId>
       <artifactId>VaultAPI</artifactId>
       <version>1.7</version>
       <scope>provided</scope>
   </dependency>
   ```
3. In `plugin.yml`, append `Vault` to `softdepend`:
   ```yaml
   softdepend:
     - WorldGuard
     - WorldEdit
     - GriefPrevention
     - Vault
   ```
4. Add the new permission under `permissions:`:
   ```yaml
   freeminecraftmodels.shop:
     description: Allows access to the furniture shop.
     default: true
   ```

The permission is granted by default — when the shop is disabled, `ShopCommand` is never registered, so the grant is inert.

---

## Task 2 — Add `shopEnabled` and `shopPrice` to PropRecipeConfig

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/config/recipes/PropRecipeConfig.java`

**Steps:**

1. Add two `@Getter` fields after the existing ones:
   ```java
   @Getter private boolean shopEnabled = true;
   @Getter private double shopPrice = 100.0;
   ```
2. In `processConfigFields()`, after the existing `parsedIngredients` block, read both fields. Use `ShopConfig.getDefaultPrice()` as the default for `shopPrice` so the global setting flows through to new recipes:
   ```java
   this.shopEnabled = processBoolean("shopEnabled", shopEnabled, true, true);
   double defaultPrice = ShopConfig.getDefaultPrice();
   this.shopPrice = processDouble("shopPrice", defaultPrice, defaultPrice, true);
   ```
3. The `forceWriteDefault=true` flag causes existing recipe files without these keys to get them written on next load — same convention every other FMM config file uses.

`PropRecipeConfig.create()` (used by `/fmm craftify`) does not need changes: when it calls `processConfigFields()` with no `shopEnabled`/`shopPrice` keys present in the new YAML, the defaults are written automatically.

---

## Task 3 — Create ShopConfig singleton

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/config/ShopConfig.java`

**Steps:**

Mirror [DefaultConfig.java](../../src/main/java/com/magmaguy/freeminecraftmodels/config/DefaultConfig.java): extend `ConfigurationFile`, override `initializeValues()`, populate static fields via `ConfigurationEngine.setBoolean/setInt/setDouble/setString`. Generates `plugins/FreeMinecraftModels/shop_config.yml` with these fields:

```java
public class ShopConfig extends ConfigurationFile {
    @Getter public static boolean enabled;
    @Getter public static double defaultPrice;
    @Getter public static String menuTitle;
    @Getter public static String priceLoreFormat;
    @Getter public static String clickToBuyLoreFormat;
    @Getter public static String purchaseSuccessMessage;
    @Getter public static String insufficientFundsMessage;
    @Getter public static String shopDisabledMessage;
    @Getter public static String itemNotForSaleMessage;
    @Getter public static String inventoryFullMessage;

    public ShopConfig() { super("shop_config.yml"); }

    @Override
    public void initializeValues() {
        enabled = ConfigurationEngine.setBoolean(
            List.of("Master toggle for the furniture shop. Defaults to false.",
                    "Requires Vault + economy provider; shop disables itself silently if either is missing."),
            fileConfiguration, "enabled", false);
        defaultPrice = ConfigurationEngine.setDouble(
            List.of("Default price applied to recipes that do not define shopPrice."),
            fileConfiguration, "defaultPrice", 100.0);
        menuTitle = ConfigurationEngine.setString(
            fileConfiguration, "menuTitle", "&8FMM - Furniture Shop");
        // ... messages, lore formats ...
    }
}
```

Vault is the only supported economy backend — there is no `useVault` toggle. Players run an unmodded server without Vault simply leave `enabled: false`.

Pagination uses `ModelMenuHelper.PREV_SLOT` / `NEXT_SLOT` / `CONTENT_SLOTS` directly (already 54-slot, no need to re-customize layout — keeps visual parity with `CraftableItemsMenu`).

Messages support placeholders via simple string replacement: `{price}`, `{balance}`, `{item}`.

---

## Task 4 — Create VaultEconomyHook

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/shop/VaultEconomyHook.java`

**Steps:**

Lift the pattern from EliteMobs' `VaultCompatibility`:

```java
public final class VaultEconomyHook {
    private static Economy economy = null;
    private static boolean enabled = false;

    private VaultEconomyHook() {}

    public static void initialize() {
        enabled = false;
        economy = null;
        if (!ShopConfig.isEnabled()) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            Logger.warn("Shop is enabled but Vault is not installed; shop disabled.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
            Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            Logger.warn("Shop is enabled but no economy provider is registered with Vault; shop disabled.");
            return;
        }
        economy = rsp.getProvider();
        enabled = true;
        Logger.info("Shop initialized with economy provider: " + economy.getName());
    }

    public static boolean isEnabled() { return enabled; }
    public static double getBalance(Player p) { return economy.getBalance(p); }
    public static boolean withdraw(Player p, double amount) {
        return economy.withdrawPlayer(p, amount).transactionSuccess();
    }
}
```

No internal-currency fallback — FMM has no player-data system. Failure to find Vault simply leaves `enabled = false`.

---

## Task 5 — Create PurchaseHandler

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/shop/PurchaseHandler.java`

**Steps:**

Pure logic class with a result enum. The menu maps results to configurable messages.

```java
public final class PurchaseHandler {
    public enum Result { SUCCESS, INSUFFICIENT_FUNDS, NOT_FOR_SALE, SHOP_DISABLED }

    public static class Outcome {
        public final Result result;
        public final double balance;       // for INSUFFICIENT_FUNDS message
        public final double price;
        public Outcome(Result r, double balance, double price) { ... }
    }

    public static Outcome attempt(Player player, PropRecipeConfig recipe) {
        if (!ShopConfig.isEnabled() || !VaultEconomyHook.isEnabled())
            return new Outcome(Result.SHOP_DISABLED, 0, 0);
        if (!recipe.isShopEnabled())
            return new Outcome(Result.NOT_FOR_SALE, 0, 0);

        double price = recipe.getShopPrice();
        double balance = VaultEconomyHook.getBalance(player);
        if (balance < price)
            return new Outcome(Result.INSUFFICIENT_FUNDS, balance, price);

        if (!VaultEconomyHook.withdraw(player, price))
            return new Outcome(Result.INSUFFICIENT_FUNDS, balance, price);

        ItemStack output = ModelItemFactory.createModelItem(recipe.getModelId(), Material.PAPER);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(output);
        // Drop overflow at feet
        overflow.values().forEach(stack ->
            player.getWorld().dropItemNaturally(player.getLocation(), stack));

        return new Outcome(Result.SUCCESS, balance - price, price);
    }
}
```

Uses `ModelItemFactory.createModelItem` — same call `PropRecipeConfig.registerRecipe()` uses for the recipe output, ensuring shop-bought items are byte-identical to crafted ones.

---

## Task 6 — Create ShopMenu with inner Events listener

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/shop/ShopMenu.java`

**Steps:**

Clone the structure of `CraftableItemsMenu`:
- `static HashMap<Inventory, ShopMenu> openMenus`
- Constructor takes `Player`, builds list of purchasable recipes, calls `open()`
- `open()` creates a 54-slot inventory titled `ShopConfig.getMenuTitle()`, populates, registers in map
- `populate()` reuses `ModelMenuHelper.CONTENT_SLOTS` / `PREV_SLOT` / `NEXT_SLOT` / `ITEMS_PER_PAGE`
- For each recipe item, build a display `ItemStack` via a new helper `buildShopItem(PropRecipeConfig)` that:
  - Resolves the `FileModelConverter` for the model ID
  - Calls `ModelMenuHelper.buildModelItem(converter, false)` to get the base display item (same lore/visuals as the existing craftable-items menu)
  - Appends the price line and click-to-buy line from `ShopConfig`
  - Tags the item with a `model_id` PDC key (same key `ModelMenuHelper` already uses in admin mode) so the click handler resolves clicks safely
- Inner static `Events` class with `onInventoryClick` and `onInventoryClose`. Click handler:
  1. Bail if `openMenus.get(inv) == null`
  2. `event.setCancelled(true)` (always — pure-display menu)
  3. Bail if the click is in the player's inventory, not the shop inventory
  4. If slot is `PREV_SLOT` / `NEXT_SLOT`, paginate
  5. Otherwise read the `model_id` PDC tag from `event.getCurrentItem()`. If absent, ignore.
  6. Look up recipe via `PropRecipeManager.getLoadedRecipes().get(modelId)`
  7. Call `PurchaseHandler.attempt(player, recipe)` and dispatch the result message:
     ```java
     switch (outcome.result) {
       case SUCCESS -> sendMsg(player, ShopConfig.getPurchaseSuccessMessage(), outcome);
       case INSUFFICIENT_FUNDS -> sendMsg(player, ShopConfig.getInsufficientFundsMessage(), outcome);
       case NOT_FOR_SALE -> sendMsg(player, ShopConfig.getItemNotForSaleMessage(), outcome);
       case SHOP_DISABLED -> { sendMsg(player, ShopConfig.getShopDisabledMessage(), outcome); player.closeInventory(); }
     }
     ```
- `static void registerEvents(Plugin plugin)` mirrors `CraftableItemsMenu.registerEvents`.

Recipe list is computed once at menu open (lazy — no work done unless a player runs `/fmm shop`).

---

## Task 7 — Create ShopCommand

**Files:**
- Create: `src/main/java/com/magmaguy/freeminecraftmodels/commands/ShopCommand.java`

**Steps:**

Clone `CraftifyCommand`'s shape (subcommand via `super(List.of("shop"))`):

```java
public class ShopCommand extends AdvancedCommand {
    public ShopCommand() {
        super(List.of("shop"));
        setPermission("freeminecraftmodels.shop");
        setDescription("Opens the furniture shop.");
        setUsage("/fmm shop");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        if (!ShopConfig.isEnabled() || !VaultEconomyHook.isEnabled()) {
            Logger.sendMessage(player, ShopConfig.getShopDisabledMessage());
            return;
        }
        new ShopMenu(player);
    }
}
```

The `ShopConfig.isEnabled()` check is defensive — the command won't even be registered when disabled (Task 8) — but it costs nothing and protects against a re-registration bug.

---

## Task 8 — Wire everything into FreeMinecraftModels main class

**Files:**
- Modify: `src/main/java/com/magmaguy/freeminecraftmodels/FreeMinecraftModels.java`

**Steps:**

1. **Async init** — add right after `new DefaultConfig()`:
   ```java
   initializationContext.step("Shop Config");
   new ShopConfig();
   ```
   (`PropRecipeManager.initialize()` runs later in sync init, so `ShopConfig` is ready by then for the `defaultPrice` lookup.)

2. **Sync init** — at the end, after `PropRecipeManager.initialize()`:
   ```java
   initializationContext.step("Shop");
   VaultEconomyHook.initialize();
   if (ShopConfig.isEnabled() && VaultEconomyHook.isEnabled()) {
       manager.registerCommand(new ShopCommand());
       ShopMenu.registerEvents(this);
   }
   ```

   Note: `manager` is a local variable inside `syncInitialization`; `ShopCommand` needs to be registered against the same `CommandManager` instance, so the registration must happen before the method returns. Move the new block to immediately after the existing `manager.registerCommand(new GiveItemCommand())` block but before `NightbreakPluginBootstrap.registerStandardCommands` — order matters only in that the manager must be in scope.

3. No changes to `onDisable()` — `ShopMenu` and command listeners are unregistered automatically by `HandlerList.unregisterAll`. `VaultEconomyHook`'s static state is harmless across reload (re-initialized on next enable).

4. The existing `/fmm reload` flow (`reloadImportedContent`) does **not** re-run shop init. Shop toggle changes thus require a server restart. (Acceptable for v1 — flagging shop changes as restart-only matches how `DefaultConfig` toggles already behave.)

---

## Task 9 — Build and verify

**Files:** none.

**Steps:**

Run `mvn package` in the FMM directory. Verify:
1. Compilation succeeds (no missing imports, no Vault-API resolution failure).
2. `target/FreeMinecraftModels.jar` is produced.
3. `dependency-reduced-pom.xml` is regenerated cleanly.
4. The jar size and resource entries look reasonable (Vault API is `provided`, so it should not be shaded in).

If the build fails, investigate and fix; do not skip verification per `feedback_full_builds`.

---

## Out of scope for v1

- Sell back / refunds.
- Multi-quantity purchase (shift-click bulk buy).
- Per-player purchase limits or cooldowns.
- Shop categories or per-pack shops.
- `/fmm reload` re-applying shop toggle (requires server restart).
- Inventory-full handling beyond "drop at feet" (matches vanilla behavior).
