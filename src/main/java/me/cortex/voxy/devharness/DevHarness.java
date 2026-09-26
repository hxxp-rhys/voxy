package me.cortex.voxy.devharness;

import com.mojang.datafixers.util.Pair;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * DEVELOPMENT-ONLY scripted test driver. Not part of the shipped jar (build.gradle excludes the package) and inert
 * unless the JVM is started with {@code -Dvoxy.devHarness=<script file>} ({@code ./gradlew runClient -Pharness=<file>}).
 * <p>
 * The script is a plain text file, one step per line ({@code #} comments allowed):
 * <pre>
 *   wait N                       wait N client ticks
 *   cmd COMMAND                  run a server command as the player (permission 4, output suppressed)
 *   ccmd COMMAND                 run a client command (e.g. "voxy ...")
 *   ocean Y_ABOVE PITCH [platform]  find the nearest ocean, pick the direction with the longest stretch of ocean,
 *                                teleport the player there Y_ABOVE blocks over sea level looking that way at PITCH
 *                                degrees, make them fly, and optionally build a stone platform in front of them.
 *                                Sets the anchor used by "rel" and "turn".
 *   anchor                       set the anchor to the player's current position / yaw
 *   rel F S U COMMAND            run COMMAND with {x} {y} {z} replaced by anchor + F*forward + S*right + U*up
 *   every N STEP                 run STEP (any step line) once per tick for N ticks
 *   turn DYAW PITCH              set the player's rotation to anchorYaw + DYAW, PITCH
 *   camera FIRST_PERSON|THIRD_PERSON_BACK|THIRD_PERSON_FRONT
 *   hidegui true|false
 *   fov DEGREES                  set the field of view (30 = zoomed in)
 *   shaders true|false           enable/disable the Iris shader pack (dev environment always has Iris)
 *   shot NAME                    save screenshots/NAME.png of the last rendered frame
 *   log TEXT
 *   quit                         stop the client
 * </pre>
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class DevHarness {
    private static final String SCRIPT = System.getProperty("voxy.devHarness");
    private static List<String> steps;
    private static int index;
    private static int waitTicks;
    private static String repeatLine;
    private static int repeatTicks;
    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static final java.util.Set<String> STEP_OPS = java.util.Set.of(
            "wait", "log", "cmd", "ccmd", "hidegui", "fov", "shaders", "window", "camera", "shot", "quit", "anchor", "turn", "rel", "every", "ocean");

    // anchor (set by "ocean" / "anchor")
    private static double ax, ay, az;
    private static double fx, fz;   // forward unit vector (xz)
    private static float ayaw;

    private DevHarness() {}

    /** {@code -Dvoxy.devHarness.world=<save name>:<seed>}: open that save, creating a normal world with the seed if absent. */
    private static final String WORLD = System.getProperty("voxy.devHarness.world");
    private static boolean worldRequested;
    /**
     * Set once NeoForge has fired {@link RegisterPayloadHandlersEvent}, the last mod-loading init task ("Network registry
     * lock", ref neoforge CommonModLoader.finish). Joining a world before it makes the integrated server see an
     * unregistered (vanilla-looking) client: mods such as rrls (Remove Reloading Screen) show the title screen while
     * the initial resource reload - in which NeoForge runs that task - is still in progress, and vanilla's
     * {@code --quickPlaySingleplayer} joins from that same early point (ref Minecraft.buildInitialScreens). So the
     * harness opens its world only after this flag is set.
     */
    private static volatile boolean modLoadingComplete;

    @EventBusSubscriber(modid = "voxy", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusHooks {
        private ModBusHooks() {}

        @SubscribeEvent
        public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
            modLoadingComplete = true;
            if (SCRIPT != null) Logger.info("[DevHarness] payload handlers registered, mod loading complete");
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (SCRIPT == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null && WORLD != null && !worldRequested && modLoadingComplete && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
            worldRequested = true;
            var parts = WORLD.split(":", 2);
            var name = parts[0];
            long seed = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
            if (mc.getLevelSource().levelExists(name)) {
                Logger.info("[DevHarness] opening existing world " + name);
                mc.createWorldOpenFlows().openWorld(name, () -> Logger.error("[DevHarness] failed to open world " + name));
            } else {
                Logger.info("[DevHarness] creating normal world " + name + " with seed " + seed);
                var settings = new net.minecraft.world.level.LevelSettings(name, net.minecraft.world.level.GameType.CREATIVE, false,
                        net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(),
                        net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                var options = new net.minecraft.world.level.levelgen.WorldOptions(seed, true, false);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, options,
                        net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions, mc.screen);
            }
            return;
        }
        if (mc.player == null || mc.level == null || !mc.hasSingleplayerServer()) return;
        if (steps == null) {
            try {
                steps = new ArrayList<>();
                for (var line : Files.readAllLines(Path.of(SCRIPT))) {
                    line = line.strip();
                    if (!line.isEmpty() && !line.startsWith("#")) steps.add(line);
                }
                Logger.info("[DevHarness] loaded " + steps.size() + " steps from " + SCRIPT);
            } catch (IOException e) {
                Logger.error("[DevHarness] cannot read script " + SCRIPT, e);
                steps = List.of();
            }
        }
        if (busy.get()) return;
        if (repeatLine != null) {
            execute(mc, repeatLine);
            if (--repeatTicks <= 0) repeatLine = null;
            return;
        }
        if (waitTicks > 0) { waitTicks--; return; }
        if (index >= steps.size()) return;
        var line = steps.get(index++);
        Logger.info("[DevHarness] step " + index + "/" + steps.size() + ": " + line);
        execute(mc, line);
    }

    private static void execute(Minecraft mc, String line) {
        var sp = line.split("\\s+", 2);
        var op = sp[0].toLowerCase(Locale.ROOT);
        var arg = sp.length > 1 ? sp[1] : "";
        var server = mc.getSingleplayerServer();
        switch (op) {
            case "wait" -> waitTicks = Integer.parseInt(arg.trim());
            case "log" -> Logger.info("[DevHarness] " + arg);
            case "cmd" -> runServerCommand(server, arg);
            case "ccmd" -> mc.player.connection.sendCommand(arg);
            case "hidegui" -> mc.options.hideGui = Boolean.parseBoolean(arg.trim());
            case "fov" -> mc.options.fov().set(Integer.parseInt(arg.trim()));
            case "shaders" -> net.irisshaders.iris.api.v0.IrisApi.getInstance().getConfig().setShadersEnabledAndApply(Boolean.parseBoolean(arg.trim()));
            case "window" -> {
                var p = arg.trim().split("\\s+");
                mc.getWindow().setWindowed(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            }
            case "camera" -> mc.options.setCameraType(CameraType.valueOf(arg.trim()));
            case "shot" -> Screenshot.grab(mc.gameDirectory, arg.trim() + ".png", mc.getMainRenderTarget(),
                    c -> Logger.info("[DevHarness] screenshot " + arg.trim() + ": " + c.getString()));
            case "quit" -> mc.stop();
            case "anchor" -> {
                ax = mc.player.getX(); ay = mc.player.getY(); az = mc.player.getZ();
                setYaw(mc.player.getYRot());
            }
            case "turn" -> {
                var p = arg.trim().split("\\s+");
                float yaw = ayaw + Float.parseFloat(p[0]);
                runServerCommand(server, String.format(Locale.ROOT, "tp @s ~ ~ ~ %.2f %.2f", yaw, Float.parseFloat(p[1])));
            }
            case "rel" -> {
                var p = arg.trim().split("\\s+", 4);
                double f = Double.parseDouble(p[0]), s = Double.parseDouble(p[1]), u = Double.parseDouble(p[2]);
                double rx = -fz, rz = fx; // right-hand side of the forward direction
                double x = ax + fx * f + rx * s, y = ay + u, z = az + fz * f + rz * s;
                var cmd = p[3].replace("{x}", fmt(x)).replace("{y}", fmt(y)).replace("{z}", fmt(z));
                // a bare command (summon/fill/particle/...) is a server command; a known step keyword is a step
                var head = cmd.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                execute(mc, STEP_OPS.contains(head) ? cmd : "cmd " + cmd);
            }
            case "every" -> {
                var p = arg.trim().split("\\s+", 2);
                repeatTicks = Integer.parseInt(p[0]);
                repeatLine = p[1];
            }
            case "ocean" -> {
                var p = arg.trim().split("\\s+");
                int yAbove = Integer.parseInt(p[0]);
                float pitch = Float.parseFloat(p[1]);
                boolean platform = p.length > 2 && p[2].equalsIgnoreCase("platform");
                busy.set(true);
                server.execute(() -> {
                    try {
                        gotoOcean(server, yAbove, pitch, platform);
                    } catch (Throwable t) {
                        Logger.error("[DevHarness] ocean step failed", t);
                    } finally {
                        busy.set(false);
                    }
                });
            }
            default -> Logger.error("[DevHarness] unknown step: " + line);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void setYaw(float yaw) {
        ayaw = yaw;
        double r = Math.toRadians(yaw);
        fx = -Math.sin(r);
        fz = Math.cos(r);
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static void runServerCommand(MinecraftServer server, String command) {
        server.execute(() -> {
            CommandSourceStack src = player(server).createCommandSourceStack().withPermission(4).withSuppressedOutput();
            server.getCommands().performPrefixedCommand(src, command);
        });
    }

    /** Runs on the server thread. */
    private static void gotoOcean(MinecraftServer server, int yAbove, float pitch, boolean platform) {
        ServerPlayer player = player(server);
        ServerLevel level = player.serverLevel();
        Predicate<Holder<Biome>> isOcean = h -> h.is(BiomeTags.IS_OCEAN);
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(isOcean, player.blockPosition(), 6400, 32, 64);
        if (found == null) {
            Logger.error("[DevHarness] no ocean biome within 6400 blocks");
            return;
        }
        BlockPos o = found.getFirst();
        BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int sea = level.getSeaLevel();
        // choose the yaw whose ray stays over ocean the longest (samples every 32 blocks up to 800)
        float bestYaw = 0; int bestRun = -1;
        for (int yawDeg = 0; yawDeg < 360; yawDeg += 45) {
            double r = Math.toRadians(yawDeg);
            double dx = -Math.sin(r), dz = Math.cos(r);
            int run = 0;
            for (int d = 32; d <= 800; d += 32) {
                int x = (int) Math.round(o.getX() + dx * d), z = (int) Math.round(o.getZ() + dz * d);
                if (isOcean.test(biomes.getNoiseBiome(x >> 2, sea >> 2, z >> 2, sampler))) run++; else break;
            }
            if (run > bestRun) { bestRun = run; bestYaw = yawDeg; }
        }
        ax = o.getX() + 0.5; ay = sea + yAbove; az = o.getZ() + 0.5;
        setYaw(bestYaw);
        Logger.info(String.format(Locale.ROOT, "[DevHarness] ocean at %s, facing yaw %.0f (ocean run %d x 32 blocks), anchor %.1f %.1f %.1f",
                o, bestYaw, bestRun, ax, ay, az));
        CommandSourceStack src = player.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        var commands = server.getCommands();
        commands.performPrefixedCommand(src, "gamemode creative");
        commands.performPrefixedCommand(src, String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.2f %.2f", ax, ay, az, bestYaw, pitch));
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        if (platform) {
            // stone slab 13 wide, from the player's feet to 14 blocks ahead, one block below the anchor
            double rx = -fz, rz = fx;
            double[] xs = {ax - rx * 6, ax + rx * 6, ax + fx * 14 - rx * 6, ax + fx * 14 + rx * 6};
            double[] zs = {az - rz * 6, az + rz * 6, az + fz * 14 - rz * 6, az + fz * 14 + rz * 6};
            int x1 = (int) Math.floor(min(xs)), x2 = (int) Math.floor(max(xs));
            int z1 = (int) Math.floor(min(zs)), z2 = (int) Math.floor(max(zs));
            int y = (int) Math.floor(ay) - 1;
            commands.performPrefixedCommand(src, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x1, y, z1, x2, y, z2));
        }
    }

    private static double min(double[] v) { double m = v[0]; for (double d : v) m = Math.min(m, d); return m; }
    private static double max(double[] v) { double m = v[0]; for (double d : v) m = Math.max(m, d); return m; }
}
