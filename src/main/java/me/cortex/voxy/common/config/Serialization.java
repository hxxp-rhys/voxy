package me.cortex.voxy.common.config;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.PlatformUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Serialization {
    public static final Set<Class<?>> CONFIG_TYPES = new HashSet<>();
    public static Gson GSON;

    private static final class GsonConfigSerialization <T> implements TypeAdapterFactory {
        private final String typeField = "TYPE";
        private final Class<T> clz;

        private final Map<String, Class<? extends T>> name2type = new HashMap<>();
        private final Map<Class<? extends T>, String> type2name = new HashMap<>();

        private GsonConfigSerialization(Class<T> clz) {
            this.clz = clz;
        }

        public GsonConfigSerialization<T> register(String typeName, Class<? extends T> cls) {
            if (this.name2type.put(typeName, cls) != null) {
                throw new IllegalStateException("Type name already registered: " + typeName);
            }
            if (this.type2name.put(cls, typeName) != null) {
                throw new IllegalStateException("Class already registered with type name: " + typeName + ", " + cls);
            }
            return this;
        }


        private T deserialize(Gson gson, JsonElement json) {
            var retype = this.name2type.get(json.getAsJsonObject().remove(this.typeField).getAsString());
            return gson.getDelegateAdapter(this, TypeToken.get(retype)).fromJsonTree(json);
        }

        private JsonElement serialize(Gson gson, T value) {
            String name = this.type2name.get(value.getClass());
            if (name == null) {
                name = "UNKNOWN_TYPE_{" + value.getClass().getName() + "}";
            }

            var vjson = gson
                    .getDelegateAdapter(this, TypeToken.get((Class<T>) value.getClass()))
                    .toJsonTree(value);
            //All of this is so that the config_type is at the top :blob_face:
            var json = new JsonObject();
            json.addProperty(this.typeField, name);
            vjson.getAsJsonObject().asMap().forEach(json::add);
            return json;
        }


        @Override
        public <X> TypeAdapter<X> create(Gson gson, TypeToken<X> type) {
            if (this.clz.isAssignableFrom(type.getRawType())) {
                var jsonObjectAdapter = gson.getAdapter(JsonElement.class);

                return (TypeAdapter<X>) new TypeAdapter<T>() {
                    @Override
                    public void write(JsonWriter out, T value) throws IOException {
                        jsonObjectAdapter.write(out, GsonConfigSerialization.this.serialize(gson, value));
                    }

                    @Override
                    public T read(JsonReader in) throws IOException {
                        var obj = jsonObjectAdapter.read(in);
                        return GsonConfigSerialization.this.deserialize(gson, obj);
                    }
                };
            }
            return null;
        }
    }

    //Safety net (1.21.1/NeoForge): the classes that must be registered for a voxy storage config to round-trip.
    // The dynamic scan below normally finds all of these (plus anything new); this list only guarantees that a
    // loader/packaging quirk (e.g. a jar-in-jar filesystem that cannot be listed) can never leave GSON with zero
    // registered config types, which would silently break every existing save's config.json.
    private static final String[] KNOWN_CONFIG_CLASSES = {
            "me.cortex.voxy.common.config.compressors.LZ4Compressor$Config",
            "me.cortex.voxy.common.config.compressors.ZSTDCompressor$Config",
            "me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend$Config",
            "me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend$Config",
            "me.cortex.voxy.common.config.storage.redis.RedisStorageBackend$Config",
            "me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend$Config",
            "me.cortex.voxy.common.config.storage.other.ReadonlyCachingLayer$Config",
            "me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor$Config",
            "me.cortex.voxy.common.config.storage.other.ConditionalStorageBackendConfig",
            "me.cortex.voxy.common.config.storage.other.FragmentedStorageBackendAdaptor$Config",
            "me.cortex.voxy.common.config.storage.other.FragmentedStorageBackendAdaptor$Config2",
            "me.cortex.voxy.common.config.storage.other.BasicPathInsertionConfig",
            "me.cortex.voxy.common.config.section.SectionSerializationStorage$Config",
    };

    public static void init() {
        String BASE_SEARCH_PACKAGE = "me.cortex.voxy";

        Map<Class<?>, GsonConfigSerialization<?>> serializers = new HashMap<>();

        Set<String> clazzs = new LinkedHashSet<>();
        //1.21.1/NeoForge: FabricLoader.getModContainer("voxy").getRootPaths() -> PlatformUtil.modRootPaths. The
        // NeoForge SecureJar root path is a union-filesystem path for both a production jar and a dev classes
        // directory, so the same directory walk covers both.
        for (var path : PlatformUtil.modRootPaths(PlatformUtil.MOD_ID)) {
            clazzs.addAll(collectAllClasses(path, BASE_SEARCH_PACKAGE));
        }
        clazzs.addAll(collectAllClasses(BASE_SEARCH_PACKAGE));
        if (clazzs.isEmpty()) {
            Logger.warn("Class scan found no voxy classes, falling back to the known config class list");
            clazzs.addAll(Arrays.asList(KNOWN_CONFIG_CLASSES));
        }
        int count = 0;
        outer:
        for (var clzName : clazzs) {
            if (VoxyCommon.IS_DEDICATED_SERVER&&clzName.startsWith("me.cortex.voxy.client")) {
                continue;//Dont load stuff from client path when were on a dedicated server
            }
            if (!clzName.toLowerCase(Locale.ROOT).contains("config")) {
                continue;//Only load classes that contain the word config
            }
            if (clzName.contains("mixin")) {
                continue;//Dont want to load mixins
            }
            if (clzName.contains("ModMenuIntegration")) {
                continue;//Dont want to modmenu incase it doesnt exist
            }
            if (clzName.contains("VoxyConfigScreenPages")) {
                continue;//Dont want to modmenu incase it doesnt exist
            }
            if (clzName.contains("VoxyConfigScreenFactory")) {
                continue;//NeoForge config screen factory references the mod list gui, dont load it this early
            }
            if (clzName.endsWith("VoxyConfig")) {
                continue;//Special case to prevent recursive loading pain
            }

            if (clzName.equals(Serialization.class.getName())) {
                continue;//Dont want to load ourselves
            }

            try {
                var clz = Class.forName(clzName);
                if (Modifier.isAbstract(clz.getModifiers())) {
                    //Dont want to register abstract classes
                    continue;
                }
                var original = clz;
                while ((clz = clz.getSuperclass()) != null) {
                    if (CONFIG_TYPES.contains(clz)) {
                        Method nameMethod = null;
                        try {
                            nameMethod = original.getMethod("getConfigTypeName");
                            nameMethod.setAccessible(true);
                        } catch (NoSuchMethodException e) {}
                        if (nameMethod == null) {
                            Logger.error("WARNING: Config class " + clzName + " doesnt contain a getConfigTypeName and thus wont be serializable");
                            continue outer;
                        }
                        count++;
                        String name = (String) nameMethod.invoke(null);
                        serializers.computeIfAbsent(clz, GsonConfigSerialization::new)
                                .register(name, (Class) original);
                        Logger.info("Registered " + original.getSimpleName() + " as " + name + " for config type " + clz.getSimpleName());
                        break;
                    }
                }
            } catch (Throwable e) {
                Logger.error("Error while setting up config serialization", e);
            }
        }

        var builder = new GsonBuilder()
                .setPrettyPrinting();
        for (var entry : serializers.entrySet()) {
            builder.registerTypeAdapterFactory(entry.getValue());
        }

        GSON = builder.create();
        Logger.info("Registered " + count + " config types");
    }

    private static List<String> collectAllClasses(String pack) {
        try {
            InputStream stream = Serialization.class.getClassLoader()
                    .getResourceAsStream(pack.replaceAll("[.]", "/"));
            if (stream == null) {
                return List.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            return reader.lines().flatMap(inner -> {
                if (inner.endsWith(".class")) {
                    return Stream.of(pack + "." + inner.replace(".class", ""));
                } else if (!inner.contains(".")) {
                    return collectAllClasses(pack + "." + inner).stream();
                } else {
                    return Stream.of();
                }
            }).collect(Collectors.toList());
        } catch (Exception e) {
            Logger.error("Failed to collect classes in package: " + pack, e);
            return List.of();
        }
    }
    private static List<String> collectAllClasses(Path base, String pack) {
        try {
            var dir = base.resolve(pack.replaceAll("[.]", "/"));
            if (!Files.exists(dir)) {
                return List.of();
            }
            try (var listing = Files.list(dir)) {
                return listing.flatMap(inner -> {
                    if (inner.getFileName().toString().endsWith(".class")) {
                        return Stream.of(pack + "." + inner.getFileName().toString().replace(".class", ""));
                    } else if (Files.isDirectory(inner)) {
                        return collectAllClasses(base, pack + "." + inner.getFileName()).stream();
                    } else {
                        return Stream.of();
                    }
                }).collect(Collectors.toList());
            }
        } catch (Exception e) {
            //1.21.1: never let a filesystem provider quirk (union fs, jar-in-jar) abort config serialization setup
            Logger.error("Failed to collect classes in package: " + pack + " from " + base, e);
            return List.of();
        }
    }
}
