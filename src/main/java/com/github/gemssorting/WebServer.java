package com.github.gemssorting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The web interface: static files from the jar's web/ folder plus a small JSON API.
 *
 * GET  /login?t=...   one-time link from "/gems web": opens a session and goes to /
 * GET  /api/session   who is logged in (401 if nobody)
 * POST /api/logout
 * GET  /api/items     the item catalog (names and icons)
 * GET  /api/groups    the groups
 * PUT  /api/groups    replaces the groups (409 if someone else saved in the meantime)
 * GET  /atlas.png     all item icons in one image (see Assets)
 */
final class WebServer {

    private static final String COOKIE = "gems_session";
    /** Language chosen in the web interface ("en" or "it"), set by the page itself. */
    private static final String LANG_COOKIE = "gems_lang";
    private static final int MAX_BODY = 1 << 20;
    private static final Map<String, String> STATIC = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/i18n.js", "i18n.js",
            "/app.css", "app.css",
            "/sortable.min.js", "sortable.min.js",
            "/favicon.svg", "favicon.svg");

    private final GroupStore groups;
    private final Assets assets;
    private final Auth auth;
    private final boolean secureCookie;
    private final Logger logger;
    private final Gson gson;
    private final HttpServer server;
    private final ExecutorService executor;

    WebServer(GroupStore groups, Assets assets, Auth auth, String bind, int port, boolean secureCookie, Logger logger)
            throws IOException {
        this.groups = groups;
        this.assets = assets;
        this.auth = auth;
        this.secureCookie = secureCookie;
        this.logger = logger;
        this.gson = groups.gson();
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        this.executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "GemsSorting web");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            route(exchange);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Web request failed: " + exchange.getRequestURI(), e);
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/login") && method.equals("GET")) {
            String session = auth.redeem(query(exchange, "t"));
            if (session != null) {
                exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + session
                        + "; Path=/; Max-Age=" + auth.sessionSeconds() + "; HttpOnly; SameSite=Lax"
                        + (secureCookie ? "; Secure" : ""));
            }
            redirect(exchange, session != null ? "/" : "/?login=expired");
            return;
        }
        if (path.equals("/atlas.png") && method.equals("GET")) {
            Path atlas = assets.atlas();
            if (atlas == null) {
                send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // The URL carries ?v=<atlas version>, so it can be cached for long.
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=2592000, immutable");
            send(exchange, 200, "image/png", Files.readAllBytes(atlas));
            return;
        }
        if (path.startsWith("/api/")) {
            api(exchange, method, path);
            return;
        }
        String resource = STATIC.get(path);
        if (resource != null && method.equals("GET")) {
            staticFile(exchange, resource);
            return;
        }
        send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    private void api(HttpExchange exchange, String method, String path) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        String token = cookie(exchange, COOKIE);
        boolean it = "it".equals(cookie(exchange, LANG_COOKIE));
        Auth.Session session = auth.session(token);
        if (session == null) {
            error(exchange, 401, it ? "Accesso scaduto: scrivi /gems web in gioco per entrare."
                    : "Session expired: type /gems web in game to log in.");
            return;
        }
        // Writes need a custom header, which a cross-site form or image cannot send.
        if (!method.equals("GET") && !"1".equals(exchange.getRequestHeaders().getFirst("X-Gems"))) {
            error(exchange, 403, it ? "Richiesta non valida." : "Invalid request.");
            return;
        }
        switch (method + " " + path) {
            case "GET /api/session" -> {
                JsonObject body = new JsonObject();
                body.addProperty("name", session.name());
                json(exchange, 200, body);
            }
            case "POST /api/logout" -> {
                auth.logout(token);
                exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                        + (secureCookie ? "; Secure" : ""));
                json(exchange, 200, new JsonObject());
            }
            case "GET /api/items" -> {
                JsonObject body = new JsonObject();
                body.addProperty("ready", assets.ready());
                JsonObject atlas = new JsonObject();
                atlas.addProperty("url", "/atlas.png?v=" + assets.atlasVersion());
                atlas.addProperty("columns", Assets.ATLAS_COLUMNS);
                atlas.addProperty("size", Assets.ICON_SIZE);
                body.add("atlas", atlas);
                body.add("items", gson.toJsonTree(assets.items()));
                json(exchange, 200, body);
            }
            case "GET /api/groups" -> json(exchange, 200, gson.toJsonTree(groups.state()));
            case "PUT /api/groups" -> {
                GroupStore.State submitted;
                try {
                    submitted = gson.fromJson(new String(body(exchange), StandardCharsets.UTF_8), GroupStore.State.class);
                } catch (JsonParseException | IOException e) {
                    error(exchange, 400, it ? "Dati non validi." : "Invalid data.");
                    return;
                }
                if (submitted == null) {
                    error(exchange, 400, it ? "Dati non validi." : "Invalid data.");
                    return;
                }
                try {
                    json(exchange, 200, gson.toJsonTree(groups.replace(submitted, session.name() + " (web)")));
                } catch (GroupStore.InvalidStateException e) {
                    error(exchange, 400, e.message(it));
                } catch (GroupStore.ConflictException e) {
                    JsonObject body = new JsonObject();
                    body.addProperty("error", it
                            ? "Qualcun altro ha modificato i gruppi: ho ricaricato la versione aggiornata."
                            : "Someone else changed the groups: the latest version has been reloaded.");
                    body.add("state", gson.toJsonTree(groups.state()));
                    json(exchange, 409, body);
                }
            }
            default -> error(exchange, 404, it ? "Non trovato." : "Not found.");
        }
    }

    private void staticFile(HttpExchange exchange, String name) throws IOException {
        byte[] data;
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            data = in.readAllBytes();
        }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        if (name.endsWith(".html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'");
        }
        send(exchange, 200, type, data);
    }

    private void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", gson.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        json(exchange, status, body);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(302, -1);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    private static byte[] body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) {
                throw new IOException("Body too large");
            }
            return data;
        }
    }

    private static String cookie(HttpExchange exchange, String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals(name)) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
