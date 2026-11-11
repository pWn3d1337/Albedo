package elucent.albedo;

import elucent.albedo.lighting.Light;
import elucent.albedo.util.ShaderUtil;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.Config.Comment;
import net.minecraftforge.common.config.Config.LangKey;
import net.minecraftforge.common.config.Config.RangeInt;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = Albedo.MODID, name = "Albedo")
public class ConfigManager {
    @RangeInt(min = 0, max = Light.MAX_SHADER_LIGHTS)
    @Comment({"The maximum number of lights allowed to render in a scene. Lights are sorted nearest-first, so further-away lights will be culled after nearer lights. The shaders hold at most 100."})
    @LangKey("albedo.config.maxLights")
    public static int maxLights = 40;

    @RangeInt(min = 16, max = 256)
    @Comment({"The maximum distance lights can be before being culled."})
    @LangKey("albedo.config.maxDistance")
    public static int maxDistance = 64;

    @Comment({"Disables albedo lighting."})
    @LangKey("albedo.config.disableLights")
    public static boolean disableLights = false;

    @Comment({"Fades coloured light out where vanilla block light is 0, so solid walls block it.",
            "Lights without a vanilla light level (for example held items without a dynamic lights mod) are hidden as well."})
    @LangKey("albedo.config.blockLightOcclusion")
    public static boolean blockLightOcclusion = false;

    @Comment({"Holiday Events"})
    @LangKey("albedo.config.holiday")
    public static boolean holiday = true;

    public static boolean isLightingEnabled() {
        return !disableLights && ShaderUtil.isReady();
    }

    /** Lights the shaders can actually render this frame. */
    public static int getMaxLights() {
        return Math.max(0, Math.min(maxLights, Light.MAX_SHADER_LIGHTS));
    }

    /** Saves and applies changes made in the in-game config GUI (Forge does not do that by itself). */
    public static class ChangeHandler {
        @SubscribeEvent
        public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (Albedo.MODID.equals(event.getModID())) {
                net.minecraftforge.common.config.ConfigManager.sync(Albedo.MODID, Config.Type.INSTANCE);
            }
        }
    }
}
