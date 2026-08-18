package com.example.portallock;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;

public class PortalLockMod implements ModInitializer {
    @Override
    public void onInitialize() {
        PortalLockConfig.load();
        PortalLockLang.load();
        PortalLockActivationState.load();
        registerCommands();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerPortalLockCommand(dispatcher));
    }

    private void registerPortalLockCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pl")
                .requires(PortalLockMod::canUseReload)
                .then(Commands.literal("reload")
                        .executes(context -> {
                            PortalLockConfig.load();
                            PortalLockLang.load();
                            PortalLockActivationState.load();
                            sendReloadMessage(context.getSource());
                            return 1;
                        }))
                .then(Commands.literal("activate")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            BlockPos pos = player.blockPosition();
                            String dimKey = player.level().dimension().location().toString();
                            String name = player.getName().getString();
                            boolean added = PortalLockActivationState.activate(pos.getX(), pos.getY(), pos.getZ(), dimKey, name);
                            if (added) {
                                context.getSource().sendSuccess(() -> Component.literal("[PortalLock] Portal activated at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
                            } else {
                                context.getSource().sendSuccess(() -> Component.literal("[PortalLock] Portal already activated at this location, updated author."), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("deactivate")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            BlockPos pos = player.blockPosition();
                            String dimKey = player.level().dimension().location().toString();
                            boolean removed = PortalLockActivationState.deactivate(pos.getX(), pos.getY(), pos.getZ(), dimKey, PortalLockConfig.DATA.activation_radius);
                            if (removed) {
                                context.getSource().sendSuccess(() -> Component.literal("[PortalLock] Portal deactivated near " + pos.getX() + " " + pos.getY() + " " + pos.getZ()), false);
                            } else {
                                context.getSource().sendSuccess(() -> Component.literal("[PortalLock] No activated portal found nearby."), false);
                            }
                            return 1;
                        }))
                .then(Commands.literal("list")
                        .executes(context -> {
                            var portals = PortalLockActivationState.getPortals();
                            if (portals.isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal("[PortalLock] No activated portals."), false);
                            } else {
                                context.getSource().sendSuccess(() -> Component.literal("[PortalLock] Activated portals (" + portals.size() + "):"), false);
                                for (PortalLockActivationState.ActivatedPortal p : portals) {
                                    String line = "  " + p.x + " " + p.y + " " + p.z + " [" + p.dimension + "] by " + p.activated_by;
                                    context.getSource().sendSuccess(() -> Component.literal(line), false);
                                }
                            }
                            return 1;
                        })));
    }


    private static boolean canUseReload(CommandSourceStack source) {
        try {
            Object result = source.getClass().getMethod("hasPermission", int.class).invoke(source, 2);
            if (result instanceof Boolean b) return b;
        } catch (Throwable ignored) {
        }
        try {
            Object result = source.getClass().getMethod("hasPermissionLevel", int.class).invoke(source, 2);
            if (result instanceof Boolean b) return b;
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void sendReloadMessage(CommandSourceStack source) {
        try {
            source.sendSuccess(() -> Component.literal("[PortalLock] Reloaded config and language files."), false);
        } catch (Throwable ignored) {
            System.out.println("[PortalLock] Reloaded config and language files.");
        }
    }
}
