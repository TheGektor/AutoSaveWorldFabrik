package com.autosaveworldfabrik;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AutoSaveWorldFabrik implements ModInitializer {
    public static final String MOD_ID = "autosaveworldfabrik";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static MinecraftServer server;
    private static ScheduledExecutorService scheduler;
    private static Properties config;
    
    // Configuration values
    private static int saveAllTimeMinutes = 5;
    private static String autoRestartTime = "0";
    
    @Override
    public void onInitialize() {
        LOGGER.info("AutoSaveWorldFabrik initialized!");
        
        // Load configuration
        loadConfig();
        
        // Initialize scheduler
        scheduler = Executors.newScheduledThreadPool(2);
        
        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }
    
    private void onServerStarted(MinecraftServer server) {
        AutoSaveWorldFabrik.server = server;
        LOGGER.info("Server started, initializing auto-save and auto-restart tasks...");
        
        // Start auto-save task
        if (saveAllTimeMinutes > 0) {
            startAutoSaveTask();
            LOGGER.info("Auto-save enabled: every {} minutes", saveAllTimeMinutes);
        } else {
            LOGGER.info("Auto-save disabled");
        }
        
        // Start auto-restart task
        if (!autoRestartTime.equals("0")) {
            startAutoRestartTask();
            LOGGER.info("Auto-restart enabled: {}", autoRestartTime);
        } else {
            LOGGER.info("Auto-restart disabled");
        }
    }
    
    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("Server stopping, shutting down scheduler...");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void loadConfig() {
        config = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("autosaveworldfabrik.properties")) {
            if (input != null) {
                config.load(input);
                saveAllTimeMinutes = Integer.parseInt(config.getProperty("save-all-time", "5"));
                autoRestartTime = config.getProperty("auto-restart-time", "0");
                LOGGER.info("Configuration loaded: save-all-time={}, auto-restart-time={}", saveAllTimeMinutes, autoRestartTime);
            } else {
                LOGGER.warn("Configuration file not found, using defaults");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load configuration", e);
        }
    }
    
    private void startAutoSaveTask() {
        scheduler.scheduleAtFixedRate(() -> {
            if (server != null && !server.isStopped()) {
                try {
                    // Execute save-all command
                    server.getCommandManager().executeWithPrefix(
                        server.getCommandSource().withLevel(4),
                        "save-all"
                    );
                    LOGGER.info("Auto-save executed");
                } catch (Exception e) {
                    LOGGER.error("Failed to execute auto-save", e);
                }
            }
        }, saveAllTimeMinutes, saveAllTimeMinutes, TimeUnit.MINUTES);
    }
    
    private void startAutoRestartTask() {
        if (autoRestartTime.startsWith("-")) {
            // Specific time format (-HH:MM)
            startScheduledRestartTask();
        } else {
            // Interval format (hours)
            int restartIntervalHours = Integer.parseInt(autoRestartTime);
            startIntervalRestartTask(restartIntervalHours);
        }
    }
    
    private void startScheduledRestartTask() {
        String timeStr = autoRestartTime.substring(1); // Remove the '-' prefix
        String[] timeParts = timeStr.split(":");
        int hour = Integer.parseInt(timeParts[0]);
        int minute = Integer.parseInt(timeParts[1]);
        
        LocalTime restartTime = LocalTime.of(hour, minute);
        
        scheduler.scheduleAtFixedRate(() -> {
            if (server != null && !server.isStopped()) {
                ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));
                LocalTime currentTime = now.toLocalTime();
                
                // Check if current time matches restart time (with 1 minute tolerance)
                if (Math.abs(currentTime.toSecondOfDay() - restartTime.toSecondOfDay()) <= 60) {
                    restartServer("Scheduled restart at " + restartTime);
                }
            }
        }, 0, 1, TimeUnit.MINUTES);
    }
    
    private void startIntervalRestartTask(int intervalHours) {
        scheduler.scheduleAtFixedRate(() -> {
            if (server != null && !server.isStopped()) {
                restartServer("Interval restart (every " + intervalHours + " hours)");
            }
        }, intervalHours, intervalHours, TimeUnit.HOURS);
    }
    
    private void restartServer(String reason) {
        if (server != null && !server.isStopped()) {
            LOGGER.info("Initiating server restart: {}", reason);
            
            // Broadcast restart message to all players
            server.getPlayerManager().broadcast(Text.literal("§c[AutoSaveWorldFabrik] Server will restart in 10 seconds: " + reason), false);
            
            // Schedule restart after 10 seconds
            scheduler.schedule(() -> {
                try {
                    server.getCommandManager().executeWithPrefix(
                        server.getCommandSource().withLevel(4),
                        "stop"
                    );
                } catch (Exception e) {
                    LOGGER.error("Failed to restart server", e);
                }
            }, 10, TimeUnit.SECONDS);
        }
    }
}
