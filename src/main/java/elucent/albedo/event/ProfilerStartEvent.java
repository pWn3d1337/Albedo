package elucent.albedo.event;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;

/** Posted on the client thread whenever the vanilla profiler starts a new section (coremod hook). */
public class ProfilerStartEvent extends Event {
    private final String section;

    private ProfilerStartEvent(String section) {
        this.section = section;
    }

    public static void postNewEvent(String section) {
        // The integrated server has its own Profiler (same class, same hook) with sections such as "entities".
        // Its events used to reach the render code from the server thread, where GL calls and the shared
        // render state are not safe.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread()) {
            return;
        }
        MinecraftForge.EVENT_BUS.post(new ProfilerStartEvent(section));
    }

    public String getSection() {
        return section;
    }

    @Override
    public boolean isCancelable() {
        return false;
    }
}
