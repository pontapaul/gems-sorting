# Changelog

## Unreleased
- Renamed from WsSorting to GemsSorting (plugin name, package `com.github.gemssorting`, artifact `gems-sorting`). The config folder moves from `plugins/WsSorting/` to `plugins/GemsSorting/`.
- The repository moved to GitHub; the README now contains the full player guide.
- Build docs: the Paper 26.3 API needs JDK 25 to compile.

## 1.0.0 - 2026-09-25
- First release; replaces SmartItemSort on the server.
- Input chests: an item frame holding an Eye of Ender. Items are sorted instantly on insert (by hand, drag, shift-click, hoppers, droppers).
- Receiver chests: an item frame holding a name tag renamed `#code`. Matching is a case-insensitive substring of the English item name or the item id. Anvil names are ignored.
- The longest matching tag wins, then the nearest chest. Full chests spill over to the next match, then to Carrot on a Stick overflow chests, then stay in the input chest.
- `radius` config option (default 128).
