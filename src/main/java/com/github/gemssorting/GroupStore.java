package com.github.gemssorting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/**
 * Item groups edited from the web interface and used by ".group" name tags.
 *
 * Every item belongs to at most one group. The groups are saved in groups.json; the sorting
 * code reads an immutable snapshot, so the web server thread can replace it at any time.
 */
final class GroupStore {

    static final int MAX_NAME_LENGTH = 40;

    /** The JSON shape shared with the web interface. */
    static final class State {
        long version;
        List<Group> groups = new ArrayList<>();
    }

    static final class Group {
        String id;
        String name;
        List<String> items = new ArrayList<>();
    }

    /** Thrown when a submitted state is invalid; the message is shown in the web interface. */
    static final class InvalidStateException extends Exception {
        InvalidStateException(String message) {
            super(message);
        }
    }

    private record Snapshot(State state, Map<Material, String> groupOf) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Logger logger;
    private volatile Snapshot snapshot;

    GroupStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.snapshot = index(new State());
    }

    void load() throws IOException {
        if (!Files.exists(file)) {
            snapshot = index(new State());
            return;
        }
        State state;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            state = GSON.fromJson(reader, State.class);
        }
        if (state == null) {
            state = new State();
        }
        // Drop items that no longer exist (e.g. after a Minecraft update) instead of refusing to start.
        for (Group group : state.groups) {
            group.items.removeIf(id -> material(id) == null);
        }
        snapshot = index(state);
    }

    /** The normalized group code of this item type, or null if it is in no group. */
    String groupOf(Material type) {
        return snapshot.groupOf().get(type);
    }

    /** A copy of the current state, safe to serialize. */
    State state() {
        return GSON.fromJson(GSON.toJson(snapshot.state()), State.class);
    }

    Gson gson() {
        return GSON;
    }

    /**
     * Replaces all groups. Fails with {@link ConflictException} if someone else saved in the
     * meantime (the submitted version is not the current one).
     */
    synchronized State replace(State submitted, String editor) throws InvalidStateException, ConflictException, IOException {
        State current = snapshot.state();
        if (submitted.version != current.version) {
            throw new ConflictException();
        }
        State next = validate(submitted);
        next.version = current.version + 1;
        write(next);
        snapshot = index(next);
        logger.info(editor + " saved the item groups (" + next.groups.size() + " groups, version " + next.version + ")");
        return state();
    }

    static final class ConflictException extends Exception {}

    private static State validate(State submitted) throws InvalidStateException {
        State clean = new State();
        Set<String> codes = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Map<Material, String> owner = new EnumMap<>(Material.class);
        for (Group group : submitted.groups == null ? List.<Group>of() : submitted.groups) {
            if (group == null) {
                continue;
            }
            String name = group.name == null ? "" : group.name.trim().replaceAll("\\s+", " ");
            while (name.startsWith(".") || name.startsWith("#")) {
                name = name.substring(1).trim();
            }
            if (name.isEmpty()) {
                throw new InvalidStateException("Ogni gruppo deve avere un nome.");
            }
            if (name.length() > MAX_NAME_LENGTH) {
                throw new InvalidStateException("Il nome \"" + name + "\" è troppo lungo (massimo " + MAX_NAME_LENGTH + " caratteri).");
            }
            String code = ItemNames.normalize(name);
            if (!codes.add(code)) {
                throw new InvalidStateException("Esistono due gruppi chiamati \"" + name + "\".");
            }
            Group out = new Group();
            out.id = group.id != null && group.id.matches("[A-Za-z0-9-]{1,64}") && ids.add(group.id)
                    ? group.id
                    : UUID.randomUUID().toString();
            ids.add(out.id);
            out.name = name;
            for (String id : group.items == null ? List.<String>of() : group.items) {
                Material type = material(id);
                if (type == null) {
                    throw new InvalidStateException("Oggetto sconosciuto: " + id);
                }
                String previous = owner.putIfAbsent(type, name);
                if (previous != null) {
                    if (previous.equals(name)) {
                        continue; // duplicate inside the same group
                    }
                    throw new InvalidStateException(id + " è sia in \"" + previous + "\" sia in \"" + name + "\".");
                }
                out.items.add(type.getKey().getKey());
            }
            clean.groups.add(out);
        }
        return clean;
    }

    private static Snapshot index(State state) {
        Map<Material, String> groupOf = new EnumMap<>(Material.class);
        for (Group group : state.groups) {
            String code = ItemNames.normalize(group.name);
            for (String id : group.items) {
                Material type = material(id);
                if (type != null) {
                    groupOf.putIfAbsent(type, code);
                }
            }
        }
        return new Snapshot(state, groupOf);
    }

    static Material material(String id) {
        if (id == null) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        if (key == null || !key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
            return null;
        }
        Material type = Material.matchMaterial(key.getKey());
        return type != null && type.isItem() && !type.isAir() && !type.isLegacy() ? type : null;
    }

    private void write(State state) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(state, writer);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
