package mcpbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import elucent.albedo.event.LightUniformEvent;
import elucent.albedo.lighting.Light;
import elucent.albedo.lighting.LightManager;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldSummary;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** RPC methods, named and shaped like mcpfabric's handlers (subset). */
public final class Handlers {
    private interface Handler {
        JsonElement handle(JsonObject params) throws Exception;
    }

    /** Thrown by handlers for an error envelope with a specific code. */
    static final class RpcException extends Exception {
        final String code;

        RpcException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final Map<String, Handler> methods = new LinkedHashMap<>();
    private final Deque<JsonObject> chat = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<CompletableFuture<BufferedImage>> screenshots = new ConcurrentLinkedQueue<>();
    private int releaseJumpAt = -1;
    private int clientTicks;

    /** Albedo state captured on the client thread each frame (read by /albedo and albedo.* below). */
    static volatile JsonObject lastFrameLights = new JsonObject();

    Handlers() {
        // info
        methods.put("info.status", p -> status());
        methods.put("info.capabilities", p -> {
            JsonObject groups = new JsonObject();
            for (String g : new String[]{"info", "world_read", "world_write", "entities", "players_admin", "command", "player_local",
                    "control", "interact", "inventory", "chat", "vision", "session"}) {
                groups.addProperty(g, true);
            }
            JsonObject o = new JsonObject();
            o.add("groups", groups);
            return o;
        });
        // world
        methods.put("world.getBlock", p -> onServer(() -> blockInfo(world(p), pos(p))));
        methods.put("world.getBlocks", this::getBlocks);
        methods.put("world.findBlocks", this::findBlocks);
        methods.put("world.setBlock", p -> command("setblock " + coords(p) + " " + blockArg(str(p, "blockId", null))));
        methods.put("world.fill", p -> command("fill " + coords(p.getAsJsonObject("from")) + " " + coords(p.getAsJsonObject("to")) + " " + blockArg(str(p, "blockId", null))));
        methods.put("world.setTime", p -> command("time set " + p.get("time").getAsInt()));
        methods.put("world.setWeather", p -> command("weather " + str(p, "weather", "clear") + (p.has("durationSeconds") ? " " + p.get("durationSeconds").getAsInt() : "")));
        methods.put("world.getTimeAndWeather", p -> onServer(() -> {
            WorldServer w = server().getWorld(0);
            JsonObject o = new JsonObject();
            o.addProperty("dayTime", w.getWorldTime() % 24000);
            o.addProperty("gameTime", w.getTotalWorldTime());
            o.addProperty("raining", w.isRaining());
            o.addProperty("thundering", w.isThundering());
            return o;
        }));
        // entities
        methods.put("entities.query", this::queryEntities);
        methods.put("entities.summon", p -> command("summon " + str(p, "type", null) + " " + coords(p) + (p.has("nbt") ? " " + str(p, "nbt", "") : "")));
        methods.put("entities.remove", p -> onServer(() -> {
            Entity e = server().getEntityFromUuid(UUID.fromString(str(p, "uuid", null)));
            if (e == null) {
                throw new RpcException("not_found", "No entity with that UUID");
            }
            e.setDead();
            JsonObject o = new JsonObject();
            o.addProperty("removed", true);
            return o;
        }));
        // players (admin)
        methods.put("players.list", p -> onServer(() -> {
            JsonArray a = new JsonArray();
            for (EntityPlayerMP pl : server().getPlayerList().getPlayers()) {
                a.add(entityInfo(pl, null));
            }
            JsonObject o = new JsonObject();
            o.add("players", a);
            return o;
        }));
        methods.put("players.teleport", this::teleport);
        methods.put("players.give", p -> command("give " + playerName(p) + " " + str(p, "itemId", null) + " " + intOr(p, "count", 1) + " 0" + (p.has("nbt") ? " " + str(p, "nbt", "") : "")));
        methods.put("players.setGameMode", p -> command("gamemode " + str(p, "mode", "creative") + " " + playerName(p)));
        methods.put("command.run", p -> command(str(p, "command", "")));
        // chat
        methods.put("chat.send", p -> onClient(() -> {
            player().sendChatMessage(str(p, "message", ""));
            JsonObject o = new JsonObject();
            o.addProperty("sent", true);
            return o;
        }));
        methods.put("chat.getRecent", p -> {
            int limit = intOr(p, "limit", 50);
            JsonArray a = new JsonArray();
            synchronized (chat) {
                List<JsonObject> all = new ArrayList<>(chat);
                for (int i = Math.max(0, all.size() - limit); i < all.size(); i++) {
                    a.add(all.get(i));
                }
            }
            JsonObject o = new JsonObject();
            o.add("messages", a);
            return o;
        });
        // local player
        methods.put("player.getState", p -> onClient(this::playerState));
        methods.put("player.getInventory", p -> onClient(() -> {
            EntityPlayerSP pl = player();
            JsonArray a = new JsonArray();
            for (int i = 0; i < pl.inventory.getSizeInventory(); i++) {
                ItemStack s = pl.inventory.getStackInSlot(i);
                if (!s.isEmpty()) {
                    JsonObject o = stackInfo(s);
                    o.addProperty("slot", i);
                    a.add(o);
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("selectedHotbar", pl.inventory.currentItem);
            o.add("items", a);
            return o;
        }));
        // control
        methods.put("control.look", p -> onClient(() -> {
            EntityPlayerSP pl = player();
            float yaw = p.has("yaw") ? p.get("yaw").getAsFloat() : pl.rotationYaw;
            float pitch = p.has("pitch") ? p.get("pitch").getAsFloat() : pl.rotationPitch;
            yaw += p.has("deltaYaw") ? p.get("deltaYaw").getAsFloat() : 0;
            pitch += p.has("deltaPitch") ? p.get("deltaPitch").getAsFloat() : 0;
            setRotation(pl, yaw, pitch);
            return playerState();
        }));
        methods.put("control.lookAt", p -> onClient(() -> {
            EntityPlayerSP pl = player();
            Vec3d eye = pl.getPositionEyes(1);
            double dx = num(p, "x") - eye.x, dy = num(p, "y") - eye.y, dz = num(p, "z") - eye.z;
            float yaw = (float) (MathHelper.atan2(dz, dx) * 180 / Math.PI) - 90;
            float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180 / Math.PI);
            setRotation(pl, yaw, pitch);
            return playerState();
        }));
        methods.put("control.setInput", p -> onClient(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            key(p, "forward", mc.gameSettings.keyBindForward);
            key(p, "back", mc.gameSettings.keyBindBack);
            key(p, "left", mc.gameSettings.keyBindLeft);
            key(p, "right", mc.gameSettings.keyBindRight);
            key(p, "jump", mc.gameSettings.keyBindJump);
            key(p, "sneak", mc.gameSettings.keyBindSneak);
            key(p, "sprint", mc.gameSettings.keyBindSprint);
            return playerState();
        }));
        methods.put("control.stop", p -> onClient(() -> {
            KeyBinding.unPressAllKeys();
            return playerState();
        }));
        methods.put("control.jumpOnce", p -> onClient(() -> {
            KeyBinding.setKeyBindState(Minecraft.getMinecraft().gameSettings.keyBindJump.getKeyCode(), true);
            releaseJumpAt = clientTicks + 3;
            return playerState();
        }));
        // interact / inventory
        methods.put("interact.placeBlock", p -> onClient(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            BlockPos pos = pos(p);
            EnumFacing face = EnumFacing.byName(str(p, "face", "up"));
            Vec3d hit = new Vec3d(pos).addVector(0.5 + face.getFrontOffsetX() * 0.5, 0.5 + face.getFrontOffsetY() * 0.5, 0.5 + face.getFrontOffsetZ() * 0.5);
            JsonObject o = new JsonObject();
            o.addProperty("result", mc.playerController.processRightClickBlock(player(), mc.world, pos, face, hit, EnumHand.MAIN_HAND).name());
            player().swingArm(EnumHand.MAIN_HAND);
            return o;
        }));
        methods.put("interact.breakBlock", p -> command("setblock " + coords(p) + " minecraft:air 0 destroy"));
        methods.put("interact.useItem", p -> onClient(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            JsonObject o = new JsonObject();
            o.addProperty("result", mc.playerController.processRightClick(player(), mc.world, EnumHand.MAIN_HAND).name());
            return o;
        }));
        methods.put("inventory.selectHotbar", p -> onClient(() -> {
            player().inventory.currentItem = MathHelper.clamp(p.get("slot").getAsInt(), 0, 8);
            return playerState();
        }));
        // vision
        methods.put("vision.screenshot", p -> screenshot());
        methods.put("vision.describeScene", p -> onClient(() -> describeScene(p)));
        // screens / session
        methods.put("screen.get", p -> onClient(() -> {
            GuiScreen s = Minecraft.getMinecraft().currentScreen;
            JsonObject o = new JsonObject();
            o.addProperty("open", s != null);
            o.addProperty("screen", s == null ? null : s.getClass().getName());
            o.addProperty("inWorld", Minecraft.getMinecraft().world != null);
            return o;
        }));
        methods.put("screen.close", p -> onClient(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player != null && mc.player.isDead) {
                mc.player.respawnPlayer();
            }
            mc.displayGuiScreen(null);
            JsonObject o = new JsonObject();
            o.addProperty("closed", true);
            return o;
        }));
        methods.put("session.worlds", p -> onClient(() -> {
            JsonArray a = new JsonArray();
            List<WorldSummary> saves = new ArrayList<>(Minecraft.getMinecraft().getSaveLoader().getSaveList());
            saves.sort(Comparator.comparingLong(WorldSummary::getLastTimePlayed).reversed());
            for (WorldSummary s : saves) {
                JsonObject o = new JsonObject();
                o.addProperty("worldId", "sp:" + s.getFileName());
                o.addProperty("name", s.getDisplayName());
                o.addProperty("lastPlayed", s.getLastTimePlayed());
                a.add(o);
            }
            JsonObject o = new JsonObject();
            o.add("worlds", a);
            return o;
        }));
        methods.put("session.join", this::join);
        methods.put("session.info", p -> onClient(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            JsonObject o = new JsonObject();
            o.addProperty("inWorld", mc.world != null);
            o.addProperty("worldId", mc.getIntegratedServer() != null ? "sp:" + mc.getIntegratedServer().getFolderName() : "none");
            return o;
        }));
        // Not an mcpfabric tool: lets scripts close the dev client cleanly (curl -d '{"method":"client.shutdown"}' .../rpc)
        methods.put("client.shutdown", p -> onClient(() -> {
            Minecraft.getMinecraft().shutdown();
            JsonObject o = new JsonObject();
            o.addProperty("shuttingDown", true);
            return o;
        }));
        methods.put("events.getRecent", p -> {
            JsonObject o = new JsonObject();
            o.add("events", new JsonArray());
            o.addProperty("note", "The 1.12 dev bridge records no game events.");
            return o;
        });
    }

    // ---- dispatch ----

    JsonObject dispatch(String method, JsonObject params) {
        Handler h = methods.get(method);
        if (h == null) {
            return error("unsupported", "Method " + method + " is not implemented in the 1.12 dev bridge. Implemented: " + methods.keySet());
        }
        try {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.add("result", h.handle(params));
            return o;
        } catch (RpcException e) {
            return error(e.code, e.getMessage());
        } catch (TimeoutException e) {
            return error("timeout", "The game thread did not answer in time");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RpcException) {
                return error(((RpcException) c).code, c.getMessage());
            }
            McpBridge.LOGGER.warn("RPC {} failed", method, c);
            return error("internal", String.valueOf(c));
        } catch (Exception e) {
            McpBridge.LOGGER.warn("RPC {} failed", method, e);
            return error("internal", String.valueOf(e));
        }
    }

    static JsonObject error(String code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.add("error", err);
        return o;
    }

    private JsonObject status() {
        Minecraft mc = Minecraft.getMinecraft();
        MinecraftServer srv = mc.getIntegratedServer();
        JsonObject o = new JsonObject();
        o.addProperty("mod", "mcpbridge (mcpfabric-compatible, 1.12 dev)");
        o.addProperty("minecraftVersion", "1.12.2");
        o.addProperty("loader", "forge");
        o.addProperty("side", "client");
        o.addProperty("serverPresent", srv != null);
        o.addProperty("integratedServer", srv != null);
        o.addProperty("inWorld", mc.world != null);
        JsonArray caps = new JsonArray();
        for (String c : new String[]{"info", "chat", "player_local", "control", "interact", "inventory", "vision", "session"}) {
            caps.add(c);
        }
        if (srv != null) {
            for (String c : new String[]{"world_read", "world_write", "entities", "command", "players_admin"}) {
                caps.add(c);
            }
        }
        o.add("capabilities", caps);
        JsonArray m = new JsonArray();
        methods.keySet().forEach(m::add);
        o.add("methods", m);
        o.addProperty("albedoDebug", "Run the command \"albedo\" (command.run) for Albedo's lighting state; \"albedo help\" lists options.");
        return o;
    }

    // ---- thread helpers ----

    private <T> T onClient(Callable<T> task) throws Exception {
        return Minecraft.getMinecraft().addScheduledTask(task).get(McpBridge.config.callTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private <T> T onServer(Callable<T> task) throws Exception {
        return server().callFromMainThread(task).get(McpBridge.config.callTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private static MinecraftServer server() throws RpcException {
        MinecraftServer srv = Minecraft.getMinecraft().getIntegratedServer();
        if (srv == null) {
            throw new RpcException("no_server", "No integrated server: join a singleplayer world first (session.join)");
        }
        return srv;
    }

    private static EntityPlayerSP player() throws RpcException {
        EntityPlayerSP p = Minecraft.getMinecraft().player;
        if (p == null) {
            throw new RpcException("no_client_player", "Not in a world");
        }
        return p;
    }

    // ---- commands ----

    /** Runs a command on the server thread as the (op) player and captures its chat output. */
    private JsonObject command(String raw) throws Exception {
        String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
        return onServer(() -> {
            MinecraftServer srv = server();
            List<EntityPlayerMP> players = srv.getPlayerList().getPlayers();
            EntityPlayerMP pl = players.isEmpty() ? null : players.get(0);
            List<String> output = new ArrayList<>();
            ICommandSender sender = new ICommandSender() {
                @Override
                public String getName() {
                    return pl != null ? pl.getName() : "McpBridge";
                }

                @Override
                public void sendMessage(ITextComponent component) {
                    output.add(component.getUnformattedText());
                }

                @Override
                public boolean canUseCommand(int permLevel, String commandName) {
                    return true;
                }

                @Override
                public BlockPos getPosition() {
                    return pl != null ? pl.getPosition() : BlockPos.ORIGIN;
                }

                @Override
                public Vec3d getPositionVector() {
                    return pl != null ? pl.getPositionVector() : Vec3d.ZERO;
                }

                @Override
                public World getEntityWorld() {
                    return pl != null ? pl.world : srv.getWorld(0);
                }

                @Override
                public Entity getCommandSenderEntity() {
                    return pl;
                }

                @Override
                public boolean sendCommandFeedback() {
                    // false: no "[Developer: ...]" admin echo in the player's chat; the feedback itself still
                    // reaches sendMessage above (gamerule sendCommandFeedback)
                    return false;
                }

                @Override
                public MinecraftServer getServer() {
                    return srv;
                }
            };
            int result = srv.getCommandManager().executeCommand(sender, cmd);
            JsonObject o = new JsonObject();
            o.addProperty("command", cmd);
            o.addProperty("success", result > 0);
            o.addProperty("result", result);
            JsonArray a = new JsonArray();
            output.forEach(a::add);
            o.add("output", a);
            return o;
        });
    }

    private static String coords(JsonObject p) throws RpcException {
        return (int) Math.floor(num(p, "x")) + " " + (int) Math.floor(num(p, "y")) + " " + (int) Math.floor(num(p, "z"));
    }

    /** "minecraft:torch[facing=up]" -> "minecraft:torch facing=up" (1.12 setblock/fill syntax). */
    private static String blockArg(String id) throws RpcException {
        if (id == null) {
            throw new RpcException("bad_params", "blockId is required");
        }
        int b = id.indexOf('[');
        if (b < 0) {
            return id;
        }
        String props = id.substring(b + 1, id.endsWith("]") ? id.length() - 1 : id.length());
        return id.substring(0, b) + " " + props;
    }

    private String playerName(JsonObject p) throws Exception {
        String name = str(p, "player", "@p");
        return name.equals("@all") ? "@a" : name;
    }

    // ---- world / entities ----

    private static World world(JsonObject p) throws RpcException {
        MinecraftServer srv = server();
        int dim;
        String d = str(p, "dimension", null);
        if (d == null) {
            List<EntityPlayerMP> players = srv.getPlayerList().getPlayers();
            dim = players.isEmpty() ? 0 : players.get(0).dimension;
        } else if (d.endsWith("nether")) {
            dim = -1;
        } else if (d.endsWith("end")) {
            dim = 1;
        } else if (d.endsWith("overworld")) {
            dim = 0;
        } else {
            dim = Integer.parseInt(d);
        }
        return srv.getWorld(dim);
    }

    private static JsonObject blockInfo(World w, BlockPos pos) {
        IBlockState state = w.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        o.addProperty("id", String.valueOf(state.getBlock().getRegistryName()));
        JsonObject props = new JsonObject();
        for (Map.Entry<IProperty<?>, Comparable<?>> e : state.getProperties().entrySet()) {
            props.addProperty(e.getKey().getName(), String.valueOf(e.getValue()));
        }
        o.add("properties", props);
        o.addProperty("blockLight", w.getLightFor(EnumSkyBlock.BLOCK, pos));
        o.addProperty("skyLight", w.getLightFor(EnumSkyBlock.SKY, pos));
        o.addProperty("lightEmission", state.getLightValue(w, pos));
        o.addProperty("opaqueCube", state.isOpaqueCube());
        return o;
    }

    private JsonElement getBlocks(JsonObject p) throws Exception {
        return onServer(() -> {
            World w = world(p);
            JsonObject from = p.getAsJsonObject("from"), to = p.getAsJsonObject("to");
            boolean includeAir = bool(p, "includeAir", false);
            int max = intOr(p, "maxBlocks", 4096);
            BlockPos a = pos(from), b = pos(to);
            JsonArray arr = new JsonArray();
            outer:
            for (BlockPos pos : BlockPos.getAllInBox(a, b)) {
                if (!includeAir && w.isAirBlock(pos)) {
                    continue;
                }
                if (arr.size() >= max) {
                    break outer;
                }
                arr.add(blockInfo(w, pos));
            }
            JsonObject o = new JsonObject();
            o.add("blocks", arr);
            return o;
        });
    }

    private JsonElement findBlocks(JsonObject p) throws Exception {
        return onServer(() -> {
            World w = world(p);
            BlockPos c = pos(p.getAsJsonObject("center"));
            int r = intOr(p, "radius", 16);
            int max = intOr(p, "maxResults", 64);
            List<Block> wanted = new ArrayList<>();
            for (JsonElement e : p.getAsJsonArray("blockIds")) {
                Block b = Block.getBlockFromName(e.getAsString());
                if (b != null) {
                    wanted.add(b);
                }
            }
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos pos : BlockPos.getAllInBox(c.add(-r, -r, -r), c.add(r, r, r))) {
                if (pos.getY() >= 0 && pos.getY() < 256 && wanted.contains(w.getBlockState(pos).getBlock())) {
                    found.add(pos.toImmutable());
                }
            }
            found.sort(Comparator.comparingDouble(q -> q.distanceSq(c)));
            JsonArray arr = new JsonArray();
            for (int i = 0; i < Math.min(max, found.size()); i++) {
                arr.add(blockInfo(w, found.get(i)));
            }
            JsonObject o = new JsonObject();
            o.addProperty("total", found.size());
            o.add("blocks", arr);
            return o;
        });
    }

    private JsonElement queryEntities(JsonObject p) throws Exception {
        return onServer(() -> {
            World w = world(p);
            List<EntityPlayerMP> players = server().getPlayerList().getPlayers();
            Vec3d c = p.has("center") ? vec(p.getAsJsonObject("center")) : (players.isEmpty() ? Vec3d.ZERO : players.get(0).getPositionVector());
            double r = p.has("radius") ? p.get("radius").getAsDouble() : 32;
            boolean includePlayers = bool(p, "includePlayers", true);
            boolean onlyLiving = bool(p, "onlyLiving", false);
            int max = intOr(p, "maxResults", 100);
            List<String> types = new ArrayList<>();
            if (p.has("types")) {
                p.getAsJsonArray("types").forEach(e -> types.add(e.getAsString()));
            }
            List<Entity> list = w.getEntitiesWithinAABB(Entity.class, new AxisAlignedBB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r));
            list.sort(Comparator.comparingDouble(e -> e.getDistanceSq(c.x, c.y, c.z)));
            JsonArray arr = new JsonArray();
            for (Entity e : list) {
                if (arr.size() >= max) {
                    break;
                }
                if ((!includePlayers && e instanceof EntityPlayer) || (onlyLiving && !(e instanceof EntityLivingBase))) {
                    continue;
                }
                if (!types.isEmpty() && !types.contains(typeId(e))) {
                    continue;
                }
                arr.add(entityInfo(e, c));
            }
            JsonObject o = new JsonObject();
            o.add("entities", arr);
            return o;
        });
    }

    private static String typeId(Entity e) {
        if (e instanceof EntityPlayer) {
            return "minecraft:player";
        }
        ResourceLocation key = EntityList.getKey(e);
        return key == null ? e.getClass().getSimpleName() : key.toString();
    }

    private static JsonObject entityInfo(Entity e, Vec3d from) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", e.getUniqueID().toString());
        o.addProperty("type", typeId(e));
        o.addProperty("name", e.getName());
        o.addProperty("x", e.posX);
        o.addProperty("y", e.posY);
        o.addProperty("z", e.posZ);
        o.addProperty("dimension", e.dimension);
        if (from != null) {
            o.addProperty("distance", Math.sqrt(e.getDistanceSq(from.x, from.y, from.z)));
        }
        if (e instanceof EntityLivingBase) {
            o.addProperty("health", ((EntityLivingBase) e).getHealth());
        }
        return o;
    }

    private JsonElement teleport(JsonObject p) throws Exception {
        String name = str(p, "player", null);
        return onServer(() -> {
            MinecraftServer srv = server();
            EntityPlayerMP pl = name == null || name.startsWith("@") ? srv.getPlayerList().getPlayers().get(0) : srv.getPlayerList().getPlayerByUsername(name);
            if (pl == null) {
                throw new RpcException("not_found", "No such player");
            }
            double x = num(p, "x"), y = num(p, "y"), z = num(p, "z");
            float yaw = p.has("yaw") ? p.get("yaw").getAsFloat() : pl.rotationYaw;
            float pitch = p.has("pitch") ? p.get("pitch").getAsFloat() : pl.rotationPitch;
            if (p.has("dimension")) {
                int dim = world(p).provider.getDimension();
                if (dim != pl.dimension) {
                    pl.changeDimension(dim, (world, entity, ignored) -> entity.setLocationAndAngles(x, y, z, yaw, pitch));
                }
            }
            pl.connection.setPlayerLocation(x, y, z, yaw, pitch);
            return entityInfo(pl, null);
        });
    }

    // ---- local player ----

    private JsonObject playerState() throws RpcException {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP pl = player();
        JsonObject o = entityInfo(pl, null);
        o.addProperty("yaw", MathHelper.wrapDegrees(pl.rotationYaw));
        o.addProperty("pitch", pl.rotationPitch);
        o.addProperty("food", pl.getFoodStats().getFoodLevel());
        GameType mode = mc.playerController.getCurrentGameType();
        o.addProperty("gameMode", mode.getName());
        o.addProperty("onGround", pl.onGround);
        o.add("mainHand", stackInfo(pl.getHeldItemMainhand()));
        o.add("offHand", stackInfo(pl.getHeldItemOffhand()));
        RayTraceResult hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            JsonObject b = blockInfo(mc.world, hit.getBlockPos());
            b.addProperty("face", hit.sideHit.getName());
            o.add("lookingAtBlock", b);
        } else if (hit != null && hit.typeOfHit == RayTraceResult.Type.ENTITY) {
            o.add("lookingAtEntity", entityInfo(hit.entityHit, pl.getPositionVector()));
        }
        return o;
    }

    private static JsonObject stackInfo(ItemStack s) {
        JsonObject o = new JsonObject();
        if (s.isEmpty()) {
            o.addProperty("empty", true);
            return o;
        }
        o.addProperty("id", String.valueOf(s.getItem().getRegistryName()));
        o.addProperty("meta", s.getMetadata());
        o.addProperty("count", s.getCount());
        o.addProperty("name", s.getDisplayName());
        return o;
    }

    private static void setRotation(EntityPlayerSP pl, float yaw, float pitch) {
        pitch = MathHelper.clamp(pitch, -90, 90);
        pl.rotationYaw = yaw;
        pl.rotationPitch = pitch;
        pl.prevRotationYaw = yaw;
        pl.prevRotationPitch = pitch;
        pl.rotationYawHead = yaw;
    }

    private static void key(JsonObject p, String name, KeyBinding binding) {
        if (p.has(name)) {
            KeyBinding.setKeyBindState(binding.getKeyCode(), p.get(name).getAsBoolean());
        }
    }

    private JsonObject describeScene(JsonObject p) throws RpcException {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP pl = player();
        double max = p.has("maxDistance") ? p.get("maxDistance").getAsDouble() : 48;
        JsonObject o = new JsonObject();
        o.add("player", playerState());
        o.addProperty("dayTime", mc.world.getWorldTime() % 24000);
        JsonArray ents = new JsonArray();
        for (Entity e : mc.world.getEntitiesWithinAABB(Entity.class, pl.getEntityBoundingBox().grow(max))) {
            if (e != pl) {
                ents.add(entityInfo(e, pl.getPositionVector()));
            }
        }
        o.add("entities", ents);
        // Light-emitting blocks around the player, by id
        Map<String, Integer> lights = new HashMap<>();
        BlockPos c = new BlockPos(pl);
        int r = (int) Math.min(max, 24);
        for (BlockPos pos : BlockPos.getAllInBoxMutable(c.add(-r, -r, -r), c.add(r, r, r))) {
            IBlockState s = mc.world.getBlockState(pos);
            if (s.getLightValue(mc.world, pos) > 0) {
                lights.merge(String.valueOf(s.getBlock().getRegistryName()), 1, Integer::sum);
            }
        }
        JsonObject lo = new JsonObject();
        lights.forEach(lo::addProperty);
        o.add("lightSourcesWithin" + r, lo);
        o.add("albedo", lastFrameLights);
        return o;
    }

    private JsonElement screenshot() throws Exception {
        if (Minecraft.getMinecraft().world == null && Minecraft.getMinecraft().currentScreen == null) {
            throw new RpcException("not_ready", "Nothing rendered yet");
        }
        CompletableFuture<BufferedImage> f = new CompletableFuture<>();
        screenshots.add(f);
        BufferedImage img = f.get(McpBridge.config.callTimeoutMs, TimeUnit.MILLISECONDS);
        int maxWidth = 1280;
        if (img.getWidth() > maxWidth) {
            int h = img.getHeight() * maxWidth / img.getWidth();
            BufferedImage scaled = new BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, maxWidth, h, null);
            g.dispose();
            img = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        JsonObject o = new JsonObject();
        o.addProperty("format", "png");
        o.addProperty("width", img.getWidth());
        o.addProperty("height", img.getHeight());
        o.addProperty("base64", Base64.getEncoder().encodeToString(out.toByteArray()));
        return o;
    }

    private JsonElement join(JsonObject p) throws Exception {
        String rawId = str(p, "worldId", null);
        String id = rawId != null && rawId.startsWith("sp:") ? rawId.substring(3) : rawId;
        return onClient(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world != null) {
                throw new RpcException("already_in_world", "Already in a world");
            }
            List<WorldSummary> saves = new ArrayList<>(mc.getSaveLoader().getSaveList());
            saves.sort(Comparator.comparingLong(WorldSummary::getLastTimePlayed).reversed());
            WorldSummary target = null;
            for (WorldSummary s : saves) {
                if (id == null || s.getFileName().equals(id) || s.getDisplayName().equals(id)) {
                    target = s;
                    break;
                }
            }
            JsonObject o = new JsonObject();
            if (target != null) {
                mc.launchIntegratedServer(target.getFileName(), target.getDisplayName(), null);
                o.addProperty("joined", target.getFileName());
            } else {
                // Unknown id: create a flat creative test world with cheats under that name
                String name = id == null ? "mcp_test" : id;
                WorldSettings settings = new WorldSettings(1L, GameType.CREATIVE, false, false, WorldType.FLAT)
                        .setGeneratorOptions("3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;")
                        .enableCommands();
                mc.launchIntegratedServer(name, name, settings);
                o.addProperty("created", name);
            }
            o.addProperty("note", "Loading takes a few seconds; poll player.getState until it succeeds.");
            return o;
        });
    }

    // ---- events ----

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || screenshots.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        BufferedImage img = null;
        Exception failure = null;
        try {
            img = ScreenShotHelper.createScreenshot(mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        } catch (Exception e) {
            failure = e;
        }
        CompletableFuture<BufferedImage> f;
        while ((f = screenshots.poll()) != null) {
            if (img != null) {
                f.complete(img);
            } else {
                f.completeExceptionally(failure);
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTicks++;
        if (releaseJumpAt >= 0 && clientTicks >= releaseJumpAt) {
            KeyBinding.setKeyBindState(Minecraft.getMinecraft().gameSettings.keyBindJump.getKeyCode(), false);
            releaseJumpAt = -1;
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        JsonObject o = new JsonObject();
        o.addProperty("text", event.getMessage().getUnformattedText());
        o.addProperty("type", event.getType().name().toLowerCase(Locale.ROOT));
        synchronized (chat) {
            chat.addLast(o);
            while (chat.size() > 500) {
                chat.removeFirst();
            }
        }
    }

    /** Snapshot of the lights Albedo collected this frame (posted after LightManager.update, before upload). */
    @SubscribeEvent
    public void onLightUniforms(LightUniformEvent event) {
        JsonObject o = new JsonObject();
        o.addProperty("lightCount", LightManager.lights.size());
        Vec3d cam = LightManager.getCameraPos();
        o.addProperty("camera", cam.x + ", " + cam.y + ", " + cam.z);
        JsonArray nearest = new JsonArray();
        for (int i = 0; i < Math.min(12, LightManager.lights.size()); i++) {
            Light l = LightManager.lights.get(i);
            JsonObject lo = new JsonObject();
            lo.addProperty("pos", l.getPreciseX() + ", " + l.getPreciseY() + ", " + l.getPreciseZ());
            lo.addProperty("color", l.r + ", " + l.g + ", " + l.b + ", " + l.a);
            lo.addProperty("radius", l.radius());
            lo.addProperty("angle", l.angle);
            nearest.add(lo);
        }
        o.add("nearest", nearest);
        lastFrameLights = o;
    }

    // ---- params ----

    private static double num(JsonObject p, String k) throws RpcException {
        if (!p.has(k)) {
            throw new RpcException("bad_params", k + " is required");
        }
        return p.get(k).getAsDouble();
    }

    private static int intOr(JsonObject p, String k, int d) {
        return p.has(k) ? p.get(k).getAsInt() : d;
    }

    private static boolean bool(JsonObject p, String k, boolean d) {
        return p.has(k) ? p.get(k).getAsBoolean() : d;
    }

    private static String str(JsonObject p, String k, String d) {
        return p.has(k) && !p.get(k).isJsonNull() ? p.get(k).getAsString() : d;
    }

    private static BlockPos pos(JsonObject p) throws RpcException {
        return new BlockPos(MathHelper.floor(num(p, "x")), MathHelper.floor(num(p, "y")), MathHelper.floor(num(p, "z")));
    }

    private static Vec3d vec(JsonObject p) throws RpcException {
        return new Vec3d(num(p, "x"), num(p, "y"), num(p, "z"));
    }
}
