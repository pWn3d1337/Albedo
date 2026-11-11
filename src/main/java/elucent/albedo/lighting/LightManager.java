package elucent.albedo.lighting;

import elucent.albedo.Albedo;
import elucent.albedo.ConfigManager;
import elucent.albedo.EventManager;
import elucent.albedo.event.GatherLightsEvent;
import elucent.albedo.util.ShaderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LightManager {
    private static final Logger LOGGER = LogManager.getLogger("Albedo");
    /** Safety cap for lights added outside a frame (legacy addLight) so the list cannot grow without bound. */
    private static final int MAX_PENDING_LIGHTS = 4096;

    private static final String[] POSITION_UNIFORMS = new String[Light.MAX_SHADER_LIGHTS];
    private static final String[] COLOR_UNIFORMS = new String[Light.MAX_SHADER_LIGHTS];
    private static final String[] HEADING_UNIFORMS = new String[Light.MAX_SHADER_LIGHTS];
    private static final String[] ANGLE_UNIFORMS = new String[Light.MAX_SHADER_LIGHTS];

    static {
        for (int i = 0; i < Light.MAX_SHADER_LIGHTS; i++) {
            POSITION_UNIFORMS[i] = "lights[" + i + "].position";
            COLOR_UNIFORMS[i] = "lights[" + i + "].color";
            HEADING_UNIFORMS[i] = "lights[" + i + "].heading";
            ANGLE_UNIFORMS[i] = "lights[" + i + "].angle";
        }
    }

    /** Interpolated position of the render view entity this frame. Shader positions are relative to it. */
    public static Vec3d cameraPos;
    public static ICamera camera;
    public static ArrayList<Light> lights = new ArrayList<>();
    public static DistComparator distComparator = new DistComparator();

    private static final Set<Class<?>> failedProviders = Collections.synchronizedSet(new HashSet<>());

    /**
     * The Albedo 0.1.x API, still used by some mods (e.g. WeissAlbedo). Without it those mods crash with
     * {@code NoSuchMethodError: LightManager.addLight}. The light is rendered from the next frame on.
     *
     * @deprecated use {@link GatherLightsEvent#add(Light)} or an {@link ILightProvider}
     */
    @Deprecated
    public static void addLight(Light l) {
        if (l != null && ConfigManager.isLightingEnabled() && lights.size() < MAX_PENDING_LIGHTS) {
            lights.add(l);
        }
    }

    /** Camera position for this frame, never null. */
    public static Vec3d getCameraPos() {
        return cameraPos != null ? cameraPos : Vec3d.ZERO;
    }

    public static void uploadLights() {
        ShaderManager shader = ShaderManager.getCurrentShader();
        if (shader == null) {
            return;
        }
        // The shader array holds 100 lights; uploading a larger count makes the shader read past it
        int count = Math.min(ConfigManager.getMaxLights(), lights.size());
        shader.setUniform("lightCount", count);
        Vec3d cam = getCameraPos();
        for (int i = 0; i < count; i++) {
            Light l = lights.get(i);
            shader.setUniform(POSITION_UNIFORMS[i], (float) (l.getPreciseX() - cam.x), (float) (l.getPreciseY() - cam.y), (float) (l.getPreciseZ() - cam.z));
            shader.setUniform(COLOR_UNIFORMS[i], l.r, l.g, l.b, l.a);
            shader.setUniform(HEADING_UNIFORMS[i], l.rx, l.ry, l.rz);
            shader.setUniform(ANGLE_UNIFORMS[i], l.angle);
        }
    }

    private static Vec3d interpolate(Entity entity, float partialTicks) {
        return new Vec3d(
                entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks,
                entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks,
                entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks);
    }

    public static void update(World world) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity cameraEntity = mc.getRenderViewEntity();
        if (world == null || cameraEntity == null) {
            if (cameraPos == null) {
                cameraPos = Vec3d.ZERO;
            }
            camera = null;
            return;
        }
        cameraPos = interpolate(cameraEntity, mc.getRenderPartialTicks());
        camera = new Frustum();
        camera.setPosition(cameraPos.x, cameraPos.y, cameraPos.z);
        GatherLightsEvent event = new GatherLightsEvent(lights, (float) ConfigManager.maxDistance, cameraPos, camera);
        MinecraftForge.EVENT_BUS.post(event);

        // Lights of registered block handlers, collected by the block scan; culled here against this frame's camera
        synchronized (EventManager.EXISTING) {
            for (List<Light> blockLights : EventManager.EXISTING.values()) {
                for (Light light : blockLights) {
                    event.add(light);
                }
            }
        }

        int maxDist = ConfigManager.maxDistance;
        AxisAlignedBB box = new AxisAlignedBB(cameraPos.x - maxDist, cameraPos.y - maxDist, cameraPos.z - maxDist,
                cameraPos.x + maxDist, cameraPos.y + maxDist, cameraPos.z + maxDist);
        for (Entity e : world.getEntitiesWithinAABB(Entity.class, box)) {
            try {
                gatherEntityLights(event, e);
            } catch (RuntimeException ex) {
                reportFailure(e.getClass(), ex);
            }
        }

        for (TileEntity t : world.loadedTileEntityList) {
            try {
                if (t instanceof ILightProvider) {
                    event.add(((ILightProvider) t).provideLight());
                }
                if (t.hasCapability(Albedo.LIGHT_PROVIDER_CAPABILITY, null)) {
                    ILightProvider provider = t.getCapability(Albedo.LIGHT_PROVIDER_CAPABILITY, null);
                    if (provider != null) {
                        provider.gatherLights(event, null);
                    }
                }
            } catch (RuntimeException ex) {
                reportFailure(t.getClass(), ex);
            }
        }

        // Lights added through the legacy API or straight into the list skipped GatherLightsEvent's checks
        lights.removeIf(l -> l == null || !l.isValid());
        lights.sort(distComparator);
    }

    private static void gatherEntityLights(GatherLightsEvent event, Entity e) {
        if (e instanceof ILightProvider) {
            event.add(((ILightProvider) e).provideLight());
        }
        if (e instanceof EntityItem) {
            gatherStackLights(event, ((EntityItem) e).getItem(), e);
        }
        if (e.hasCapability(Albedo.LIGHT_PROVIDER_CAPABILITY, null)) {
            ILightProvider provider = e.getCapability(Albedo.LIGHT_PROVIDER_CAPABILITY, null);
            if (provider != null) {
                provider.gatherLights(event, e);
            }
        }
        // Held and worn items light up for every entity. In 1.1.0 this only ran for entities that had the
        // capability themselves, so a player holding a light-providing item stayed dark (the 1.13 code has it right).
        for (ItemStack itemStack : e.getHeldEquipment()) {
            gatherStackLights(event, itemStack, e);
        }
        for (ItemStack itemStack : e.getArmorInventoryList()) {
            gatherStackLights(event, itemStack, e);
        }
    }

    private static void gatherStackLights(GatherLightsEvent event, ItemStack stack, Entity holder) {
        if (!stack.isEmpty() && stack.hasCapability(Albedo.LIGHT_PROVIDER_CAPABILITY, null)) {
            ILightProvider provider = stack.getCapability(Albedo.LIGHT_PROVIDER_CAPABILITY, null);
            if (provider != null) {
                provider.gatherLights(event, holder);
            }
        }
    }

    /** Logs a broken light provider once instead of crashing the game every frame. */
    public static void reportFailure(Class<?> source, Throwable t) {
        if (failedProviders.add(source)) {
            LOGGER.error("Light provider {} threw an exception; its lights are skipped", source.getName(), t);
        }
    }

    public static void clear() {
        lights.clear();
    }

    public static class DistComparator implements Comparator<Light> {
        @Override
        public int compare(Light a, Light b) {
            Vec3d cam = getCameraPos();
            double dist1 = cam.squareDistanceTo(a.getPreciseX(), a.getPreciseY(), a.getPreciseZ());
            double dist2 = cam.squareDistanceTo(b.getPreciseX(), b.getPreciseY(), b.getPreciseZ());
            return Double.compare(dist1, dist2);
        }
    }
}
