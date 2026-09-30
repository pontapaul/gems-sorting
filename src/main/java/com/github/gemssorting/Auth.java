package com.github.gemssorting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * Login for the web interface: "/gems web" gives an operator a one-time link, which opens a
 * browser session. Sessions are kept across restarts (only token hashes are stored) and are
 * refused as soon as the player is no longer an operator.
 */
final class Auth {

    /** A logged-in browser. uuid is null for sessions opened from the server console. */
    record Session(String uuid, String name, long expires) {}

    private record Pending(String uuid, String name, long expires) {}

    private record OpCheck(boolean op, long expires) {}

    static final Duration LINK_VALIDITY = Duration.ofMinutes(5);
    private static final long OP_CHECK_MS = 30_000;

    private final Plugin plugin;
    private final Path file;
    private final Duration sessionValidity;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();
    private final Gson gson = new Gson();
    private final Map<String, Pending> links = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, OpCheck> opChecks = new HashMap<>();

    Auth(Plugin plugin, Path file, Duration sessionValidity) {
        this.plugin = plugin;
        this.file = file;
        this.sessionValidity = sessionValidity;
        this.logger = plugin.getLogger();
        load();
    }

    /** A one-time login token for this player (uuid null for the console). */
    synchronized String issueLink(UUID uuid, String name) {
        String token = newToken();
        long now = System.currentTimeMillis();
        links.values().removeIf(pending -> pending.expires() <= now);
        links.put(hash(token), new Pending(uuid == null ? null : uuid.toString(), name, now + LINK_VALIDITY.toMillis()));
        return token;
    }

    /** Exchanges a login token for a session token, or returns null if it is invalid or used. */
    synchronized String redeem(String linkToken) {
        if (linkToken == null) {
            return null;
        }
        Pending pending = links.remove(hash(linkToken));
        long now = System.currentTimeMillis();
        if (pending == null || pending.expires() <= now) {
            return null;
        }
        String token = newToken();
        sessions.values().removeIf(session -> session.expires() <= now);
        sessions.put(hash(token), new Session(pending.uuid(), pending.name(), now + sessionValidity.toMillis()));
        save();
        logger.info(pending.name() + " logged in to the web interface");
        return token;
    }

    /** The session of this token, or null if it is unknown, expired or no longer an operator's. */
    Session session(String token) {
        if (token == null) {
            return null;
        }
        Session session;
        synchronized (this) {
            session = sessions.get(hash(token));
        }
        if (session == null || session.expires() <= System.currentTimeMillis()) {
            return null;
        }
        return session.uuid() == null || isOp(session.uuid()) ? session : null;
    }

    synchronized void logout(String token) {
        if (token != null && sessions.remove(hash(token)) != null) {
            save();
        }
    }

    long sessionSeconds() {
        return sessionValidity.toSeconds();
    }

    private boolean isOp(String uuid) {
        long now = System.currentTimeMillis();
        synchronized (opChecks) {
            OpCheck check = opChecks.get(uuid);
            if (check != null && check.expires() > now) {
                return check.op();
            }
        }
        boolean op;
        try {
            op = plugin.getServer().getScheduler()
                    .callSyncMethod(plugin, () -> plugin.getServer().getOfflinePlayer(UUID.fromString(uuid)).isOp())
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
        synchronized (opChecks) {
            opChecks.put(uuid, new OpCheck(op, now + OP_CHECK_MS));
        }
        return op;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Session> saved = gson.fromJson(reader, new TypeToken<Map<String, Session>>() {}.getType());
            if (saved != null) {
                long now = System.currentTimeMillis();
                saved.forEach((key, session) -> {
                    if (session != null && session.expires() > now) {
                        sessions.put(key, session);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not read the web sessions; everyone has to log in again", e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(sessions, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save the web sessions", e);
        }
    }
}
