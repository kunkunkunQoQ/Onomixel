package dev.kunkun.onomixel.render;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Only visible sources request expensive searches; following an existing slot is cheap and per-frame. */
public final class LayoutScheduler {
    public static final long INTERVAL_NANOS = 100_000_000L;
    public record Work(long id, long lastSolveNanos, boolean visible, boolean preferred, boolean initialized) {}

    private LayoutScheduler() {}

    public static int budget(int visibleCount, int displayLimit) {
        return Math.clamp((Math.min(visibleCount, displayLimit) + 3) / 4, 4, 12);
    }

    public static Set<Long> select(List<Work> work, int displayLimit, long now) {
        List<Work> due = work.stream().filter(w -> w.visible && now - w.lastSolveNanos >= INTERVAL_NANOS).toList();
        int budget = budget((int) work.stream().filter(Work::visible).count(), displayLimit);
        Set<Long> selected = new LinkedHashSet<>();
        Comparator<Work> oldest = Comparator.comparing(Work::initialized).thenComparingLong(Work::lastSolveNanos);
        // Leave one fair ticket for the rest of the visible scene when the desired captions are busy.
        due.stream().filter(Work::preferred).sorted(oldest).limit(budget - 1L).forEach(w -> selected.add(w.id));
        due.stream().sorted(oldest).filter(w -> !selected.contains(w.id)).limit(budget - selected.size())
                .forEach(w -> selected.add(w.id));
        return Set.copyOf(selected);
    }
}
