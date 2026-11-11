package elucent.albedo;

import com.google.common.collect.ImmutableMap;
import elucent.albedo.event.GatherLightsEvent;
import elucent.albedo.lighting.DefaultLightProvider;
import elucent.albedo.lighting.ILightProvider;
import elucent.albedo.util.ShaderUtil;
import elucent.albedo.util.TriConsumer;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.Capability.IStorage;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

@Mod(
        modid = Albedo.MODID,
        name = Albedo.NAME,
        useMetadata = true, // version comes from mcmod.info, filled in by the build
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.12.2]"
)
public class Albedo {
    public static final String MODID = "albedo";
    public static final String NAME = "Albedo";

    private static final Map<Block, TriConsumer<BlockPos, IBlockState, GatherLightsEvent>> MAP = new IdentityHashMap<>();

    @CapabilityInject(ILightProvider.class)
    public static Capability<ILightProvider> LIGHT_PROVIDER_CAPABILITY;

    public static TriConsumer<BlockPos, IBlockState, GatherLightsEvent> getLightHandler(Block block) {
        return MAP.get(block);
    }

    /**
     * Registers a light handler for a block without a tile entity. Blocks around the player are scanned a few
     * sections per tick and the handler is called for every block of this type to add its lights.
     * Call it during mod loading (client side), not from render code.
     */
    public static void registerBlockHandler(Block block, TriConsumer<BlockPos, IBlockState, GatherLightsEvent> triConsumer) {
        if (block != null && triConsumer != null) {
            MAP.put(block, triConsumer);
        }
    }

    public static ImmutableMap<Block, TriConsumer<BlockPos, IBlockState, GatherLightsEvent>> getBlockHandlers() {
        return ImmutableMap.copyOf(MAP);
    }

    /** True if any block light handlers are registered (the block scan is skipped otherwise). */
    public static boolean hasBlockHandlers() {
        return !MAP.isEmpty();
    }

    @EventHandler
    public void preinit(FMLPreInitializationEvent event) {
        CapabilityManager.INSTANCE.register(ILightProvider.class, new IStorage<ILightProvider>() {
            @Nullable
            @Override
            public NBTBase writeNBT(Capability<ILightProvider> capability, ILightProvider instance, EnumFacing side) {
                return null;
            }

            @Override
            public void readNBT(Capability<ILightProvider> capability, ILightProvider instance, EnumFacing side, NBTBase nbt) {
            }
        }, DefaultLightProvider::new);
        MinecraftForge.EVENT_BUS.register(new ConfigManager.ChangeHandler());
    }

    @EventHandler
    public void loadComplete(FMLPostInitializationEvent event) {
        // Registering a reload listener runs it once right away, which compiles the shaders
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager()).registerReloadListener(new ShaderUtil());
        MinecraftForge.EVENT_BUS.register(new EventManager());
        if (Boolean.getBoolean("albedo.debugLights")) {
            DebugLights.register();
        }
        if (Boolean.getBoolean("albedo.autoTest")) {
            AutoTest.register();
        }
    }
}
