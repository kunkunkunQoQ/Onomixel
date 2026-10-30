package dev.kunkun.onomixel.sound;

import java.util.IdentityHashMap;
import java.util.concurrent.ArrayBlockingQueue;

/** Main-thread request identities plus a bounded audio-thread mailbox. No game objects are dereferenced here. */
public final class PlaybackMailbox<T, P> {
    public static final int REQUEST_LIMIT = 256;
    public static final int CONFIRMATION_LIMIT = 128;
    public static final long MAX_DELAY_NANOS = 2_000_000_000L;
    private final IdentityHashMap<T, Request<P>> requests = new IdentityHashMap<>();
    private final ArrayBlockingQueue<Confirmation<T>> confirmations = new ArrayBlockingQueue<>(CONFIRMATION_LIMIT);

    private record Request<P>(Object world, P payload, long wallNanos) {}
    public record Confirmation<T>(T identity, long wallNanos) {}

    /** Call only on the client thread. */
    public void request(T identity, Object world, P payload, long wallNanos) {
        expire(wallNanos);
        if (requests.size() < REQUEST_LIMIT || requests.containsKey(identity)) {
            requests.put(identity, new Request<>(world, payload, wallNanos));
        }
    }

    /** Safe to call from the audio thread; never schedules unbounded client runnables. */
    public boolean confirm(T identity, long wallNanos) {
        return confirmations.offer(new Confirmation<>(identity, wallNanos));
    }

    public Confirmation<T> poll() { return confirmations.poll(); }

    /** Call only on the client thread. A confirmation consumes its request exactly once. */
    public P consume(Confirmation<T> confirmation, Object currentWorld, long wallNanos) {
        Request<P> request = requests.get(confirmation.identity);
        // A delayed confirmation for an older play must not delete a newer request using the same identity.
        if (request == null || confirmation.wallNanos < request.wallNanos) return null;
        requests.remove(confirmation.identity);
        if (request.world != currentWorld
                || wallNanos - request.wallNanos > MAX_DELAY_NANOS
                || wallNanos - confirmation.wallNanos > MAX_DELAY_NANOS) return null;
        return request.payload;
    }

    public void expire(long wallNanos) {
        requests.values().removeIf(request -> wallNanos - request.wallNanos > MAX_DELAY_NANOS);
    }

    public void clear() {
        requests.clear();
        confirmations.clear();
    }

    public int requestCount() { return requests.size(); }
    public int confirmationCount() { return confirmations.size(); }
}
