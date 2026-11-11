package elucent.albedo.event;

import com.google.common.collect.ImmutableList;
import elucent.albedo.lighting.Light;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.ArrayList;

/**
 * Posted once per frame (and once per light-handler block, see {@code Albedo.registerBlockHandler}) to collect the
 * lights to render. Add lights with {@link #add(Light)}, which culls lights that are too far away or off screen.
 */
public class GatherLightsEvent extends Event {
    private final ArrayList<Light> lights;
    private final float maxDistance;
    private final Vec3d cameraPosition;
    private final ICamera camera;

    public GatherLightsEvent(ArrayList<Light> lights, float maxDistance, Vec3d cameraPosition, ICamera camera) {
        this.lights = lights;
        this.maxDistance = maxDistance;
        this.cameraPosition = cameraPosition;
        this.camera = camera;
    }

    /**
     * The Albedo 0.1.x constructor: no culling.
     *
     * @deprecated use {@link #GatherLightsEvent(ArrayList, float, Vec3d, ICamera)}
     */
    @Deprecated
    public GatherLightsEvent(ArrayList<Light> lights) {
        this(lights, Float.POSITIVE_INFINITY, null, null);
    }

    public ImmutableList<Light> getLightList() {
        return ImmutableList.copyOf(lights);
    }

    /**
     * Bridge for mods built against Albedo 0.1.x, where {@code getLightList()} returned the mutable list.
     * The coremod renames this method to {@code getLightList} with the {@code ()Ljava/util/ArrayList;} descriptor
     * (a return-type overload is legal bytecode but not legal Java), so old callers link instead of crashing with
     * NoSuchMethodError. Do not call it directly.
     */
    @Deprecated
    public ArrayList<Light> albedo$legacyGetLightList() {
        return lights;
    }

    public float getMaxDistance() {
        return maxDistance;
    }

    public Vec3d getCameraPosition() {
        return cameraPosition;
    }

    public ICamera getCamera() {
        return camera;
    }

    /** Adds a light unless it is null, invalid, too far from the camera or outside the view frustum. */
    public void add(Light light) {
        if (light == null || !light.isValid()) {
            return;
        }
        float radius = light.radius();
        double x = light.getPreciseX();
        double y = light.getPreciseY();
        double z = light.getPreciseZ();
        if (cameraPosition != null) {
            double dist = Math.sqrt(cameraPosition.squareDistanceTo(x, y, z));
            if (dist > radius + maxDistance) {
                return;
            }
        }
        if (camera == null || camera.isBoundingBoxInFrustum(new AxisAlignedBB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius))) {
            lights.add(light);
        }
    }

    @Override
    public boolean isCancelable() {
        return false;
    }
}
