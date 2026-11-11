package elucent.albedo.event;

import net.minecraft.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;

public class RenderEntityEvent extends Event {
   private final Entity e;

   private RenderEntityEvent(Entity e) {
      this.e = e;
   }

   public static void postNewEvent(Entity e) {
      MinecraftForge.EVENT_BUS.post(new RenderEntityEvent(e));
   }

   public Entity getEntity() {
      return this.e;
   }

   @Override
   public boolean isCancelable() {
      return false;
   }
}
