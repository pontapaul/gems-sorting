package com.github.gemssorting;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Draws inventory-style item icons from the models and textures of the Minecraft client jar.
 *
 * Flat items (item/generated) are their texture layers; block models are drawn as isometric
 * boxes (top, north and west faces, like the inventory view). Element rotations and face UV
 * rotations are ignored: good enough for an icon.
 */
final class IconRenderer {

    static final int SIZE = 64;

    private static final double A = Math.sqrt(0.5);
    private static final double UX = 0.5 * A;
    private static final double UY = Math.cos(Math.toRadians(30));
    private static final double CENTER_UP = 8 * (2 * UX + UY);
    private static final double SCALE = (SIZE - 4) / (2 * CENTER_UP);
    private static final int GRASS = 0xFF91BD59;
    private static final int FOLIAGE = 0xFF48B518;

    /** A model to draw; special is the "special" renderer of chests, heads, ... (or null). */
    private record Part(String model, int[] tints, JsonObject special) {}

    private record Model(Map<String, String> textures, JsonArray elements, boolean generated) {}

    private final ZipFile zip;
    private final Gson gson = new Gson();
    private final Map<String, JsonObject> jsonCache = new HashMap<>();
    private final Map<String, BufferedImage> textureCache = new HashMap<>();

    IconRenderer(ZipFile zip) {
        this.zip = zip;
    }

    /** Renders the icon of an item, or returns null if the item has no usable model. */
    BufferedImage render(String id) throws IOException {
        JsonObject definition = json("assets/minecraft/items/" + id + ".json");
        if (definition == null) {
            return null;
        }
        List<Part> parts = new ArrayList<>();
        collect(definition.get("model"), parts);

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        boolean drawn = false;
        for (Part part : parts) {
            drawn |= draw(g, part);
        }
        g.dispose();
        return drawn ? image : null;
    }

