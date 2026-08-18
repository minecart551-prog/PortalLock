package com.example.portallock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PortalLockActivationState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File("config/portal-lock/activated-portals.json");
    private static final List<ActivatedPortal> portals = new ArrayList<>();

    private PortalLockActivationState() {}

    public static void load() {
        portals.clear();
        if (!FILE.exists()) return;
        try (FileReader reader = new FileReader(FILE, StandardCharsets.UTF_8)) {
            List<ActivatedPortal> loaded = GSON.fromJson(reader, List.class);
            if (loaded != null) {
                for (ActivatedPortal p : loaded) {
                    if (p != null && p.dimension != null) portals.add(p);
                }
            }
        } catch (Exception e) {
            System.out.println("[PortalLock] Failed to load activated portals: " + e.getMessage());
        }
    }

    public static void save() {
        FILE.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(portals, writer);
        } catch (IOException e) {
            System.out.println("[PortalLock] Failed to save activated portals: " + e.getMessage());
        }
    }

    public static boolean activate(int x, int y, int z, String dimension, String playerName) {
        for (ActivatedPortal p : portals) {
            if (p.x == x && p.y == y && p.z == z && p.dimension.equals(dimension)) {
                p.activated_by = playerName;
                save();
                return false;
            }
        }
        portals.add(new ActivatedPortal(x, y, z, dimension, playerName));
        save();
        return true;
    }

    public static boolean deactivate(int x, int y, int z, String dimension, int radius) {
        return portals.removeIf(p -> p.dimension.equals(dimension) && distance(p.x, p.y, p.z, x, y, z) <= radius);
    }

    public static boolean isActivated(int x, int y, int z, String dimension, int radius) {
        for (ActivatedPortal p : portals) {
            if (p.dimension.equals(dimension) && distance(p.x, p.y, p.z, x, y, z) <= radius) {
                return true;
            }
        }
        return false;
    }

    public static List<ActivatedPortal> getPortals() {
        return List.copyOf(portals);
    }

    private static double distance(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static class ActivatedPortal {
        public int x;
        public int y;
        public int z;
        public String dimension;
        public String activated_by;

        public ActivatedPortal(int x, int y, int z, String dimension, String activated_by) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.activated_by = activated_by;
        }
    }
}
