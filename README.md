# WsSorting

Item-frame driven chest sorting for our Paper 26.3 server. The player guide is at [docs/Sorting-System-Guide.pdf](docs/Sorting-System-Guide.pdf), with its source in `docs/guide.html`.

| Frame on a chest holds | Chest role |
|---|---|
| Eye of Ender | **Input**: anything put in (by hand, hopper, dropper) is sorted out instantly |
| Name tag renamed `#code` | **Receiver**: gets every item whose name contains `code` |
| Carrot on a Stick | **Overflow**: gets items with no receiver, or whose receivers are full |

- The frame can be on any face of the chest (either half of a double chest). Chests and trapped chests both work, and glow item frames work too.
- Matching is case-insensitive and ignores anvil names. It checks the English item name and the item id, so `#sapling`, `#Oak Sapling` and `#oak_sapling` all work.
- If an item matches several tags, the longest tag wins (`#Sapling` beats `#Oak`), then the nearest chest. When a chest is full, the item goes to the next matching one.
- Order: matching receivers, then overflow chests (nearest first). Anything left stays in the input chest.
- Range: `radius` in `config.yml` (default 128) is measured from the input chest. Receiver chests must be in loaded chunks.
- Frame changes take effect within about 3 seconds (the scan is cached).

## Code layout

| File | What it does |
|---|---|
| `WsSortingPlugin.java` | Entry point; reads `config.yml` |
| `SortingListener.java` | Inventory events (click, drag, close, hopper move); schedules a sort of the touched chest |
| `SortingService.java` | Finds input, receiver and overflow chests via item frames and moves the items |
| `ItemNames.java` | Parses `#code` name tags and matches item names |

## Where the repository lives

The main copy is on the game server at **`/repos/ws-sorting`**. Reach it through the Pterodactyl panel file manager or SFTP. It's a normal git repository (the `.git` folder is included), so the full history and version tags come along.

The panel only offers file access (SFTP), not git access, so you can't `git clone` or `git push` to it directly. Workflow:

1. Download `/repos/ws-sorting` over SFTP (WinSCP, FileZilla, …), including the hidden `.git` folder.
2. Work and commit locally. Check `git log` first so you're building on the latest version.
3. Upload the whole folder back. **Tell the group before you do**, so two people don't overwrite each other's work.

## Build

Requirements: JDK 21 or newer and Maven 3.9+.

```
mvn -DskipTests clean package
```

Output: `target/ws-sorting-<version>.jar`.

## Releasing a new version

1. Bump `<version>` in `pom.xml` and add an entry to `CHANGELOG.md`.
2. Build, then copy the jar to `release/ws-sorting-<version>.jar`. Released jars are kept in the repo so anyone can deploy without building.
3. Commit and tag it: `git commit -am "Release x.y.z"` and then `git tag vx.y.z`.
4. Deploy: rename the old jar in `/plugins` to `.bak` (never overwrite a loaded jar), upload the new one, and restart the server when no one is playing.
