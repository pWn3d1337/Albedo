package elucent.albedo.event;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraftforge.fml.common.eventhandler.Event;

public class RenderChunkUniformsEvent extends Event {
   private final RenderChunk renderChunk;

   public RenderChunkUniformsEvent(RenderChunk r) {
      this.renderChunk = r;
   }

   public RenderChunk getChunk() {
      return this.renderChunk;
   }

   @Override
   public boolean isCancelable() {
      return false;
   }
}
