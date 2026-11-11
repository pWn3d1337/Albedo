package elucent.albedo;

import elucent.albedo.event.GatherLightsEvent;
import elucent.albedo.lighting.Light;
import elucent.albedo.lighting.LightManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Test lights for the dev client, enabled with {@code -Dalbedo.debugLights=true} (set by {@code runClient}).
 * Torches glow orange, lit redstone lamps red, sea lanterns blue and glowstone yellow (block handlers), a player
 * holding a blaze rod casts a cone light (GatherLightsEvent), and the 0.1.x bridges are exercised.
 */
final class DebugLights {
    private static final Logger LOGGER = LogManager.getLogger("Albedo");

    private DebugLights() {
    }

    static void register() {
        Albedo.registerBlockHandler(Blocks.TORCH, (pos, state, event) ->
                event.add(Light.builder().pos(pos).color(1.0f, 0.55f, 0.15f).radius(10).build()));
        Albedo.registerBlockHandler(Blocks.LIT_REDSTONE_LAMP, (pos, state, event) ->
                event.add(Light.builder().pos(pos).color(1.0f, 0.1f, 0.1f).radius(12).build()));
        Albedo.registerBlockHandler(Blocks.SEA_LANTERN, (pos, state, event) ->
                event.add(Light.builder().pos(pos).color(0.2f, 0.4f, 1.0f).radius(12).build()));
        // 0.1.x-style light (angle left to the 8-argument constructor) must be visible too
        Albedo.registerBlockHandler(Blocks.GLOWSTONE, (pos, state, event) ->
                event.add(new Light(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f, 1.0f, 0.9f, 0.3f, 1.0f, 10)));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new DebugLights.Handler());
        checkLegacyBridge();
        LOGGER.info("Albedo debug lights registered");
    }

    /** The coremod must have renamed the 0.1.x bridge so both getLightList() descriptors exist. */
    private static void checkLegacyBridge() {
        int found = 0;
        for (Method m : GatherLightsEvent.class.getMethods()) {
            if (m.getName().equals("getLightList")) {
                found++;
                LOGGER.info("GatherLightsEvent.getLightList() -> {}", m.getReturnType().getName());
            }
        }
        if (found != 2) {
            LOGGER.error("Legacy GatherLightsEvent.getLightList bridge missing ({} methods found)", found);
        }
    }

    public static final class Handler {
        private boolean legacyCalled;

        @SubscribeEvent
        @SuppressWarnings("unchecked")
        public void onGatherLights(GatherLightsEvent event) {
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player != null && player.getHeldItemMainhand().getItem() == Items.BLAZE_ROD) {
                event.add(Light.builder().pos(player.getPositionEyes(1.0f))
                        .color(1.0f, 0.3f, 1.0f)
                        .direction(player.getLookVec().scale(16), (float) (Math.PI / 6))
                        .build());
            }
            if (player != null && player.getHeldItemOffhand().getItem() == Items.BLAZE_ROD) {
                // 0.1.x API: LightManager.addLight and the mutable getLightList()
                LightManager.addLight(Light.builder().pos(player).color(0.2f, 1.0f, 0.2f).radius(8).build());
                if (!legacyCalled) {
                    legacyCalled = true;
                    try {
                        ArrayList<Light> list = (ArrayList<Light>) GatherLightsEvent.class.getMethod("albedo$legacyGetLightList").invoke(event);
                        LOGGER.info("Legacy bridge not renamed (dev without coremod?), list size {}", list.size());
                    } catch (NoSuchMethodException e) {
                        LOGGER.info("Legacy bridge renamed by the coremod");
                    } catch (ReflectiveOperationException e) {
                        LOGGER.error("Legacy bridge check failed", e);
                    }
                }
            }
        }
    }
}