    /** Picks the models an item definition shows by default (no special components). */
    private void collect(JsonElement element, List<Part> parts) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject node = element.getAsJsonObject();
        String type = node.has("type") ? strip(node.get("type").getAsString()) : "";
        switch (type) {
            case "model" -> parts.add(new Part(node.get("model").getAsString(), tints(node.getAsJsonArray("tints")), null));
            case "composite" -> {
                for (JsonElement child : node.getAsJsonArray("models")) {
                    collect(child, parts);
                }
            }
            case "condition" -> {
                int before = parts.size();
                collect(node.get("on_false"), parts);
                if (parts.size() == before) {
                    collect(node.get("on_true"), parts);
                }
            }
            case "select" -> {
                if (node.has("fallback")) {
                    collect(node.get("fallback"), parts);
                } else if (node.has("cases") && !node.getAsJsonArray("cases").isEmpty()) {
                    collect(node.getAsJsonArray("cases").get(0).getAsJsonObject().get("model"), parts);
                }
            }
            case "range_dispatch" -> {
                if (node.has("fallback")) {
                    collect(node.get("fallback"), parts);
                } else if (node.has("entries") && !node.getAsJsonArray("entries").isEmpty()) {
                    collect(node.getAsJsonArray("entries").get(0).getAsJsonObject().get("model"), parts);
                }
            }
            case "special" -> parts.add(new Part(node.get("base").getAsString(), new int[0], node.getAsJsonObject("model")));
            default -> {
                if (node.has("fallback")) {
                    collect(node.get("fallback"), parts);
                }
            }
        }
    }

    private static int[] tints(JsonArray array) {
        if (array == null) {
            return new int[0];
        }
        int[] colors = new int[array.size()];
        for (int i = 0; i < colors.length; i++) {
            JsonObject tint = array.get(i).getAsJsonObject();
            String type = tint.has("type") ? strip(tint.get("type").getAsString()) : "";
            if (tint.has("value")) {
                colors[i] = color(tint.get("value"));
            } else if (tint.has("default")) {
                colors[i] = color(tint.get("default"));
            } else if (type.equals("grass")) {
                colors[i] = GRASS;
            } else if (type.equals("foliage")) {
                colors[i] = FOLIAGE;
            } else {
                colors[i] = 0xFFFFFFFF;
            }
        }
        return colors;
    }

    private static int color(JsonElement value) {
        if (value.isJsonArray()) {
            JsonArray rgb = value.getAsJsonArray();
            int r = (int) Math.round(rgb.get(0).getAsDouble() * 255);
            int g = (int) Math.round(rgb.get(1).getAsDouble() * 255);
            int b = (int) Math.round(rgb.get(2).getAsDouble() * 255);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }
        return 0xFF000000 | value.getAsInt();
    }

    private boolean draw(Graphics2D g, Part part) throws IOException {
        if (part.special() != null && drawSpecial(g, part.special())) {
            return true;
        }
        Model model = model(part.model());
        if (model == null) {
            return false;
        }
        if (model.elements() != null && !model.generated()) {
            return drawElements(g, model, part.tints());
        }
        if (model.generated()) {
            boolean drawn = false;
            for (int layer = 0; model.textures().containsKey("layer" + layer); layer++) {
                BufferedImage texture = texture(model, "#layer" + layer);
                if (texture != null) {
                    int tint = layer < part.tints().length ? part.tints()[layer] : 0xFFFFFFFF;
                    g.drawImage(shade(texture, tint, 1.0), 0, 0, SIZE, SIZE, null);
                    drawn = true;
                }
            }
            return drawn;
        }
        // Other special renderers (shields, tridents, ...): show the particle texture.
        BufferedImage particle = texture(model, "#particle");
        if (particle == null) {
            return false;
        }
        g.drawImage(particle, 0, 0, SIZE, SIZE, null);
        return true;
    }

    private boolean drawElements(Graphics2D g, Model model, int[] tints) throws IOException {
        List<JsonObject> elements = new ArrayList<>();
        for (JsonElement element : model.elements()) {
            elements.add(element.getAsJsonObject());
        }
        // Painter's algorithm: draw the elements farthest from the viewer first.
        elements.sort(Comparator.comparingDouble(e -> {
            double[] f = vec(e.getAsJsonArray("from"));
            double[] t = vec(e.getAsJsonArray("to"));
            return -UY * A * (f[0] + t[0]) + 0.5 * (f[1] + t[1]) - UY * A * (f[2] + t[2]);
        }));
        boolean drawn = false;
        for (JsonObject element : elements) {
            double[] f = vec(element.getAsJsonArray("from"));
            double[] t = vec(element.getAsJsonArray("to"));
            JsonObject faces = element.getAsJsonObject("faces");
            if (faces == null) {
                continue;
            }
            drawn |= face(g, model, tints, faces.getAsJsonObject("up"), 1.0,
                    new double[] {f[0], t[1], f[2]}, new double[] {t[0], t[1], f[2]}, new double[] {f[0], t[1], t[2]},
                    new double[] {f[0], f[2], t[0], t[2]});
            drawn |= face(g, model, tints, faces.getAsJsonObject("north"), 0.8,
                    new double[] {t[0], t[1], f[2]}, new double[] {f[0], t[1], f[2]}, new double[] {t[0], f[1], f[2]},
                    new double[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]});
            drawn |= face(g, model, tints, faces.getAsJsonObject("west"), 0.6,
                    new double[] {f[0], t[1], f[2]}, new double[] {f[0], t[1], t[2]}, new double[] {f[0], f[1], f[2]},
                    new double[] {f[2], 16 - t[1], t[2], 16 - f[1]});
        }
        return drawn;
    }

    /**
     * Draws one face: origin is the 3D corner at the texture's (u1, v1), uEnd at (u2, v1), vEnd at (u1, v2).
     */
    private boolean face(Graphics2D g, Model model, int[] tints, JsonObject face, double light,
                         double[] origin, double[] uEnd, double[] vEnd, double[] defaultUv) throws IOException {
        if (face == null || !face.has("texture")) {
            return false;
        }
        BufferedImage texture = texture(model, face.get("texture").getAsString());
        if (texture == null) {
            return false;
        }
        double[] uv = face.has("uv") ? vec(face.getAsJsonArray("uv")) : defaultUv;
        int tintIndex = face.has("tintindex") ? face.get("tintindex").getAsInt() : -1;
        int tint = tintIndex >= 0 && tintIndex < tints.length ? tints[tintIndex] : 0xFFFFFFFF;
        return drawFace(g, shade(texture, tint, 1.0), light, origin, uEnd, vEnd, uv);
    }

    /** uv is in 1/16 of the texture size, like model files. */
    private static boolean drawFace(Graphics2D g, BufferedImage texture, double light,
                                    double[] origin, double[] uEnd, double[] vEnd, double[] uv) {
        double s = texture.getWidth() / 16.0;
        double du = (uv[2] - uv[0]) * s;
        double dv = (uv[3] - uv[1]) * s;
        if (du == 0 || dv == 0) {
            return false;
        }
        double[] o = project(origin);
        double[] u = project(uEnd);
        double[] v = project(vEnd);
        double m00 = (u[0] - o[0]) / du, m10 = (u[1] - o[1]) / du;
        double m01 = (v[0] - o[0]) / dv, m11 = (v[1] - o[1]) / dv;
        double tx = o[0] - m00 * uv[0] * s - m01 * uv[1] * s;
        double ty = o[1] - m10 * uv[0] * s - m11 * uv[1] * s;

        Path2D clip = new Path2D.Double();
        clip.moveTo(o[0], o[1]);
        clip.lineTo(u[0], u[1]);
        clip.lineTo(u[0] + v[0] - o[0], u[1] + v[1] - o[1]);
        clip.lineTo(v[0], v[1]);
        clip.closePath();

        Graphics2D fg = (Graphics2D) g.create();
        fg.clip(clip);
        fg.drawImage(shade(texture, 0xFFFFFFFF, light), new AffineTransform(m00, m10, m01, m11, tx, ty), null);
        fg.dispose();
        return true;
    }

    /** Entity-textured items, drawn as boxes with Minecraft's box UV layout. */
    private boolean drawSpecial(Graphics2D g, JsonObject special) throws IOException {
        String type = strip(special.get("type").getAsString());
        switch (type) {
            case "chest" -> {
                BufferedImage texture = entity("chest/" + strip(special.get("texture").getAsString()));
                if (texture == null) {
                    return false;
                }
                box(g, texture, 1, 0, 1, 15, 10, 15, 0, 19, 14, 10, 14);
                box(g, texture, 1, 9, 1, 15, 14, 15, 0, 0, 14, 5, 14);
                box(g, texture, 7, 7, 0, 9, 11, 1, 0, 0, 2, 4, 1);
                return true;
            }
            case "shulker_box" -> {
                BufferedImage texture = entity("shulker/" + strip(special.get("texture").getAsString()));
                if (texture == null) {
                    return false;
                }
                box(g, texture, 0, 0, 0, 16, 8, 16, 0, 28, 16, 8, 16);
                box(g, texture, 0, 4, 0, 16, 16, 16, 0, 0, 16, 12, 16);
                return true;
            }
            case "head", "player_head" -> {
                String kind = special.has("kind") ? special.get("kind").getAsString() : "player";
                String path = switch (kind) {
                    case "skeleton" -> "skeleton/skeleton";
                    case "wither_skeleton" -> "skeleton/wither_skeleton";
                    case "zombie" -> "zombie/zombie";
                    case "creeper" -> "creeper/creeper";
                    case "piglin" -> "piglin/piglin";
                    case "player" -> "player/wide/steve";
                    default -> null;
                };
                BufferedImage texture = path == null ? null : entity(path);
                if (texture == null) {
                    return false;
                }
                int width = kind.equals("piglin") ? 10 : 8;
                double half = width * 0.75;
                box(g, texture, 8 - half, 0, 2, 8 + half, 12, 14, 0, 0, width, 8, 8);
                return true;
            }
            case "banner" -> {
                BufferedImage texture = entity("banner/base");
                Integer color = special.has("color") ? DYES.get(special.get("color").getAsString()) : null;
                if (texture == null || color == null) {
                    return false;
                }
                double s = texture.getWidth() / 64.0;
                BufferedImage flag = texture.getSubimage((int) s, (int) s, (int) (20 * s), (int) (40 * s));
                g.drawImage(shade(flag, color, 1.0), 17, 2, 30, 60, null);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static final Map<String, Integer> DYES = Map.ofEntries(
            Map.entry("white", 0xFFF9FFFE), Map.entry("orange", 0xFFF9801D), Map.entry("magenta", 0xFFC74EBD),
            Map.entry("light_blue", 0xFF3AB3DA), Map.entry("yellow", 0xFFFED83D), Map.entry("lime", 0xFF80C71F),
            Map.entry("pink", 0xFFF38BAA), Map.entry("gray", 0xFF474F52), Map.entry("light_gray", 0xFF9D9D97),
            Map.entry("cyan", 0xFF169C9C), Map.entry("purple", 0xFF8932B8), Map.entry("blue", 0xFF3C44AA),
            Map.entry("brown", 0xFF835432), Map.entry("green", 0xFF5E7C16), Map.entry("red", 0xFFB02E26),
            Map.entry("black", 0xFF1D1D21));

    private BufferedImage entity(String path) throws IOException {
        return texture(new Model(Map.of(), null, false), "entity/" + path);
    }

    /**
     * Draws a box whose texture uses the entity box UV layout at (u, v) with size w x h x d
     * (in texture pixels of a 64-wide texture); the front of the box faces north.
     */
    private void box(Graphics2D g, BufferedImage texture, double x1, double y1, double z1,
                     double x2, double y2, double z2, int u, int v, int w, int h, int d) {
        double k = 16.0 / 64;
        drawFace(g, texture, 1.0,
                new double[] {x2, y2, z2}, new double[] {x1, y2, z2}, new double[] {x2, y2, z1},
                new double[] {(u + d) * k, v * k, (u + d + w) * k, (v + d) * k});
        drawFace(g, texture, 0.8,
                new double[] {x2, y2, z1}, new double[] {x1, y2, z1}, new double[] {x2, y1, z1},
                new double[] {(u + d) * k, (v + d) * k, (u + d + w) * k, (v + d + h) * k});
        drawFace(g, texture, 0.6,
                new double[] {x1, y2, z1}, new double[] {x1, y2, z2}, new double[] {x1, y1, z1},
                new double[] {(u + d + w) * k, (v + d) * k, (u + d + w + d) * k, (v + d + h) * k});
    }

    /** Block coordinates (0..16) to icon pixels. */
    private static double[] project(double[] p) {
        double x = (p[2] - p[0]) * A;
        double up = UX * p[0] + UY * p[1] + UX * p[2];
        return new double[] {SIZE / 2.0 + x * SCALE, SIZE / 2.0 - (up - CENTER_UP) * SCALE};
    }

    private static BufferedImage shade(BufferedImage texture, int tint, double light) {
        if (tint == 0xFFFFFFFF && light == 1.0) {
            return texture;
        }
        double r = ((tint >> 16) & 0xFF) / 255.0 * light;
        double gr = ((tint >> 8) & 0xFF) / 255.0 * light;
        double b = (tint & 0xFF) / 255.0 * light;
        BufferedImage out = new BufferedImage(texture.getWidth(), texture.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < texture.getHeight(); y++) {
            for (int x = 0; x < texture.getWidth(); x++) {
                int argb = texture.getRGB(x, y);
                int pr = (int) (((argb >> 16) & 0xFF) * r);
                int pg = (int) (((argb >> 8) & 0xFF) * gr);
                int pb = (int) ((argb & 0xFF) * b);
                out.setRGB(x, y, (argb & 0xFF000000) | pr << 16 | pg << 8 | pb);
            }
        }
        return out;
    }

    private Model model(String ref) {
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        boolean generated = false;
        String current = ref;
        for (int depth = 0; current != null && depth < 32; depth++) {
            String path = strip(current);
            if (path.equals("builtin/generated")) {
                generated = true;
                break;
            }
            JsonObject json = json("assets/minecraft/models/" + path + ".json");
            if (json == null) {
                break;
            }
            if (json.has("textures")) {
                for (var entry : json.getAsJsonObject("textures").entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonObject() && value.getAsJsonObject().has("sprite")) {
                        value = value.getAsJsonObject().get("sprite");
                    }
                    if (value.isJsonPrimitive()) {
                        textures.putIfAbsent(entry.getKey(), value.getAsString());
                    }
                }
            }
            if (elements == null && json.has("elements")) {
                elements = json.getAsJsonArray("elements");
            }
            current = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        if (textures.isEmpty() && elements == null && !generated) {
            return null;
        }
        return new Model(textures, elements, generated);
    }

    /** Resolves "#variable" references and loads the texture (first frame of animated ones). */
    private BufferedImage texture(Model model, String ref) throws IOException {
        String value = ref;
        for (int i = 0; value != null && value.startsWith("#") && i < 16; i++) {
            value = model.textures().get(value.substring(1));
        }
        if (value == null || value.startsWith("#")) {
            return null;
        }
        String path = "assets/minecraft/textures/" + strip(value) + ".png";
        if (textureCache.containsKey(path)) {
            return textureCache.get(path);
        }
        BufferedImage image = null;
        ZipEntry entry = zip.getEntry(path);
        if (entry != null) {
            try (InputStream in = zip.getInputStream(entry)) {
                BufferedImage raw = ImageIO.read(in);
                if (raw != null) {
                    // Animated textures are vertical strips of square frames: keep the first one.
                    int w = raw.getWidth();
                    int h = raw.getHeight() > w && raw.getHeight() % w == 0 ? w : raw.getHeight();
                    image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = image.createGraphics();
                    g.drawImage(raw, 0, 0, null);
                    g.dispose();
                }
            }
        }
        textureCache.put(path, image);
        return image;
    }

    private JsonObject json(String path) {
        if (jsonCache.containsKey(path)) {
            return jsonCache.get(path);
        }
        JsonObject json = null;
        ZipEntry entry = zip.getEntry(path);
        if (entry != null) {
            try (InputStream in = zip.getInputStream(entry)) {
                json = gson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            } catch (IOException | RuntimeException e) {
                json = null;
            }
        }
        jsonCache.put(path, json);
        return json;
    }

    private static double[] vec(JsonArray array) {
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    private static String strip(String ref) {
        return ref.startsWith("minecraft:") ? ref.substring("minecraft:".length()) : ref;
    }
}
