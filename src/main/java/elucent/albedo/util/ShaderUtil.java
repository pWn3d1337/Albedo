package elucent.albedo.util;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.resource.IResourceType;
import net.minecraftforge.client.resource.ISelectiveResourceReloadListener;
import net.minecraftforge.client.resource.VanillaResourceType;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ShaderUtil implements ISelectiveResourceReloadListener {
    private static final Logger LOGGER = LogManager.getLogger("Albedo");

    public static ShaderManager fastLightProgram;
    public static ShaderManager entityLightProgram;
    public static ShaderManager depthProgram;

    private static boolean ready = false;

    /** False if the shaders failed to compile or link; Albedo lighting then stays off instead of crashing. */
    public static boolean isReady() {
        return ready;
    }

    public static void init(IResourceManager manager) {
        ready = false;
        deleteAll();
        if (!OpenGlHelper.shadersSupported) {
            LOGGER.error("This GPU/driver does not support GLSL shaders; Albedo lighting is disabled");
            return;
        }
        try {
            fastLightProgram = new ShaderManager(new ResourceLocation("albedo:fastlight"), manager);
            entityLightProgram = new ShaderManager(new ResourceLocation("albedo:entitylight"), manager);
            depthProgram = new ShaderManager(new ResourceLocation("albedo:depth"), manager);
            ready = true;
        } catch (RuntimeException e) {
            LOGGER.error("Failed to load the Albedo shaders; Albedo lighting is disabled", e);
            deleteAll();
        }
    }

    private static void deleteAll() {
        for (ShaderManager shader : new ShaderManager[]{fastLightProgram, entityLightProgram, depthProgram}) {
            if (shader != null) {
                shader.delete();
            }
        }
        fastLightProgram = null;
        entityLightProgram = null;
        depthProgram = null;
    }

    public static int loadProgram(String vsh, String fsh, IResourceManager manager) {
        int vertexShader = createShader(vsh, OpenGlHelper.GL_VERTEX_SHADER, manager);
        int fragmentShader;
        try {
            fragmentShader = createShader(fsh, OpenGlHelper.GL_FRAGMENT_SHADER, manager);
        } catch (RuntimeException e) {
            GL20.glDeleteShader(vertexShader);
            throw e;
        }
        int program = OpenGlHelper.glCreateProgram();
        OpenGlHelper.glAttachShader(program, vertexShader);
        OpenGlHelper.glAttachShader(program, fragmentShader);
        OpenGlHelper.glLinkProgram(program);
        // The shader objects are no longer needed once linked
        GL20.glDetachShader(program, vertexShader);
        GL20.glDetachShader(program, fragmentShader);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 32768);
            GL20.glDeleteProgram(program);
            throw new RuntimeException("Error linking shader program " + vsh + " / " + fsh + ": " + log);
        }
        String log = GL20.glGetProgramInfoLog(program, 32768);
        if (!log.trim().isEmpty()) {
            LOGGER.debug("Shader program {} / {} link log: {}", vsh, fsh, log);
        }
        return program;
    }

    public static int createShader(String filename, int shaderType, IResourceManager manager) {
        int shader = OpenGlHelper.glCreateShader(shaderType);
        if (shader == 0) {
            throw new RuntimeException("Could not create a GL shader object for " + filename);
        }
        byte[] source;
        try (IResource resource = manager.getResource(new ResourceLocation(filename));
             InputStream in = resource.getInputStream()) {
            source = IOUtils.toByteArray(in);
        } catch (IOException e) {
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Could not read shader " + filename, e);
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(source.length);
        buffer.put(source);
        buffer.flip();
        OpenGlHelper.glShaderSource(shader, buffer);
        OpenGlHelper.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, OpenGlHelper.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = getLogInfo(shader);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Error compiling shader " + filename + ": " + log);
        }
        return shader;
    }

    public static String getLogInfo(int shader) {
        return GL20.glGetShaderInfoLog(shader, GL20.glGetShaderi(shader, GL20.GL_INFO_LOG_LENGTH));
    }

    public static String readFileAsString(String filename, IResourceManager manager) throws Exception {
        try (IResource resource = manager.getResource(new ResourceLocation(filename));
             BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager, Predicate<IResourceType> resourcePredicate) {
        if (resourcePredicate.test(VanillaResourceType.SHADERS)) {
            init(resourceManager);
        }
    }
}
