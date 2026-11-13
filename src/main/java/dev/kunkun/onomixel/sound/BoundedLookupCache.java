package dev.kunkun.onomixel.sound;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, zero-GC-on-hit bounded lookup cache backed by {@link ConcurrentHashMap}.
 * Uses {@link #NULL_SENTINEL} so callers can distinguish negative-cached nulls from misses.
 */
public final class BoundedLookupCache<K, V> {
    public static final Object NULL_SENTINEL = new Object();

    private final ConcurrentHashMap<K, Object> entries;
    private final int maxSize;
    private final int evictBatch;
    private final Object evictLock = new Object();

    public BoundedLookupCache(int maxSize, int evictBatch) {
        this(Math.min(maxSize, 256), maxSize, evictBatch);
    }

    public BoundedLookupCache(int initialCapacity, int maxSize, int evictBatch) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        if (evictBatch < 1) throw new IllegalArgumentException("evictBatch must be >= 1");
        this.entries = new ConcurrentHashMap<>(Math.max(16, initialCapacity));
        this.maxSize = maxSize;
        this.evictBatch = Math.min(maxSize, evictBatch);
    }

    /** Returns non-null cached value, or null on cache miss (or if {@link #NULL_SENTINEL} was stored). */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        if (key == null) return null;
        Object raw = entries.get(key);
        return (raw == null || raw == NULL_SENTINEL) ? null : (V) raw;
    }

    /** Returns raw stored entry (possibly {@link #NULL_SENTINEL}), or null on miss. */
    public Object getRaw(K key) {
        return key == null ? null : entries.get(key);
    }

    /** Inserts a non-null key/value pair, evicting a bounded batch when at capacity. */
    public void put(K key, V value) {
        if (key == null || value == null) return;
        ensureCapacity(key);
        entries.put(key, value);
    }

    /** Inserts a non-null key with a nullable value (stored as {@link #NULL_SENTINEL} when null). */
    public void putNullable(K key, V value) {
        if (key == null) return;
        ensureCapacity(key);
        entries.put(key, value == null ? NULL_SENTINEL : value);
    }

    /** Inserts if absent without overwriting a concurrent writer's value; returns stored value. */
    @SuppressWarnings("unchecked")
    public V putIfAbsent(K key, V value) {
        if (key == null || value == null) return value;
        ensureCapacity(key);
        Object existing = entries.putIfAbsent(key, value);
        return (existing == null || existing == NULL_SENTINEL) ? value : (V) existing;
    }

    public boolean containsKey(K key) {
        return key != null && entries.containsKey(key);
    }

    public void remove(K key) {
        if (key != null) {
            entries.remove(key);
        }
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private void ensureCapacity(K incomingKey) {
        if (entries.size() >= maxSize && !entries.containsKey(incomingKey)) {
            synchronized (evictLock) {
                if (entries.size() >= maxSize && !entries.containsKey(incomingKey)) {
                    Iterator<K> it = entries.keySet().iterator();
                    for (int evicted = 0; evicted < evictBatch && it.hasNext(); evicted++) {
                        it.next();
                        it.remove();
                    }
                }
            }
        }
    }
}
