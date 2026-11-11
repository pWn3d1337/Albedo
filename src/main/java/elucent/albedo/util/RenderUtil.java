package elucent.albedo.util;

import elucent.albedo.ConfigManager;
import elucent.albedo.EventManager;
import elucent.albedo.event.RenderChunkUniformsEvent;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.FloatBuffer;

public class RenderUtil {
    public static boolean lightingEnabled = false;
    public static ShaderManager previousShader;
    public static boolean enabledLast = false;
    public static TransformType itemTransformType = TransformType.NONE;

    /** Called by the coremod from ChunkRenderContainer.preRenderChunk. */
    public static void renderChunkUniforms(RenderChunk c) {
        MinecraftForge.EVENT_BUS.post(new RenderChunkUniformsEvent(c));
    }

    /** Called by the coremod from GlStateManager.enableLighting. */
    public static void enableLightingUniforms() {
        if (!EventManager.isGui && ConfigManager.isLightingEnabled()) {
            if (enabledLast) {
                if (previousShader != null) {
                    previousShader.useShader();
                }
                enabledLast = false;
                previousShader = null;
            }
            if (ShaderManager.isCurrentShader(ShaderUtil.entityLightProgram)) {
                ShaderUtil.entityLightProgram.setUniform("lightingEnabled", true);
            }
        }
    }

    /** Called by the coremod from GlStateManager.disableLighting. */
    public static void disableLightingUniforms() {
        if (!EventManager.isGui && ConfigManager.isLightingEnabled()) {
            if (ShaderManager.isCurrentShader(ShaderUtil.entityLightProgram)) {
                ShaderUtil.entityLightProgram.setUniform("lightingEnabled", false);
            }
            if (!enabledLast) {
                previousShader = ShaderManager.getCurrentShader();
                enabledLast = true;
                ShaderManager.stopShader();
            }
        }
    }

    /**
     * Forgets a shader that disableLightingUniforms() stopped but no enableLighting() restored. Without this, the
     * next frame's first enableLighting() would bind that shader in an unrelated render pass (e.g. the sky).
     */
    public static void resetLightingState() {
        enabledLast = false;
        previousShader = null;
    }

    /**
     * Called by the coremod from GlStateManager.glTexEnv. RenderLivingBase.setBrightness tints hurt mobs red (and
     * flashing creepers white) with a texture combiner on the lightmap unit: colour = mix(colour, envColor.rgb,
     * envColor.a). Shaders bypass texture combiners, so the entity shader gets the colour as a uniform instead.
     */
    public static void onTexEnv(int target, int pname, FloatBuffer params) {
        if (target != GL11.GL_TEXTURE_ENV || pname != GL11.GL_TEXTURE_ENV_COLOR || params == null || params.remaining() < 4
                || !ShaderManager.isCurrentShader(ShaderUtil.entityLightProgram)) {
            return;
        }
        // GlStateManager.enableOutlineMode sets the colour of texture unit 0; only the lightmap unit's is the overlay
        if (GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != OpenGlHelper.lightmapTexUnit) {
            return;
        }
        int p = params.position();
        ShaderUtil.entityLightProgram.setUniform("overlayColor", params.get(p), params.get(p + 1), params.get(p + 2), params.get(p + 3));
    }

    /** Called by the coremod from RenderLivingBase.unsetBrightness, and before each entity is rendered. */
    public static void clearEntityOverlay() {
        if (ShaderManager.isCurrentShader(ShaderUtil.entityLightProgram)) {
            ShaderUtil.entityLightProgram.setUniform("overlayColor", 0f, 0f, 0f, 0f);
        }
    }

    /** Called by the coremod from ForgeHooksClient.handleCameraTransforms. */
    public static void setTransform(TransformType t) {
        itemTransformType = t;
    }

    /**
     * Uploads the fog mode (read from GL, since GlStateManager keeps it private) and the occlusion setting to the
     * current Albedo shader, so its fog matches the fixed-function fog of the pass (linear, or exponential under
     * water and in lava).
     */
    public static void setFogAndOcclusionUniforms(ShaderManager shader) {
        if (shader == null || !ShaderManager.isCurrentShader(shader)) {
            return;
        }
        int fogMode = GL11.glIsEnabled(GL11.GL_FOG) ? GL11.glGetInteger(GL11.GL_FOG_MODE) : 0;
        shader.setUniform("fogMode", fogMode);
        shader.setUniform("blockLightOcclusion", ConfigManager.blockLightOcclusion ? 1 : 0);
    }
}
