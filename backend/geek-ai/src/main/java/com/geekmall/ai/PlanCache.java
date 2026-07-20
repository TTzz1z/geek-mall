package com.geekmall.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase D: short-TTL cache for identical planner utterances (message + scene + non-runtime slots).
 */
@Component
public class PlanCache {
    private final boolean enabled;
    private final long ttlMs;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();

    public PlanCache(@Value("${geek.llm.plan-cache-enabled:true}") boolean enabled,
                     @Value("${geek.llm.plan-cache-ttl-ms:60000}") long ttlMs) {
        this.enabled = enabled;
        this.ttlMs = Math.max(5_000L, Math.min(600_000L, ttlMs));
    }

    public boolean enabled() {
        return enabled;
    }

    public long ttlMs() {
        return ttlMs;
    }

    public Optional<PlanEnvelope> get(String message, String sceneHint, Map<String, Object> slots) {
        if (!enabled) return Optional.empty();
        evictExpired();
        String key = cacheKey(message, sceneHint, slots);
        Entry e = cache.get(key);
        if (e == null || e.expiresAtMs < System.currentTimeMillis()) {
            if (e != null) cache.remove(key, e);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(e.plan);
    }

    public void put(String message, String sceneHint, Map<String, Object> slots, PlanEnvelope plan) {
        if (!enabled || plan == null) return;
        String key = cacheKey(message, sceneHint, slots);
        cache.put(key, new Entry(plan, System.currentTimeMillis() + ttlMs));
        puts.incrementAndGet();
    }

    public Map<String, Object> stats() {
        evictExpired();
        return Map.of(
                "enabled", enabled,
                "ttlMs", ttlMs,
                "size", cache.size(),
                "hits", hits.get(),
                "misses", misses.get(),
                "puts", puts.get()
        );
    }

    static String cacheKey(String message, String sceneHint, Map<String, Object> slots) {
        StringBuilder sb = new StringBuilder();
        sb.append(normalize(message)).append('|');
        sb.append(sceneHint == null || sceneHint.isBlank() ? "auto" : sceneHint.trim().toUpperCase()).append('|');
        if (slots != null) {
            appendSlot(sb, slots, "budget");
            appendSlot(sb, slots, "budget_mode");
            appendSlot(sb, slots, "budgetMode");
            appendSlot(sb, slots, "categoryId");
            appendSlot(sb, slots, "use_case");
            // Multi-turn: same utterance with different memory must not collide
            appendSlot(sb, slots, ConversationSlots.LAST_SEARCH_SPU_IDS);
            appendSlot(sb, slots, ConversationSlots.LAST_SKU_ID);
            appendSlot(sb, slots, ConversationSlots.LAST_COMPARE_SPU_IDS);
        }
        return sha256(sb.toString());
    }

    private static void appendSlot(StringBuilder sb, Map<String, Object> slots, String key) {
        Object v = slots.get(key);
        if (v == null) return;
        sb.append(key).append('=').append(v).append(';');
    }

    private static String normalize(String message) {
        if (message == null) return "";
        return message.trim().replaceAll("\\s+", " ");
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getValue().expiresAtMs < now) it.remove();
        }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private record Entry(PlanEnvelope plan, long expiresAtMs) {}
}
