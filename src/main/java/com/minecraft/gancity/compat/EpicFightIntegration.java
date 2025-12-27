package com.minecraft.gancity.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Epic Fight integration (soft dependency).
 *
 * Uses reflection to avoid a hard dependency on Epic Fight at compile time.
 *
 * Currently used to enrich AI perception with Epic Fight combat-mode and stamina/charging signals.
 */
public final class EpicFightIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean initialized = false;
    private static volatile boolean available = false;

    // Reflection handles
    private static Class<?> epicFightCapabilitiesClass;
    private static Method getServerPlayerPatchMethod;

    // Cache (reflection is expensive; VisualPerception already has its own cache, but we keep a tiny one here too)
    private static final long CACHE_MS = 250;
    private static final ConcurrentHashMap<UUID, CachedCombatState> cache = new ConcurrentHashMap<>();

    private EpicFightIntegration() {}

    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!ModCompatibility.isEpicFightLoaded()) {
            available = false;
            return;
        }

        try {
            epicFightCapabilitiesClass = Class.forName("yesman.epicfight.world.capabilities.EpicFightCapabilities");
            getServerPlayerPatchMethod = epicFightCapabilitiesClass.getMethod("getServerPlayerPatch", Entity.class);

            available = true;
            LOGGER.info("Epic Fight integration initialized (reflection)");
        } catch (Throwable t) {
            available = false;
            LOGGER.warn("Epic Fight detected but integration init failed (reflection): {}", t.toString());
        }
    }

    public static boolean isAvailable() {
        // Lazy init: safe for early calls
        if (!initialized) init();
        return available;
    }

    public static CombatState getCombatState(Player player) {
        if (player == null) return null;
        if (!isAvailable()) return null;

        // Server-side only; Epic Fight warns about logical side usage.
        if (player.level() != null && player.level().isClientSide()) return null;

        UUID id = player.getUUID();
        long now = System.currentTimeMillis();

        CachedCombatState cached = cache.get(id);
        if (cached != null && (now - cached.timestampMs) < CACHE_MS) {
            return cached.state;
        }

        CombatState computed = computeCombatState(player);
        cache.put(id, new CachedCombatState(computed, now));
        return computed;
    }

    private static CombatState computeCombatState(Player player) {
        try {
            Object optPatchObj = getServerPlayerPatchMethod.invoke(null, player);
            if (!(optPatchObj instanceof Optional<?> optional)) {
                return null;
            }
            if (optional.isEmpty()) {
                return null;
            }

            Object patch = optional.get();
            if (patch == null) return null;

            boolean epicFightMode = invokeBoolean(patch, "isEpicFightMode");
            boolean holdingAny = invokeBoolean(patch, "isHoldingAny");

            float stamina = invokeFloat(patch, "getStamina");
            float maxStamina = invokeFloat(patch, "getMaxStamina");
            float staminaRatio = (maxStamina > 0.0001f) ? clamp01(stamina / maxStamina) : 0.0f;

            int chargingTicks = invokeInt(patch, "getSkillChargingTicks");
            float chargeRatio = clamp01(chargingTicks / 20.0f); // ~1s window normalized

            int ticksSinceLastAction = invokeInt(patch, "getTickSinceLastAction");

            return new CombatState(epicFightMode, holdingAny, staminaRatio, chargingTicks, chargeRatio, ticksSinceLastAction);
        } catch (Throwable t) {
            // Never break AI if Epic Fight changes API
            return null;
        }
    }

    private static boolean invokeBoolean(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int invokeInt(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Integer i) return i;
            if (v instanceof Number n) return n.intValue();
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static float invokeFloat(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Float f) return f;
            if (v instanceof Number n) return n.floatValue();
            return 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }

    private record CachedCombatState(CombatState state, long timestampMs) {}

    public record CombatState(
        boolean epicFightMode,
        boolean holdingAny,
        float staminaRatio,
        int chargingTicks,
        float chargeRatio,
        int ticksSinceLastAction
    ) {}
}
