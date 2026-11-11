package elucent.albedo.event;

import net.minecraftforge.fml.common.eventhandler.Event;

public class LightUniformEvent extends Event {
   @Override
   public boolean isCancelable() {
      return false;
   }
}
