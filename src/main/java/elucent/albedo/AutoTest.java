package elucent.albedo;

import elucent.albedo.lighting.LightManager;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Unattended dev-client smoke test, enabled with {@code -Dalbedo.autoTest=true} ({@code ./gradlew runClient
 * -PalbedoAutoTest}). Creates a flat creative world, builds a scene with the debug lights (see DebugLights), and
 * saves screenshots to {@code run/screenshots}: the overworld scene at night, the Nether with zombie pigmen 6 and
 * 20 blocks away (entity fog), and the scene again 20 million blocks from the origin (float precision). Then it
 * quits the game.
 */
final class AutoTest {
    private static final Logger LOGGER = LogManager.getLogger("Albedo");
    private static final String WORLD = "albedo_autotest";
    private static final long FAR = 20_000_000L;

    private boolean launched;
    private int ticks = -1;
    private int stage;
    private String screenshotName;

    static void register() {
        MinecraftForge.EVENT_BUS.register(new AutoTest());
        LOGGER.info("Albedo auto test armed");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!launched) {
            if (mc.currentScreen instanceof GuiMainMenu) {
                launched = true;
                mc.getSaveLoader().deleteWorldDirectory(WORLD);
                WorldSettings settings = new WorldSettings(1L, GameType.CREATIVE, false, false, WorldType.FLAT)
                        .setGeneratorOptions("3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;")
                        .enableCommands();
                LOGGER.info("Albedo auto test: creating world");
                mc.launchIntegratedServer(WORLD, WORLD, settings);
            }
            return;
        }
        if (mc.player == null || mc.world == null || mc.getIntegratedServer() == null) {
            return;
        }
        ticks++;
        MinecraftServer server = mc.getIntegratedServer();
        switch (stage) {
            case 0:
                if (ticks == 20) {
                    mc.gameSettings.hideGUI = true;
                    server.addScheduledTask(() -> buildScene(server, 0, 0, true));
                }
                shootPair("albedo_overworld", 140);
                break;
            case 1:
                if (ticks == 1) {
                    server.addScheduledTask(() -> nether(server));
                }
                shootPair("albedo_nether_fog", 160);
                break;
            case 2:
                if (ticks == 1) {
                    server.addScheduledTask(() -> backToOverworldFar(server));
                }
                if (ticks == 60) {
                    server.addScheduledTask(() -> buildScene(server, FAR, FAR, false));
                }
                shootPair("albedo_far_" + FAR, 200);
                break;
            case 3:
                if (ticks == 20) {
                    LOGGER.info("Albedo auto test finished");
                    mc.shutdown();
                }
                break;
            default:
                break;
        }
    }

    /** Screenshot with Albedo at tick t, then the same view with Albedo off (vanilla baseline), then next stage. */
    private void shootPair(String name, int t) {
        if (ticks == t) {
            shoot(name);
        } else if (ticks == t + 5) {
            ConfigManager.disableLights = true;
        } else if (ticks == t + 10) {
            shoot(name + "_vanilla");
        } else if (ticks == t + 15) {
            ConfigManager.disableLights = false;
            stage++;
            ticks = 0;
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END && screenshotName != null) {
            Minecraft mc = Minecraft.getMinecraft();
            LOGGER.info("Albedo auto test: screenshot {} (block lights: {}, camera: {})", screenshotName, EventManager.EXISTING.size(), LightManager.cameraPos);
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, screenshotName + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            screenshotName = null;
        }
    }

    private void shoot(String name) {
        screenshotName = name;
    }

    private static EntityPlayerMP player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    /** Lights on the ground in front of the player (looking south, +Z), at night. */
    private static void buildScene(MinecraftServer server, long ox, long oz, boolean setRules) {
        WorldServer world = server.getWorld(0);
        if (setRules) {
            world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
            world.getGameRules().setOrCreateGameRule("doMobSpawning", "false");
        }
        world.setWorldTime(18000);
        int y = 4;
        BlockPos o = new BlockPos(ox, y, oz);
        place(world, o.add(-4, 0, 6), Blocks.TORCH.getDefaultState());
        place(world, o.add(4, 0, 6), Blocks.TORCH.getDefaultState());
        place(world, o.add(-3, 0, 11), Blocks.SEA_LANTERN.getDefaultState());
        place(world, o.add(3, 0, 11), Blocks.LIT_REDSTONE_LAMP.getDefaultState());
        place(world, o.add(0, 0, 15), Blocks.GLOWSTONE.getDefaultState());
        // A sea lantern walled in by stone must not light the area around the cube
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    place(world, o.add(9 + dx, dy, 10 + dz), Blocks.STONE.getDefaultState());
                }
            }
        }
        place(world, o.add(9, 1, 10), Blocks.SEA_LANTERN.getDefaultState());

        EntityPig pig = new EntityPig(world);
        pig.setLocationAndAngles(ox + 0.5, y, oz + 8.5, 180, 0);
        pig.setNoAI(true);
        world.spawnEntity(pig);

        EntityPlayerMP player = player(server);
        player.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.BLAZE_ROD));
        player.connection.setPlayerLocation(ox + 0.5, y + 1, oz - 2.5, 0, 25);
    }

    /** A Nether platform with zombie pigmen 6 and 20 blocks in front of the player. */
    private static void nether(MinecraftServer server) {
        EntityPlayerMP player = player(server);
        player.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        player.changeDimension(-1, (world, entity, yaw) -> {
            for (int x = -4; x <= 4; x++) {
                for (int z = -3; z <= 26; z++) {
                    world.setBlockState(new BlockPos(x, 99, z), Blocks.OBSIDIAN.getDefaultState(), 2);
                    for (int y = 100; y <= 104; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 2);
                    }
                }
            }
            entity.setLocationAndAngles(0.5, 100, -1.5, 0, 0);
            for (int z : new int[]{6, 20}) {
                EntityPigZombie pigman = new EntityPigZombie(world);
                pigman.setLocationAndAngles(z == 6 ? -1.5 : 2.5, 100, z + 0.5, 180, 0);
                pigman.setNoAI(true);
                world.spawnEntity(pigman);
            }
        });
    }

    private static void backToOverworldFar(MinecraftServer server) {
        EntityPlayerMP player = player(server);
        player.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.BLAZE_ROD));
        player.changeDimension(0, (world, entity, yaw) -> entity.setLocationAndAngles(FAR + 0.5, 5, FAR - 2.5, 0, 25));
    }

    private static void place(WorldServer world, BlockPos pos, IBlockState state) {
        world.setBlockState(pos, state, 2);
    }
}
