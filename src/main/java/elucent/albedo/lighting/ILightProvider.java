package elucent.albedo.lighting;

import elucent.albedo.event.GatherLightsEvent;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Provides lights. Expose it as the {@code Albedo.LIGHT_PROVIDER_CAPABILITY} capability on tile entities, entities
 * or item stacks, or implement it directly on a tile entity or entity.
 */
public interface ILightProvider {
    /**
     * Adds this provider's lights with {@link GatherLightsEvent#add(Light)}.
     *
     * @param event   the event collecting this frame's lights
     * @param context the entity holding or being the provider; null for tile entities
     */
    default void gatherLights(GatherLightsEvent event, Entity context) {
        // Providers written for Albedo 0.1.x only implement provideLight()
        Light light = provideLight();
        if (light != null) {
            event.add(light);
        }
    }

    /**
     * The Albedo 0.1.x API: one light, or null.
     *
     * @deprecated implement {@link #gatherLights(GatherLightsEvent, Entity)} instead
     */
    @SideOnly(Side.CLIENT)
    @Deprecated
    default Light provideLight() {
        return null;
    }
}
