# Changelog

## 1.0.0 - 2026-09-25
- First release; replaces SmartItemSort on the server.
- Input chests: an item frame holding an Eye of Ender. Items are sorted instantly on insert (by hand, drag, shift-click, hoppers, droppers).
- Receiver chests: an item frame holding a name tag renamed `#code`. Matching is a case-insensitive substring of the English item name or the item id. Anvil names are ignored.
- The longest matching tag wins, then the nearest chest. Full chests spill over to the next match, then to Carrot on a Stick overflow chests, then stay in the input chest.
- `radius` config option (default 128).
