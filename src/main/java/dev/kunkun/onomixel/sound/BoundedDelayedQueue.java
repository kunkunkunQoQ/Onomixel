package dev.kunkun.onomixel.sound;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Client-thread bounded queue ordered by active-time due timestamp. */
public final class BoundedDelayedQueue<T> {
    private record Entry<T>(long dueNanos, long sequence, T value) {}

    private final int capacity;
    private final PriorityQueue<Entry<T>> entries = new PriorityQueue<>(Comparator
            .<Entry<T>>comparingLong(Entry::dueNanos).thenComparingLong(Entry::sequence));
    private long sequence;

    public BoundedDelayedQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Rejects the newest value when capacity is reached. */
    public boolean offer(long dueNanos, T value) {
        if (value == null) throw new NullPointerException("value");
        if (entries.size() >= capacity) return false;
        entries.add(new Entry<>(dueNanos, sequence++, value));
        return true;
    }

    /** Removes due values in timestamp order, or all values when flush is true. */
    public int deliver(long nowNanos, int budget, boolean flush, Predicate<? super T> allowed,
                       Consumer<? super T> consumer) {
        if (budget <= 0) return 0;
        int delivered = 0;
        while (delivered < budget && !entries.isEmpty()) {
            Entry<T> next = entries.peek();
            if (!flush && next.dueNanos() > nowNanos) break;
            entries.remove();
            if (allowed.test(next.value())) {
                consumer.accept(next.value());
                delivered++;
            }
        }
        return delivered;
    }

    public void removeIf(Predicate<? super T> predicate) {
        entries.removeIf(entry -> predicate.test(entry.value()));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
