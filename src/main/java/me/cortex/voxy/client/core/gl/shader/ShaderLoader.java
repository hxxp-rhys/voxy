package me.cortex.voxy.client.core.gl.shader;


import net.minecraft.resources.ResourceLocation;
import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ShaderLoader {
    public static String parse(String id) {
        var src =  "#version 460 core\n";
        // 1.21.1: net.minecraft.resources.ResourceLocation.parse (ref ResourceLocation.java:65), no Identifier
        src += String.join("\n", ShaderLoadingParser.parseRoot(ResourceLocation.parse(id)));
        return src;
    }


    //Use our own loader

    private static final class ShaderLoadingParser {
        private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");
        public static List<String> parseRoot(ResourceLocation id) {
            List<String> out = new ArrayList<>();
            for (var line : toLines(loadShaderAsset(id))) {
                if (line.startsWith("#version")) {
                    continue;
                } else if (line.startsWith("#import")) {
                    var match = IMPORT_PATTERN.matcher(line);
                    if (!match.matches()) throw new IllegalArgumentException("Unknown import: " + line);
                    var iid = ResourceLocation.fromNamespaceAndPath(match.group("namespace"), match.group("path"));// ref ResourceLocation.java:61
                    out.addAll(parseRoot(iid));
                } else {
                    out.add(line);
                }
            }
            return out;
        }

        private static List<String> toLines(String src) {
            // Java 21: BufferedReader.readAllLines() is Java 25, use lines().toList()
            try (var reader = new BufferedReader(new StringReader(src))) {
                return reader.lines().toList();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        private static String loadShaderAsset(ResourceLocation id) {
            //Contract C21: read from Voxy's own classloader, not the resource manager
            String path = String.format("/assets/%s/shaders/%s", id.getNamespace(), id.getPath());
            try (InputStream in = ShaderLoadingParser.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new RuntimeException("Shader not found: " + path);
                } else {
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read shader source for " + path, e);
            }
        }
    }
}
