package xyz.xenondevs.origami.mixin;

import jdk.management.HotSpotAOTCacheMXBean;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Plugin for the runServer task that ends the AOT recording and creates the AOT cache
 * once server startup is complete.
 */
public final class AotCachePlugin extends JavaPlugin implements Listener {
    
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) throws IOException {
        finishRecording();
    }
    
    private void finishRecording() throws IOException {
        String cacheDest = System.getProperty("origami.aot.cache");
        if (cacheDest == null) {
            getComponentLogger().error("Could not build AOT cache: No destination specified.");
            return;
        }
        
        Path cache = Path.of(cacheDest);
        Path tmpAot = cache.resolveSibling(cache.getFileName() + ".tmp");
        Files.deleteIfExists(tmpAot);
        var aotCache = ManagementFactory.getPlatformMXBean(HotSpotAOTCacheMXBean.class);
        try {
            if (aotCache.endRecording()) {
                if (!Files.isRegularFile(tmpAot) || Files.size(tmpAot) == 0) {
                    getComponentLogger().error("Could not assemble AOT cache {}", cache);
                }
                try {
                    Files.move(tmpAot, cache, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tmpAot, cache);
                }
                getComponentLogger().info("Successfully created AOT cache {}", cache);
            }
        } finally {
            Files.deleteIfExists(tmpAot);
        }
    }
    
}
