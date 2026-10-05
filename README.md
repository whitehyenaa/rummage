# Rummage

A client-side Fabric mod for Minecraft that highlights storage blocks containing the items you want to find.

### Usage

- Clicking the button in the players inventory labeled **Find** opens a separate menu with a search box and a list of all items inside of Minecraft.
- When a player searches for an item and presses the **Find** button, Rummage will highlight storage blocks containing the searched item.
- With cheats on, `/find <item>` also lists the coordinates and item count of all storage blocks containing said item in chat.
- Integration with Mod Menu lets you edit the outline color and thickness, along with a toggle for clearing the outline of a storage block when opened.
- Compatible storage blocks include:
  - Chests
  - Trapped Chests
  - Ender Chests
  - Barrels
  - Shulker Boxes

### Extra

> [!NOTE]
> **Rummage** only works on chests you have previously opened. A server-side mod to find items across chests all players have opened is currently under development.

> [!CAUTION]
> While **Rummage** is client-side, some servers may consider it cheating. I am not responsible for any negative outcomes when using this mod.

Requires [Fabric API](https://github.com/FabricMC/fabric-api).  
[Mod Menu](https://github.com/TerraformersMC/ModMenu) is recommended for customizability.