package elucent.albedo;

import elucent.albedo.event.LightUniformEvent;
import elucent.albedo.event.ProfilerStartEvent;
import elucent.albedo.event.RenderChunkUniformsEvent;
import elucent.albedo.event.RenderEntityEvent;
import elucent.albedo.event.RenderTileEntityEvent;
import elucent.albedo.lighting.BlockLightScanner;
import elucent.albedo.lighting.Light;
import elucent.albedo.lighting.LightManager;
import elucent.albedo.util.RenderUtil;
import elucent.albedo.util.ShaderManager;
import elucent.albedo.util.ShaderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityEndGateway;
import net.minecraft.tileentity.TileEntityEndPortal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent;
import org.lwjgl.opengl.GL11;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Switches Albedo's shaders on and off following the vanilla profiler sections of a frame, and feeds them their
 * uniforms. Positions sent to the shaders are relative to {@link LightManager#getCameraPos()}: absolute float
 * coordinates lose precision far from the world origin (lights snapped to a grid beyond ~8 million blocks).
 */
public class EventManager {
    /** Lights of registered block handlers, by block position. Written by the block scan on the client thread. */
    public static final Map<BlockPos, List<Light>> EXISTING = Collections.synchronizedMap(new HashMap<>());
    public static boolean isGui = false;

    int ticks = 0;
    boolean postedLights = false;
    boolean precedesEntities = true;
    String section = "";
    private final BlockLightScanner blockScanner = new BlockLightScanner();

    /** ProfilerStartEvent is only posted for the client thread, see ProfilerStartEvent.postNewEvent. */
    @SubscribeEvent
    public void onProfilerChange(ProfilerStartEvent event) {
        section = event.getSection();
        if (!ConfigManager.isLightingEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        switch (section) {
            case "terrain":
                if (mc.world == null || mc.getRenderViewEntity() == null) {
                    return;
                }
                isGui = false;
                precedesEntities = true;
                if (!postedLights) {
                    // Collect the lights and fix the camera position for this frame first, everything below
                    // is relative to it
                    LightManager.update(mc.world);
                    ShaderManager.stopShader();
                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new LightUniformEvent());

                    beginLightProgram(ShaderUtil.entityLightProgram);
                    LightManager.uploadLights();
                    ShaderUtil.entityLightProgram.setUniform("lightingEnabled", GL11.glIsEnabled(GL11.GL_LIGHTING));

                    beginLightProgram(ShaderUtil.fastLightProgram);
                    LightManager.uploadLights();
                    postedLights = true;
                    LightManager.clear();
                } else {
                    beginLightProgram(ShaderUtil.fastLightProgram);
                }
                break;
            case "litParticles":
                beginLightProgram(ShaderUtil.fastLightProgram);
                // Particle vertices are already relative to the camera
                ShaderUtil.fastLightProgram.setUniform("chunkOffset", 0f, 0f, 0f);
                break;
            case "translucent":
                beginLightProgram(ShaderUtil.fastLightProgram);
                break;
            case "entities":
            case "blockEntities":
                beginLightProgram(ShaderUtil.entityLightProgram);
                // the shader applies the fixed-function item/mob shading itself while GL_LIGHTING is on
                ShaderUtil.entityLightProgram.setUniform("lightingEnabled", GL11.glIsEnabled(GL11.GL_LIGHTING));
                RenderUtil.clearEntityOverlay();
                break;
            case "hand":
                beginLightProgram(ShaderUtil.entityLightProgram);
                // The hand is at the eye. 1.1.0 set this on the terrain program by mistake, so the hand was lit as
                // if it were at the last rendered entity.
                Entity viewer = mc.getRenderViewEntity();
                ShaderUtil.entityLightProgram.setUniform("entityPos", 0f, viewer != null ? viewer.getEyeHeight() : 0f, 0f);
                ShaderUtil.entityLightProgram.setUniform("lightingEnabled", GL11.glIsEnabled(GL11.GL_LIGHTING));
                precedesEntities = true;
                break;
            case "gui":
                isGui = true;
                ShaderManager.stopShader();
                break;
            case "sky":
            case "particles":
            case "weather":
            case "outline":
            case "aboveClouds":
            case "destroyProgress":
                ShaderManager.stopShader();
                break;
            default:
                break;
        }
    }

    /** Binds an Albedo program and sets the uniforms every pass needs. */
    private void beginLightProgram(ShaderManager program) {
        program.useShader();
        if (!ShaderManager.isCurrentShader(program)) {
            return; // suspended while another mod's shader is bound
        }
        Minecraft mc = Minecraft.getMinecraft();
        program.setUniform("ticks", ticks + mc.getRenderPartialTicks());
        program.setUniform("sampler", 0);
        program.setUniform("lightmap", 1);
        Entity viewer = mc.getRenderViewEntity();
        program.setUniform("playerPos", 0f, viewer != null ? viewer.getEyeHeight() : 0f, 0f);
        RenderUtil.setFogAndOcclusionUniforms(program);
    }

    @SubscribeEvent
    public void onRenderEntity(RenderEntityEvent event) {
        if (!ConfigManager.isLightingEnabled()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof EntityLightningBolt) {
            ShaderManager.stopShader();
        } else if (section.equals("entities") || section.equals("blockEntities")) {
            ShaderUtil.entityLightProgram.useShader();
        }
        if (ShaderManager.isCurrentShader(ShaderUtil.entityLightProgram)) {
            // a renderer that tinted the previous entity without unsetBrightness() must not tint this one
            RenderUtil.clearEntityOverlay();
            Vec3d cam = LightManager.getCameraPos();
            float pt = Minecraft.getMinecraft().getRenderPartialTicks();
            double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * pt;
            double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * pt;
            double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * pt;
            ShaderUtil.entityLightProgram.setUniform("entityPos", (float) (x - cam.x), (float) (y - cam.y) + entity.height / 2.0F, (float) (z - cam.z));
        }
    }

    @SubscribeEvent
    public void onRenderTileEntity(RenderTileEntityEvent event) {
        if (!ConfigManager.isLightingEnabled()) {
            return;
        }
        TileEntity tile = event.getEntity();
        if (tile instanceof TileEntityEndPortal || tile instanceof TileEntityEndGateway) {
            ShaderManager.stopShader();
        } else if (section.equals("entities") || section.equals("blockEntities")) {
            ShaderUtil.entityLightProgram.useShader();
        }
        if (ShaderManager.isCurrentShader(ShaderUtil.entityLightProgram)) {
            Vec3d cam = LightManager.getCameraPos();
            BlockPos pos = tile.getPos();
            ShaderUtil.entityLightProgram.setUniform("entityPos", (float) (pos.getX() - cam.x), (float) (pos.getY() - cam.y), (float) (pos.getZ() - cam.z));
        }
    }

    @SubscribeEvent
    public void onRenderChunk(RenderChunkUniformsEvent event) {
        if (ConfigManager.isLightingEnabled() && ShaderManager.isCurrentShader(ShaderUtil.fastLightProgram)) {
            Vec3d cam = LightManager.getCameraPos();
            BlockPos pos = event.getChunk().getPosition();
            ShaderUtil.fastLightProgram.setUniform("chunkOffset", (float) (pos.getX() - cam.x), (float) (pos.getY() - cam.y), (float) (pos.getZ() - cam.z));
        }
    }

    @SubscribeEvent
    public void clientTick(ClientTickEvent event) {
        if (event.phase == Phase.START) {
            ticks++;
        } else {
            Minecraft mc = Minecraft.getMinecraft();
            blockScanner.tick(mc.world, mc.getRenderViewEntity());
        }
    }

    @SubscribeEvent
    public void onRenderTick(RenderTickEvent event) {
        if (event.phase == Phase.START) {
            ShaderManager.resetExternalProgram();
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        postedLights = false;
        RenderUtil.resetLightingState();
        if (ConfigManager.isLightingEnabled()) {
            GlStateManager.disableLighting();
            RenderUtil.resetLightingState();
            ShaderManager.stopShader();
        }
    }
}
