package mcpbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Dev-client MCP bridge for Minecraft 1.12.2. Speaks the HTTP protocol of the mcpfabric mod
 * (POST /rpc with {method, params} -> {ok, result | error}), so the mcpfabric MCP server's tools work against
 * this 1.12 dev client. Only a subset of the methods is implemented; the rest answer "unsupported".
 * <p>
 * Lives in the devtools source set: it is on the runClient classpath only and never shipped with Albedo.
 */
@Mod(modid = McpBridge.MODID, name = "MCP Bridge (1.12 dev)", version = "1.0", clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public class McpBridge {
    public static final String MODID = "mcpbridge";
    public static final Logger LOGGER = LogManager.getLogger("McpBridge");

    private static BridgeServer server;

    /** Same file name and fields as mcpfabric's config (subset). */
    public static final class Config {
        public String host = "127.0.0.1";
        public int port = 25599;
        public String token = "";
        /** Off by default for the dev client: loopback-only and web pages (Origin header) are refused anyway. */
        public boolean requireAuth = false;
        public int callTimeoutMs = 8000;
    }

    public static Config config = new Config();

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = loadConfig(new File(event.getModConfigurationDirectory(), "mcpfabric.config.json"));
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        Handlers handlers = new Handlers();
        MinecraftForge.EVENT_BUS.register(handlers);
        try {
            server = new BridgeServer(config, handlers);
            server.start();
            LOGGER.info("MCP bridge listening on http://{}:{} (auth {})", config.host, config.port, config.requireAuth ? "required" : "off");
        } catch (IOException e) {
            LOGGER.error("Could not start the MCP bridge on {}:{} (is another game using the port?)", config.host, config.port, e);
        }
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new AlbedoCommand());
    }

    private static Config loadConfig(File file) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Config cfg = null;
        if (file.isFile()) {
            try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                cfg = gson.fromJson(r, Config.class);
            } catch (Exception e) {
                LOGGER.warn("Could not read {}, using defaults", file, e);
            }
        }
        if (cfg == null) {
            cfg = new Config();
        }
        if (cfg.token == null || cfg.token.trim().isEmpty()) {
            cfg.token = UUID.randomUUID().toString().replace("-", "");
        }
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(cfg, w);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
        return cfg;
    }
}
