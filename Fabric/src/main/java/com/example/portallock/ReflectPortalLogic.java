package com.example.portallock;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ReflectPortalLogic {
    private static final Map<UUID, Object> LAST_DIM = new HashMap<>();
    // Portal cancellation must run every tick, but message/sound must not spam.
    // In fix9 this was tied to player.tickCount and a "left portal" detector.
    // On 26.x the block collision callback and player tick order can make that
    // detector clear the notice state before the next portal callback, which
    // causes the denial sound/message to fire again every tick.
    // Use a monotonic wall-clock cooldown instead: cancellation is still every
    // callback, while the visible/audible notice is capped regardless of callback
    // order. This also lets the actionbar recover after client language changes.
    private static final long NOTICE_COOLDOWN_MS = 1000L;
    private static final Map<UUID, Long> END_NOTICE_AT = new HashMap<>();
    private static final Map<UUID, Long> NETHER_NOTICE_AT = new HashMap<>();
    // Sound must match the 1.21.x behavior: once while continuously touching the portal.
    // ActionBar may repeat slowly for language/client refresh, but the fail sound must not.
    private static final java.util.Set<UUID> END_FAIL_SOUND_SENT = new java.util.HashSet<>();
    private static final java.util.Set<UUID> NETHER_FAIL_SOUND_SENT = new java.util.HashSet<>();
    // Last actual portal-collision callback time.
    // Do not clear the one-shot sound flag from a bounding-box scan in player tick,
    // because on 26.x the order can be: player tick says "not touching", then
    // the block collision callback still fires at the edge of the portal. That was
    // the cause of the extra denial sound when leaving the portal.
    private static final long CONTACT_RESET_MS = 500L;
    private static final Map<UUID, Long> END_LAST_CONTACT_AT = new HashMap<>();
    private static final Map<UUID, Long> NETHER_LAST_CONTACT_AT = new HashMap<>();
    // Set just before/during Nether -> Overworld return so the arrival portal does
    // not immediately play the denial message/sound once when the player lands in
    // the destination portal without the required item.  Cleared when the player
    // leaves the portal or the short grace time expires.
    private static final long NETHER_RETURN_SILENT_MS = 3000L;
    private static final Map<UUID, Long> NETHER_RETURN_SILENT_UNTIL = new HashMap<>();
    private static final Map<UUID, Integer> NETHER_RETURN_MISS_TICKS = new HashMap<>();

    private ReflectPortalLogic() {}

    public static void tick(Object self) {
        if (!(self instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();
        Object current = player.level().dimension();
        Object last = LAST_DIM.get(id);

        clearContactStateIfExpired(id, END_LAST_CONTACT_AT, END_FAIL_SOUND_SENT, END_NOTICE_AT);
        clearContactStateIfExpired(id, NETHER_LAST_CONTACT_AT, NETHER_FAIL_SOUND_SENT, NETHER_NOTICE_AT);

        int contactType = getPortalContactType(player);
        if (contactType != PORTAL_END) {
            PortalLockEndState.clearFailNotice(id);
        }
        if (current == Level.OVERWORLD && NETHER_RETURN_SILENT_UNTIL.containsKey(id)) {
            if (contactType == PORTAL_NETHER) {
                NETHER_RETURN_MISS_TICKS.remove(id);
            } else {
                int misses = NETHER_RETURN_MISS_TICKS.getOrDefault(id, 0) + 1;
                if (misses >= 4) {
                    clearNetherReturnSilent(id);
                } else {
                    NETHER_RETURN_MISS_TICKS.put(id, misses);
                }
            }
        }

        if (current == Level.OVERWORLD && last == Level.END) {
            END_FAIL_SOUND_SENT.remove(id);
        }
        if (current == Level.OVERWORLD && last == Level.NETHER) {
            NETHER_FAIL_SOUND_SENT.remove(id);
            markNetherReturnSilent(id);
        }
        LAST_DIM.put(id, current);
    }

    public static void onNetherPortalInside(Object levelObj, Object posObj, Object entityObj, CallbackInfo ci) {
        if (!(levelObj instanceof Level level) || level.isClientSide()) return;

        if (entityObj instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) {
            if (isBlockedItemEntity(itemEntity)) {
                ci.cancel();
                return;
            }
        }

        ServerPlayer player = findPlayer(entityObj);
        if (player == null) return;
        if (player.level().dimension() != Level.OVERWORLD || !PortalLockConfig.DATA.nether_enabled) return;

        UUID id = player.getUUID();
        markContact(NETHER_LAST_CONTACT_AT, id);
        if (LAST_DIM.get(id) == Level.NETHER) {
            markNetherReturnSilent(id);
        }
        if (isNetherReturnSilent(id)) {
            markNetherReturnSilent(id);
            NETHER_RETURN_MISS_TICKS.remove(id);
            ci.cancel();
            return;
        }

        String blocked = hasBlockedItem(player, PortalLockConfig.DATA.blocked_items);
        if (blocked != null) {
            boolean showMessage = shouldNotifyWithCooldown(NETHER_NOTICE_AT, id);
            boolean playSound = NETHER_FAIL_SOUND_SENT.add(id);
            if (showMessage || playSound) sendBlockedDenied(player, blocked, playSound);
            ci.cancel();
            return;
        }
        NETHER_FAIL_SOUND_SENT.remove(id);
        NETHER_NOTICE_AT.remove(id);
        NETHER_LAST_CONTACT_AT.remove(id);
    }

    public static void onEndPortalInside(Object levelObj, Object posObj, Object entityObj, CallbackInfo ci) {
        if (!(levelObj instanceof Level level) || level.isClientSide()) return;

        if (entityObj instanceof net.minecraft.world.entity.item.ItemEntity itemEntity) {
            if (isBlockedItemEntity(itemEntity)) {
                ci.cancel();
                return;
            }
        }

        ServerPlayer player = findPlayer(entityObj);
        if (player == null) return;
        if (player.level().dimension() != Level.OVERWORLD || !PortalLockConfig.DATA.end_enabled) return;

        UUID id = player.getUUID();
        markContact(END_LAST_CONTACT_AT, id);

        String blocked = hasBlockedItem(player, PortalLockConfig.DATA.blocked_items);
        if (blocked != null) {
            if (PortalLockEndState.markFailNotice(id)) {
                sendBlockedDenied(player, blocked, true);
            }
            ci.cancel();
            return;
        }
        PortalLockEndState.clearFailNotice(id);
        END_FAIL_SOUND_SENT.remove(id);
        END_NOTICE_AT.remove(id);
        END_LAST_CONTACT_AT.remove(id);
    }

    private static ServerPlayer findPlayer(Object entityObj) {
        if (entityObj instanceof ServerPlayer p) return p;
        if (entityObj instanceof Entity e) {
            for (Entity passenger : e.getPassengers()) {
                if (passenger instanceof ServerPlayer p) return p;
            }
        }
        return null;
    }

    private static boolean shouldNotifyWithCooldown(Map<UUID, Long> noticeMap, UUID id) {
        long now = System.currentTimeMillis();
        Long last = noticeMap.get(id);
        if (last != null && now - last < NOTICE_COOLDOWN_MS) return false;
        noticeMap.put(id, now);
        return true;
    }

    private static void markContact(Map<UUID, Long> contactMap, UUID id) {
        contactMap.put(id, System.currentTimeMillis());
    }

    private static void clearContactStateIfExpired(UUID id, Map<UUID, Long> contactMap, java.util.Set<UUID> soundSet, Map<UUID, Long> noticeMap) {
        Long last = contactMap.get(id);
        if (last == null) return;
        long now = System.currentTimeMillis();
        if (now - last <= CONTACT_RESET_MS) return;
        contactMap.remove(id);
        soundSet.remove(id);
        noticeMap.remove(id);
    }

    private static void markNetherReturnSilent(UUID id) {
        NETHER_RETURN_SILENT_UNTIL.put(id, System.currentTimeMillis() + NETHER_RETURN_SILENT_MS);
        NETHER_RETURN_MISS_TICKS.remove(id);
    }

    private static void clearNetherReturnSilent(UUID id) {
        NETHER_RETURN_SILENT_UNTIL.remove(id);
        NETHER_RETURN_MISS_TICKS.remove(id);
    }

    private static boolean isNetherReturnSilent(UUID id) {
        Long until = NETHER_RETURN_SILENT_UNTIL.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            clearNetherReturnSilent(id);
            return false;
        }
        return true;
    }

    private static final int PORTAL_NONE = 0;
    private static final int PORTAL_NETHER = 1;
    private static final int PORTAL_END = 2;

    private static int getPortalContactType(ServerPlayer player) {
        Entity target = player.getVehicle() != null ? player.getVehicle() : player;
        try {
            Level level = player.level();
            var box = target.getBoundingBox().inflate(0.10D);
            int minX = (int)Math.floor(box.minX);
            int maxX = (int)Math.ceil(box.maxX) - 1;
            int minY = (int)Math.floor(box.minY);
            int maxY = (int)Math.ceil(box.maxY) - 1;
            int minZ = (int)Math.floor(box.minZ);
            int maxZ = (int)Math.ceil(box.maxZ) - 1;
            boolean nether = false;
            boolean end = false;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockState state = level.getBlockState(new BlockPos(x, y, z));
                        if (state.is(Blocks.NETHER_PORTAL)) nether = true;
                        if (state.is(Blocks.END_PORTAL)) end = true;
                    }
                }
            }
            if (end) return PORTAL_END;
            if (nether) return PORTAL_NETHER;
        } catch (Throwable ignored) {}
        return PORTAL_NONE;
    }

    public static int countItem(Object playerObj, Object itemObj) {
        if (!(playerObj instanceof ServerPlayer player) || !(itemObj instanceof Item item) || item == Items.AIR) return 0;
        Container inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    public static String hasBlockedItem(ServerPlayer player, java.util.List<String> blockedIds) {
        if (blockedIds == null || blockedIds.isEmpty()) return null;
        Container inv = player.getInventory();
        for (String blockedId : blockedIds) {
            Item blocked = getItemOrAir(blockedId);
            if (blocked == Items.AIR) continue;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(blocked)) return blockedId;
            }
        }
        return null;
    }

    private static boolean isBlockedItemEntity(net.minecraft.world.entity.item.ItemEntity itemEntity) {
        java.util.List<String> blockedIds = PortalLockConfig.DATA.blocked_items;
        if (blockedIds == null || blockedIds.isEmpty()) return false;
        Item item = itemEntity.getItem().getItem();
        for (String blockedId : blockedIds) {
            Item blocked = getItemOrAir(blockedId);
            if (blocked != Items.AIR && item == blocked) return true;
        }
        return false;
    }

    public static Item getItemOrAir(String id) {
        String normalized = normalizeItemId(id);
        try {
            ResourceLocation loc = new ResourceLocation(normalized);
            return BuiltInRegistries.ITEM.get(loc);
        } catch (Throwable e) {
            return Items.AIR;
        }
    }

    private static String normalizeItemId(String id) {
        if (id == null || id.isBlank()) return "minecraft:air";
        String trimmed = id.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }


    public static void sendBlockedDenied(Object player, String blockedItemId, boolean playSound) {
        if (!(player instanceof ServerPlayer p)) return;
        String template = PortalLockLang.getMessageTemplate(p, "blocked-denied", PortalLockConfig.DATA.blocked_message,
                "&cYou cannot enter while carrying &e%item%&c!");
        Component message = buildMessageComponent(template, blockedItemId);
        sendMessage(p, message, PortalLockConfig.DATA.blocked_overlay);
        if (playSound) playConfiguredSound(p, PortalLockConfig.DATA.blocked_fail_sound);
    }

    private static void sendMessage(ServerPlayer player, Component component, boolean overlay) {
        if (player == null || component == null) return;
        try {
            if (overlay) {
                Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket");
                Object packet = packetClass.getConstructor(Component.class).newInstance(component);
                Object connection = player.connection;
                callAny(connection, new String[]{"send"}, packet);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            callAny(player, new String[]{"sendSystemMessage"}, component);
        } catch (Throwable ignored) {}
    }

    private static MutableComponent buildMessageComponent(String template, String itemId) {
        String safeTemplate = template == null ? "" : template;
        MutableComponent result = Component.literal("");
        ChatFormatting current = ChatFormatting.WHITE;
        StringBuilder literal = new StringBuilder();

        for (int i = 0; i < safeTemplate.length(); ) {
            if (safeTemplate.startsWith("%item_name%", i)) {
                append(result, literal, current);
                result.append(styleComponent(itemDisplayComponent(itemId), current));
                i += "%item_name%".length();
                continue;
            }
            if (safeTemplate.startsWith("%item_id%", i)) {
                append(result, literal, current);
                result.append(Component.literal(normalizeItemId(itemId)).withStyle(current));
                i += "%item_id%".length();
                continue;
            }
            if (safeTemplate.startsWith("%item%", i)) {
                append(result, literal, current);
                result.append(styleComponent(itemDisplayComponent(itemId), current));
                i += "%item%".length();
                continue;
            }

            char ch = safeTemplate.charAt(i);
            if ((ch == '&' || ch == '\u00a7') && i + 1 < safeTemplate.length()) {
                ChatFormatting next = fromLegacyCode(safeTemplate.charAt(i + 1));
                if (next != null) {
                    append(result, literal, current);
                    current = next == ChatFormatting.RESET ? ChatFormatting.WHITE : next;
                    i += 2;
                    continue;
                }
            }
            literal.append(ch);
            i++;
        }
        append(result, literal, current);
        return result;
    }

    private static MutableComponent styleComponent(MutableComponent component, ChatFormatting style) {
        if (component == null) return Component.literal("");
        return component.withStyle(style);
    }

    private static void append(MutableComponent result, StringBuilder literal, ChatFormatting style) {
        if (literal.length() == 0) return;
        result.append(Component.literal(literal.toString()).withStyle(style));
        literal.setLength(0);
    }

    private static ChatFormatting fromLegacyCode(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> ChatFormatting.BLACK;
            case '1' -> ChatFormatting.DARK_BLUE;
            case '2' -> ChatFormatting.DARK_GREEN;
            case '3' -> ChatFormatting.DARK_AQUA;
            case '4' -> ChatFormatting.DARK_RED;
            case '5' -> ChatFormatting.DARK_PURPLE;
            case '6' -> ChatFormatting.GOLD;
            case '7' -> ChatFormatting.GRAY;
            case '8' -> ChatFormatting.DARK_GRAY;
            case '9' -> ChatFormatting.BLUE;
            case 'a' -> ChatFormatting.GREEN;
            case 'b' -> ChatFormatting.AQUA;
            case 'c' -> ChatFormatting.RED;
            case 'd' -> ChatFormatting.LIGHT_PURPLE;
            case 'e' -> ChatFormatting.YELLOW;
            case 'f' -> ChatFormatting.WHITE;
            case 'r' -> ChatFormatting.RESET;
            default -> null;
        };
    }

    private static MutableComponent itemDisplayComponent(String itemId) {
        Item item = getItemOrAir(itemId);
        if (item == Items.AIR) return Component.literal(itemPrettyName(itemId));
        String key = itemTranslationKey(item);
        return key == null || key.isBlank() ? Component.literal(itemPrettyName(itemId)) : Component.translatable(key);
    }

    private static String itemTranslationKey(Item item) {
        try {
            Object value = callAny(item, new String[]{"getDescriptionId", "getTranslationKey"});
            return value instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String itemPrettyName(String itemId) {
        String normalized = normalizeItemId(itemId);
        String path = normalized.substring(normalized.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.length() > 1 ? part.substring(1) : "");
        }
        return sb.length() == 0 ? normalized : sb.toString();
    }

    public static void playConfiguredSound(Object playerObj, String soundId) {
        if (!(playerObj instanceof ServerPlayer player)) return;
        try {
            SoundEvent sound = resolveSound(soundId);
            player.level().playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, PortalLockConfig.DATA.volume, PortalLockConfig.DATA.pitch);
        } catch (Throwable ignored) {}
    }

    private static SoundEvent resolveSound(String soundId) {
        String id = soundId == null ? "" : soundId.trim().toLowerCase(Locale.ROOT);
        if (id.contains("enderman.teleport") || id.contains("enderman_teleport")) return SoundEvents.ENDERMAN_TELEPORT;
        if (id.contains("anvil.place") || id.contains("anvil_place")) return SoundEvents.ANVIL_PLACE;
        if (id.contains("villager.no") || id.contains("villager_no")) return SoundEvents.VILLAGER_NO;
        return SoundEvents.ANVIL_PLACE;
    }

    private static Object callAny(Object target, String[] names, Object... args) throws Exception {
        Exception last = null;
        for (String n : names) {
            try { return callOnClass(target.getClass(), target, n, args); }
            catch (Exception e) { last = e; }
        }
        throw last == null ? new NoSuchMethodException() : last;
    }

    private static Object callOnClass(Class<?> c, Object target, String name, Object... args) throws Exception {
        Class<?> search = c;
        while (search != null) {
            for (Method m : search.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] types = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < types.length; i++) {
                    if (args[i] == null) continue;
                    Class<?> at = args[i].getClass();
                    if (types[i].isPrimitive()) {
                        if ((types[i] == int.class && at == Integer.class) ||
                                (types[i] == boolean.class && at == Boolean.class) ||
                                (types[i] == float.class && at == Float.class) ||
                                (types[i] == double.class && at == Double.class)) continue;
                    }
                    if (!types[i].isAssignableFrom(at)) { ok = false; break; }
                }
                if (!ok) continue;
                m.setAccessible(true);
                return m.invoke(target, args);
            }
            search = search.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }
}
