package com.github.gemssorting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.bukkit.Material;

/**
 * Item catalog for the web interface: English and Italian names and inventory-style icons.
 *
 * Both come from the official Minecraft client of the server's version, downloaded once from
 * Mojang and cached in plugins/GemsSorting/cache/&lt;version&gt;/ (the client jar itself is deleted).
 * The icons are a single atlas image (ATLAS_COLUMNS per row, ICON_SIZE pixels each), so the
 * browser loads one file instead of 1600. Until the cache is ready the catalog has English
 * names only and no icons.
 */
final class Assets {

    /** One item of the catalog, as sent to the web interface; icon is its atlas index or -1. */
    record Item(String id, String en, String it, int icon) {}

    static final int ATLAS_COLUMNS = 48;
    static final int ICON_SIZE = IconRenderer.SIZE;
    /** Bump when the cache layout changes, so old caches are rebuilt. */
    private static final String CACHE_FORMAT = "2";

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";

    private final Path cacheRoot;
    private final String version;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile List<Item> items;
    private volatile boolean ready;

    Assets(Path cacheRoot, String version, Logger logger) {
        this.cacheRoot = cacheRoot;
        this.version = version;
        this.logger = logger;
        this.items = catalog(Map.of());
    }

    List<Item> items() {
        return items;
    }

    boolean ready() {
        return ready;
    }

    /** Changes whenever the atlas does (used to bust browser caches). */
    String atlasVersion() {
        return version + "-" + CACHE_FORMAT;
    }

    /** The icon atlas, or null if it is not ready yet. */
    Path atlas() {
        Path path = dir().resolve("atlas.png");
        return ready && Files.isRegularFile(path) ? path : null;
    }

    /** Loads the cache, building it first if needed. Blocking: call it off the main thread. */
    void prepare() {
        try {
            Path dir = dir();
            if (!Files.isRegularFile(dir.resolve("names.json"))) {
                build(dir);
            }
            Map<String, Entry> entries = new HashMap<>();
            try (Reader reader = Files.newBufferedReader(dir.resolve("names.json"), StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(reader, JsonObject.class);
                for (String key : json.keySet()) {
                    entries.put(key, gson.fromJson(json.get(key), Entry.class));
                }
            }
            items = catalog(entries);
            ready = true;
            logger.info("Web catalog ready: " + items.size() + " items with English and Italian names");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not prepare the item names and icons for " + version
                    + "; the web interface will show English names only", e);
        }
    }

    /** Cached data of one item: English and Italian name and atlas index (-1 = no icon). */
    private record Entry(String en, String it, int icon) {}

    private Path dir() {
        return cacheRoot.resolve(version + "-" + CACHE_FORMAT);
    }

    private void build(Path dir) throws IOException, InterruptedException {
        logger.info("Downloading the Minecraft " + version + " client to build the item names and icons...");
        JsonObject manifest = getJson(MANIFEST);
        String versionUrl = null;
        for (var element : manifest.getAsJsonArray("versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("id").getAsString().equals(version)) {
                versionUrl = entry.get("url").getAsString();
            }
        }
        if (versionUrl == null) {
            throw new IOException("Minecraft version " + version + " is not in Mojang's version manifest");
        }
        JsonObject versionJson = getJson(versionUrl);
        JsonObject client = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");

        // Italian is an asset, not part of the client jar.
        JsonObject index = getJson(versionJson.getAsJsonObject("assetIndex").get("url").getAsString());
        JsonObject itAsset = index.getAsJsonObject("objects").getAsJsonObject("minecraft/lang/it_it.json");
        String itHash = itAsset.get("hash").getAsString();
        byte[] itBytes = get(RESOURCES + itHash.substring(0, 2) + "/" + itHash, itHash);
        JsonObject italian = gson.fromJson(new String(itBytes, StandardCharsets.UTF_8), JsonObject.class);

        Path tmp = Files.createTempDirectory(cacheRoot.getParent(), "build-");
        Path jar = tmp.resolve("client.jar");
        try {
            Files.write(jar, get(client.get("url").getAsString(), client.get("sha1").getAsString()));
            JsonObject english;
            Map<String, BufferedImage> icons = new LinkedHashMap<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                try (InputStream in = zip.getInputStream(zip.getEntry("assets/minecraft/lang/en_us.json"))) {
                    english = gson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                }
                IconRenderer renderer = new IconRenderer(zip);
                for (Material type : materials()) {
                    String id = type.getKey().getKey();
                    try {
                        BufferedImage icon = renderer.render(id);
                        if (icon != null) {
                            icons.put(id, icon);
                        }
                    } catch (RuntimeException | IOException e) {
                        logger.fine("No icon for " + id + ": " + e);
                    }
                }
                logger.info("Rendered " + icons.size() + " item icons");
            }

            int rows = Math.max(1, (icons.size() + ATLAS_COLUMNS - 1) / ATLAS_COLUMNS);
            BufferedImage atlas = new BufferedImage(ATLAS_COLUMNS * ICON_SIZE, rows * ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = atlas.createGraphics();
            Map<String, Integer> atlasIndex = new HashMap<>();
            for (var icon : icons.entrySet()) {
                int i = atlasIndex.size();
                g.drawImage(icon.getValue(), (i % ATLAS_COLUMNS) * ICON_SIZE, (i / ATLAS_COLUMNS) * ICON_SIZE, null);
                atlasIndex.put(icon.getKey(), i);
            }
            g.dispose();
            ImageIO.write(atlas, "png", tmp.resolve("atlas.png").toFile());

            JsonObject names = new JsonObject();
            for (Material type : materials()) {
                String key = type.translationKey();
                String id = type.getKey().getKey();
                String en = english.has(key) ? english.get(key).getAsString() : ItemNames.englishName(type);
                String it = italian.has(key) ? italian.get(key).getAsString() : en;
                names.add(id, gson.toJsonTree(new Entry(en, it, atlasIndex.getOrDefault(id, -1))));
            }
            try (Writer writer = Files.newBufferedWriter(tmp.resolve("names.json"), StandardCharsets.UTF_8)) {
                gson.toJson(names, writer);
            }
            Files.delete(jar);

            // Replace the cache of any older version with this one.
            if (Files.exists(cacheRoot)) {
                deleteTree(cacheRoot);
            }
            Files.createDirectories(cacheRoot);
            Files.move(tmp, dir);
        } finally {
            if (Files.exists(tmp)) {
                deleteTree(tmp);
            }
        }
    }

    private List<Item> catalog(Map<String, Entry> entries) {
        List<Item> list = new ArrayList<>();
        for (Material type : materials()) {
            String id = type.getKey().getKey();
            Entry entry = entries.get(id);
            list.add(entry != null
                    ? new Item(id, entry.en(), entry.it(), entry.icon())
                    : new Item(id, ItemNames.englishName(type), ItemNames.englishName(type), -1));
        }
        list.sort(Comparator.comparing(Item::en, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(list);
    }

    static List<Material> materials() {
        List<Material> list = new ArrayList<>();
        for (Material type : Material.values()) {
            if (!type.isLegacy() && type.isItem() && !type.isAir()) {
                list.add(type);
            }
        }
        return list;
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        return gson.fromJson(new String(get(url, null), StandardCharsets.UTF_8), JsonObject.class);
    }

    private byte[] get(String url, String sha1) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        byte[] body = response.body();
        if (sha1 != null && !sha1.equalsIgnoreCase(sha1(body))) {
            throw new IOException("Checksum mismatch for " + url);
        }
        return body;
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
