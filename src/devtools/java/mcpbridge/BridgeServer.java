package mcpbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Loopback HTTP server: GET /health, GET /info, POST /rpc (same envelope as mcpfabric). */
final class BridgeServer {
    private static final Gson GSON = new Gson();

    private final McpBridge.Config cfg;
    private final Handlers handlers;
    private HttpServer http;

    BridgeServer(McpBridge.Config cfg, Handlers handlers) {
        this.cfg = cfg;
        this.handlers = handlers;
    }

    void start() throws IOException {
        InetAddress address = InetAddress.getByName(cfg.host);
        if (!address.isLoopbackAddress()) {
            McpBridge.LOGGER.warn("Refusing non-loopback host {}, binding to 127.0.0.1", cfg.host);
            address = InetAddress.getLoopbackAddress();
        }
        http = HttpServer.create(new InetSocketAddress(address, cfg.port), 0);
        http.createContext("/health", ex -> respond(ex, 200, "{\"ok\":true}"));
        http.createContext("/info", ex -> {
            if (guard(ex)) {
                respond(ex, 200, GSON.toJson(handlers.dispatch("info.status", new JsonObject())));
            }
        });
        http.createContext("/rpc", this::handleRpc);
        http.createContext("/events", ex -> {
            if (guard(ex)) {
                respond(ex, 404, GSON.toJson(Handlers.error("unsupported", "No event stream in the 1.12 dev bridge.")));
            }
        });
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "McpBridge HTTP");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    private void handleRpc(HttpExchange ex) throws IOException {
        if (!guard(ex)) {
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, GSON.toJson(Handlers.error("bad_request", "POST only")));
            return;
        }
        JsonObject body;
        try (Reader r = new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8)) {
            body = new JsonParser().parse(r).getAsJsonObject();
        } catch (Exception e) {
            respond(ex, 400, GSON.toJson(Handlers.error("bad_request", "Body must be a JSON object {method, params}")));
            return;
        }
        String method = body.has("method") ? body.get("method").getAsString() : "";
        JsonObject params = body.has("params") && body.get("params").isJsonObject() ? body.getAsJsonObject("params") : new JsonObject();
        respond(ex, 200, GSON.toJson(handlers.dispatch(method, params)));
    }

    /** Refuses web pages (Origin header) and, if configured, requests without the bearer token. */
    private boolean guard(HttpExchange ex) throws IOException {
        if (ex.getRequestHeaders().getFirst("Origin") != null) {
            respond(ex, 403, GSON.toJson(Handlers.error("forbidden", "Requests from web pages are refused.")));
            return false;
        }
        if (cfg.requireAuth) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + cfg.token)) {
                respond(ex, 401, GSON.toJson(Handlers.error("unauthorized", "Missing or invalid bearer token.")));
                return false;
            }
        }
        return true;
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
