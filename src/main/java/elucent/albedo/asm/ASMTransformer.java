package elucent.albedo.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Inserts the hooks Albedo needs into the vanilla renderer.
 * <p>
 * The transformer runs after Forge's deobfuscation (sorting index 65536), so class names are always the MCP names,
 * and method names are SRG names in production ({@code name != transformedName}) and MCP names in dev.
 * Every hook is a static call inserted at the method start (or before the return), passing a parameter by its
 * slot, which is fixed by the method descriptor. That keeps the existing stack map frames valid, so the classes
 * are written with COMPUTE_MAXS only; COMPUTE_FRAMES would load classes through getCommonSuperClass in the
 * middle of class transformation, which can crash together with other coremods.
 */
public class ASMTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("AlbedoCore");
    private static final boolean CLIENT = FMLLaunchHandler.side() == Side.CLIENT;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        // Albedo is client-only. Patching the Profiler on a dedicated server would post an event for every
        // profiler section without anyone listening.
        if (!CLIENT) {
            return basicClass;
        }
        boolean obfuscated = !name.equals(transformedName);
        try {
            switch (transformedName) {
                case "net.minecraft.client.renderer.ChunkRenderContainer":
                    return patchRenderChunk(basicClass, obfuscated);
                case "net.minecraft.client.renderer.entity.RenderManager":
                    return patchRenderManager(basicClass, obfuscated);
                case "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher":
                    return patchTERenderer(basicClass, obfuscated);
                case "net.minecraft.client.renderer.GlStateManager":
                    return patchGlStateManager(basicClass, obfuscated);
                case "net.minecraft.client.renderer.entity.RenderLivingBase":
                    return patchRenderLivingBase(basicClass, obfuscated);
                case "net.minecraft.client.renderer.OpenGlHelper":
                    return patchOpenGlHelper(basicClass, obfuscated);
                case "net.minecraft.profiler.Profiler":
                    return patchProfiler(basicClass, obfuscated);
                case "net.minecraftforge.client.ForgeHooksClient":
                    return patchForgeHooks(basicClass);
                case "elucent.albedo.event.GatherLightsEvent":
                    return patchGatherLightsEvent(basicClass);
                default:
                    return basicClass;
            }
        } catch (Exception e) {
            // A failed patch only disables that hook; never take the game down with it
            LOGGER.error("Failed to patch {}, leaving it unchanged", transformedName, e);
            return basicClass;
        }
    }

    /** ForgeHooksClient.handleCameraTransforms(IBakedModel, TransformType, boolean): remember the item transform. */
    private byte[] patchForgeHooks(byte[] bytes) {
        String transformType = "Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;";
        return patch(bytes, "ForgeHooksClient", "handleCameraTransforms", null, m ->
                insertAtStart(m, load(1), call("elucent/albedo/util/RenderUtil", "setTransform", "(" + transformType + ")V")));
    }

    /**
     * GlStateManager.enableLighting() / disableLighting(): switch the entity shader's lighting uniform.
     * GlStateManager.glTexEnv(int, int, FloatBuffer): pass the hurt/creeper-flash overlay colour that
     * RenderLivingBase.setBrightness sets up with texture combiners (which shaders bypass) to the entity shader.
     */
    private byte[] patchGlStateManager(byte[] bytes, boolean obfuscated) {
        String enableLighting = obfuscated ? "func_179145_e" : "enableLighting";
        String disableLighting = obfuscated ? "func_179140_f" : "disableLighting";
        String glTexEnv = obfuscated ? "func_187448_b" : "glTexEnv";
        ClassNode classNode = read(bytes);
        int patched = 0;
        for (MethodNode m : classNode.methods) {
            if (m.desc.equals("()V") && m.name.equals(enableLighting)) {
                insertAtStart(m, call("elucent/albedo/util/RenderUtil", "enableLightingUniforms", "()V"));
                patched++;
            } else if (m.desc.equals("()V") && m.name.equals(disableLighting)) {
                insertAtStart(m, call("elucent/albedo/util/RenderUtil", "disableLightingUniforms", "()V"));
                patched++;
            } else if (m.desc.equals("(IILjava/nio/FloatBuffer;)V") && m.name.equals(glTexEnv)) {
                insertAtStart(m, new VarInsnNode(Opcodes.ILOAD, 0), new VarInsnNode(Opcodes.ILOAD, 1), load(2),
                        call("elucent/albedo/util/RenderUtil", "onTexEnv", "(IILjava/nio/FloatBuffer;)V"));
                patched++;
            }
        }
        return write(bytes, classNode, "GlStateManager", patched == 3);
    }

    /**
     * OpenGlHelper.glUseProgram(int): tell Albedo's shader tracking about programs bound by vanilla's ShaderManager
     * (post-processing, entity outlines) or other mods using it, so Albedo suspends its own shaders meanwhile
     * instead of overriding theirs.
     */
    private byte[] patchOpenGlHelper(byte[] bytes, boolean obfuscated) {
        String useProgram = obfuscated ? "func_153161_d" : "glUseProgram";
        return patch(bytes, "OpenGlHelper", useProgram, "(I)V", m ->
                insertAtStart(m, new VarInsnNode(Opcodes.ILOAD, 0), call("elucent/albedo/util/ShaderManager", "onExternalUseProgram", "(I)V")));
    }

    /** RenderLivingBase.unsetBrightness(): the hurt/flash overlay of the entity shader ends. */
    private byte[] patchRenderLivingBase(byte[] bytes, boolean obfuscated) {
        String unsetBrightness = obfuscated ? "func_177091_f" : "unsetBrightness";
        return patch(bytes, "RenderLivingBase", unsetBrightness, "()V", m ->
                insertAtStart(m, call("elucent/albedo/util/RenderUtil", "clearEntityOverlay", "()V")));
    }

    /** TileEntityRendererDispatcher.render(TileEntity, float, int): post RenderTileEntityEvent. */
    private byte[] patchTERenderer(byte[] bytes, boolean obfuscated) {
        String render = obfuscated ? "func_180546_a" : "render";
        return patch(bytes, "TileEntityRendererDispatcher", render, "(Lnet/minecraft/tileentity/TileEntity;FI)V", m ->
                insertAtStart(m, load(1), call("elucent/albedo/event/RenderTileEntityEvent", "postNewEvent", "(Lnet/minecraft/tileentity/TileEntity;)V")));
    }

    /** RenderManager.renderEntity(Entity, double, double, double, float, float, boolean): post RenderEntityEvent. */
    private byte[] patchRenderManager(byte[] bytes, boolean obfuscated) {
        String renderEntity = obfuscated ? "func_188391_a" : "renderEntity";
        return patch(bytes, "RenderManager", renderEntity, "(Lnet/minecraft/entity/Entity;DDDFFZ)V", m ->
                insertAtStart(m, load(1), call("elucent/albedo/event/RenderEntityEvent", "postNewEvent", "(Lnet/minecraft/entity/Entity;)V")));
    }

    /** Profiler.endStartSection(String): post ProfilerStartEvent, which drives which shader is active. */
    private byte[] patchProfiler(byte[] bytes, boolean obfuscated) {
        String endStartSection = obfuscated ? "func_76318_c" : "endStartSection";
        return patch(bytes, "Profiler", endStartSection, "(Ljava/lang/String;)V", m ->
                insertAtStart(m, load(1), call("elucent/albedo/event/ProfilerStartEvent", "postNewEvent", "(Ljava/lang/String;)V")));
    }

    /** ChunkRenderContainer.preRenderChunk(RenderChunk): upload the chunk offset after the translation. */
    private byte[] patchRenderChunk(byte[] bytes, boolean obfuscated) {
        String preRenderChunk = obfuscated ? "func_178003_a" : "preRenderChunk";
        String renderChunk = "Lnet/minecraft/client/renderer/chunk/RenderChunk;";
        return patch(bytes, "ChunkRenderContainer", preRenderChunk, "(" + renderChunk + ")V", m -> {
            boolean found = false;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    m.instructions.insertBefore(insn, load(1));
                    m.instructions.insertBefore(insn, call("elucent/albedo/util/RenderUtil", "renderChunkUniforms", "(" + renderChunk + ")V"));
                    found = true;
                }
            }
            return found;
        });
    }

    /** Renames GatherLightsEvent's 0.1.x bridge to getLightList()Ljava/util/ArrayList; (see that method). */
    private byte[] patchGatherLightsEvent(byte[] bytes) {
        return patch(bytes, "GatherLightsEvent", "albedo$legacyGetLightList", "()Ljava/util/ArrayList;", m -> {
            m.name = "getLightList";
            return true;
        });
    }

    // ---- helpers ----

    private interface MethodPatch {
        boolean apply(MethodNode m);
    }

    private static byte[] patch(byte[] bytes, String className, String method, String desc, MethodPatch patch) {
        ClassNode classNode = read(bytes);
        boolean patched = false;
        for (MethodNode m : classNode.methods) {
            if (m.name.equals(method) && (desc == null || m.desc.equals(desc))) {
                patched |= patch.apply(m);
            }
        }
        return write(bytes, classNode, className, patched);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, 0);
        return classNode;
    }

    private static byte[] write(byte[] original, ClassNode classNode, String className, boolean patched) {
        if (!patched) {
            LOGGER.warn("Could not find the method to patch in {}; some Albedo features will not work", className);
            return original;
        }
        LOGGER.info("Successfully patched {}", className);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static boolean insertAtStart(MethodNode m, AbstractInsnNode... insns) {
        InsnList list = new InsnList();
        for (AbstractInsnNode insn : insns) {
            list.add(insn);
        }
        m.instructions.insert(list);
        return true;
    }

    private static VarInsnNode load(int slot) {
        return new VarInsnNode(Opcodes.ALOAD, slot);
    }

    private static MethodInsnNode call(String owner, String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false);
    }
}
