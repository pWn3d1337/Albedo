package elucent.albedo.util;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL20;

/**
 * One of Albedo's GLSL programs, plus tracking of the program that is currently bound.
 * <p>
 * Programs bound through vanilla's {@code OpenGlHelper.glUseProgram} (vanilla post-processing and entity-outline
 * shaders, and other mods using {@code net.minecraft.client.shader.ShaderManager}) are reported to
 * {@link #onExternalUseProgram(int)} by the coremod. While such a program is bound Albedo suspends its own
 * shaders, so it neither overrides the other program nor writes its uniforms into it.
 */
public class ShaderManager {
    private static ShaderManager currentShader = null;
    private static int currentProgram = -1;
    private static boolean externalProgramActive = false;

    private final int program;
    private final Object2IntOpenHashMap<String> uniformLocations = new Object2IntOpenHashMap<>();

    public ShaderManager(ResourceLocation shader, IResourceManager resourceManager) {
        this.program = ShaderUtil.loadProgram(
                String.format("%s:shaders/%s.vs", shader.getResourceDomain(), shader.getResourcePath()),
                String.format("%s:shaders/%s.fs", shader.getResourceDomain(), shader.getResourcePath()),
                resourceManager);
        uniformLocations.defaultReturnValue(Integer.MIN_VALUE);
    }

    public static ShaderManager getCurrentShader() {
        return currentShader;
    }

    public static void stopShader() {
        if (externalProgramActive) {
            return; // not ours to unbind
        }
        if (currentProgram != 0) {
            GL20.glUseProgram(0);
            currentProgram = 0;
            currentShader = null;
        }
    }

    public static boolean isCurrentShader(ShaderManager shader) {
        return shader != null && currentProgram == shader.program;
    }

    /** True while another program was bound through OpenGlHelper and not yet unbound. */
    public static boolean isSuspended() {
        return externalProgramActive;
    }

    /** Called by the coremod at the start of {@code OpenGlHelper.glUseProgram(int)}. */
    public static void onExternalUseProgram(int program) {
        externalProgramActive = program != 0;
        currentProgram = program;
        currentShader = null;
    }

    /**
     * Forgets a foreign program that was never unbound through OpenGlHelper (once per frame), so Albedo cannot stay
     * suspended forever.
     */
    public static void resetExternalProgram() {
        if (externalProgramActive) {
            externalProgramActive = false;
            currentProgram = -1;
            currentShader = null;
        }
    }

    public void useShader() {
        if (externalProgramActive) {
            return;
        }
        if (!isCurrentShader(this)) {
            GL20.glUseProgram(program);
            currentProgram = program;
            currentShader = this;
        }
    }

    /** Deletes the GL program (used when shaders are reloaded). */
    public void delete() {
        if (isCurrentShader(this)) {
            GL20.glUseProgram(0);
            currentProgram = 0;
            currentShader = null;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
    }

    private int location(String uniform) {
        // glGetUniformLocation is a string lookup in the driver; uniforms are set for every entity, so cache it
        int location = uniformLocations.getInt(uniform);
        if (location == Integer.MIN_VALUE) {
            location = GL20.glGetUniformLocation(program, uniform);
            uniformLocations.put(uniform, location);
        }
        return location;
    }

    public void setUniform(String uniform, int value) {
        if (isCurrentShader(this)) {
            GL20.glUniform1i(location(uniform), value);
        }
    }

    public void setUniform(String uniform, boolean value) {
        if (isCurrentShader(this)) {
            GL20.glUniform1i(location(uniform), value ? 1 : 0);
        }
    }

    public void setUniform(String uniform, float value) {
        if (isCurrentShader(this)) {
            GL20.glUniform1f(location(uniform), value);
        }
    }

    public void setUniform(String uniform, int v1, int v2) {
        if (isCurrentShader(this)) {
            GL20.glUniform2i(location(uniform), v1, v2);
        }
    }

    public void setUniform(String uniform, int v1, int v2, int v3) {
        if (isCurrentShader(this)) {
            GL20.glUniform3i(location(uniform), v1, v2, v3);
        }
    }

    public void setUniform(String uniform, float v1, float v2) {
        if (isCurrentShader(this)) {
            GL20.glUniform2f(location(uniform), v1, v2);
        }
    }

    public void setUniform(String uniform, float v1, float v2, float v3) {
        if (isCurrentShader(this)) {
            GL20.glUniform3f(location(uniform), v1, v2, v3);
        }
    }

    public void setUniform(String uniform, float v1, float v2, float v3, float v4) {
        if (isCurrentShader(this)) {
            GL20.glUniform4f(location(uniform), v1, v2, v3, v4);
        }
    }
}
