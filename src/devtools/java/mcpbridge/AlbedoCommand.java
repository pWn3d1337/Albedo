package mcpbridge;

import com.google.gson.GsonBuilder;
import elucent.albedo.ConfigManager;
import elucent.albedo.EventManager;
import elucent.albedo.util.ShaderManager;
import elucent.albedo.util.ShaderUtil;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

/**
 * Dev command exposing Albedo's client-side state (the integrated server runs in the same JVM), so an MCP
 * client can check it through command.run:
 * <ul>
 *   <li>{@code albedo} / {@code albedo status} - config, shader state, block-handler lights, last frame's lights</li>
 *   <li>{@code albedo set <disableLights|maxLights|maxDistance|blockLightOcclusion> <value>}</li>
 * </ul>
 */
final class AlbedoCommand extends CommandBase {
    @Override
    public String getName() {
        return "albedo";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/albedo [status|set <disableLights|maxLights|maxDistance|blockLightOcclusion> <value>]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0 || args[0].equals("status")) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("lightingEnabled", ConfigManager.isLightingEnabled());
            o.addProperty("shadersReady", ShaderUtil.isReady());
            o.addProperty("suspendedForForeignShader", ShaderManager.isSuspended());
            o.addProperty("disableLights", ConfigManager.disableLights);
            o.addProperty("maxLights", ConfigManager.maxLights);
            o.addProperty("maxDistance", ConfigManager.maxDistance);
            o.addProperty("blockLightOcclusion", ConfigManager.blockLightOcclusion);
            o.addProperty("blockHandlerLights", EventManager.EXISTING.size());
            o.add("lastFrame", Handlers.lastFrameLights);
            sender.sendMessage(new TextComponentString(new GsonBuilder().setPrettyPrinting().create().toJson(o)));
            return;
        }
        if (args[0].equals("set") && args.length == 3) {
            String value = args[2];
            switch (args[1]) {
                case "disableLights":
                    ConfigManager.disableLights = Boolean.parseBoolean(value);
                    break;
                case "maxLights":
                    ConfigManager.maxLights = parseInt(value, 0, 100);
                    break;
                case "maxDistance":
                    ConfigManager.maxDistance = parseInt(value, 16, 256);
                    break;
                case "blockLightOcclusion":
                    ConfigManager.blockLightOcclusion = Boolean.parseBoolean(value);
                    break;
                default:
                    throw new WrongUsageException(getUsage(sender));
            }
            sender.sendMessage(new TextComponentString("albedo: " + args[1] + " = " + value));
            return;
        }
        throw new WrongUsageException(getUsage(sender));
    }
}
