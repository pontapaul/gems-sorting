# Changelog

## 1.2.0 - 2026-10-03
- Copper chests (every oxidation stage, waxed or not) and barrels work like chests in every role: input, `#name` and `.group` receivers, overflow. Copper double chests are supported.
- Web editor: search field to filter the groups by name (the `.tag` form works too).

## 1.1.4 - 2026-09-30
- `web.public-url` is empty by default (it used to point to our own domain); when it is not set, links use `http://<server-ip or localhost>:<port>` and a warning is logged.

## 1.1.3 - 2026-09-30
- Web editor: the whole interface is translated (English by default, Italian), following the same language selector as the item names. Server error messages follow it too; `/gems web` answers in the player's client language.
- Web editor: link to the GitHub repository.

## 1.1.2 - 2026-09-30
- Web editor: item names are shown in one language only, English by default; a selector switches to Italian and the choice is kept in a cookie. Search still matches both languages.

## 1.1.1 - 2026-09-30
- Fix: when upgrading from a config.yml without the `web` section, the first start ignored the defaults (login links pointed to `http://0.0.0.0:8101`).

## 1.1.0 - 2026-09-30
- Renamed from WsSorting to GemsSorting (plugin name, package `com.github.gemssorting`, artifact `gems-sorting`). The config folder moves from `plugins/WsSorting/` to `plugins/GemsSorting/`.
- Item groups: a name tag renamed `.group` makes a chest collect the items of that group. `#name` matches keep priority: name receivers first, then group chests (nearest first), then overflow.
- Web editor for the groups (built-in web server, `web.*` options): operators get a one-time login link with `/gems web`; search in Italian and English, drag and drop, click to add, bulk add, undo, autosave with conflict detection.
- Italian names and inventory-style icons are built once from the official Minecraft client and cached per version.
- The repository moved to GitHub; the README now contains the full player guide. Build docs: the Paper 26.3 API needs JDK 25 to compile.

## 1.0.0 - 2026-09-25
- First release; replaces SmartItemSort on the server.
- Input chests: an item frame holding an Eye of Ender. Items are sorted instantly on insert (by hand, drag, shift-click, hoppers, droppers).
- Receiver chests: an item frame holding a name tag renamed `#code`. Matching is a case-insensitive substring of the English item name or the item id. Anvil names are ignored.
- The longest matching tag wins, then the nearest chest. Full chests spill over to the next match, then to Carrot on a Stick overflow chests, then stay in the input chest.
- `radius` config option (default 128).
