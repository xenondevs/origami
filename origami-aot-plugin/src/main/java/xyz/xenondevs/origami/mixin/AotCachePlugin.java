package xyz.xenondevs.origami.mixin;

import jdk.management.HotSpotAOTCacheMXBean;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Plugin for the runServer task that ends the AOT recording and creates the AOT cache
 * once server startup is complete.
 */
public final class AotCachePlugin extends JavaPlugin implements Listener {
    
    private static final Pattern JAVA_ARGUMENT_FILE_QUOTABLE = Pattern.compile("[\\s#]");
    
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) throws IOException, InterruptedException {
        finishRecording();
    }
    
    private void finishRecording() throws IOException, InterruptedException {
        var aotCache = ManagementFactory.getPlatformMXBean(HotSpotAOTCacheMXBean.class);
        if (aotCache.endRecording()) {
            String cacheDest = System.getProperty("origami.aot.cache");
            if (cacheDest != null) {
                createCache(Path.of(cacheDest));
            } else {
                getComponentLogger().error("Could not build AOT cache: No destination specified.");
            }
        }
    }
    
    private void createCache(Path cache) throws IOException, InterruptedException {
        Path aotCfg = cache.resolveSibling(cache.getFileName() + ".config");
        Path tmpAot = cache.resolveSibling(cache.getFileName() + ".tmp");
        Path argumentFile = Files.createTempFile("java-arguments", ".txt");
        Files.deleteIfExists(tmpAot);
        List<String> arguments = List.of(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+AOTStreamableObjects",
            "-XX:AOTMode=create",
            "-XX:AOTConfiguration=" + aotCfg,
            "-XX:AOTCache=" + tmpAot,
            "-cp",
            System.getProperty("java.class.path"),
            "-version"
        );
        try {
            writeJavaArguments(argumentFile, arguments);
            ProcessBuilder processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + argumentFile.toAbsolutePath()
            ).directory(new File(System.getProperty("user.dir")));
            try (Process process = processBuilder.inheritIO().start()) {
                if (process.waitFor() != 0 || !Files.isRegularFile(tmpAot) || Files.size(tmpAot) == 0) {
                    throw new IllegalStateException("Could not assemble AOT cache " + cache);
                }
                Files.move(tmpAot, cache, StandardCopyOption.ATOMIC_MOVE);
            }
            
            getComponentLogger().info("Successfully created AOT cache at {}", cache);
        } finally {
            Files.deleteIfExists(argumentFile);
            Files.deleteIfExists(aotCfg);
            Files.deleteIfExists(tmpAot);
        }
    }
    
    private static void writeJavaArguments(Path path, Iterable<String> arguments) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            for (String argument : arguments) {
                String escaped = argument.replace("\\", "\\\\").replace("\"", "\\\"");
                if (escaped.isEmpty()) {
                    writer.write("\"\"");
                } else if (JAVA_ARGUMENT_FILE_QUOTABLE.matcher(escaped).find()) {
                    writer.write('"');
                    writer.write(escaped);
                    writer.write('"');
                } else {
                    writer.write(escaped);
                }
                writer.newLine();
            }
        }
    }
    
}
