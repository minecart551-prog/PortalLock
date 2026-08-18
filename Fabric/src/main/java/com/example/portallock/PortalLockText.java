package com.example.portallock;

import java.util.List;

public final class PortalLockText {
    private PortalLockText() {}
    public static void sendBlockedDenied(Object player, String blockedItemId, boolean playSound) { ReflectPortalLogic.sendBlockedDenied(player, blockedItemId, playSound); }
    public static String hasBlockedItem(Object player, List<String> blockedIds) { return ReflectPortalLogic.hasBlockedItem((net.minecraft.server.level.ServerPlayer) player, blockedIds); }
    public static Object getItemOrAir(String id) { return ReflectPortalLogic.getItemOrAir(id); }
    public static void playConfiguredSound(Object player, String soundId) { ReflectPortalLogic.playConfiguredSound(player, soundId); }
}
