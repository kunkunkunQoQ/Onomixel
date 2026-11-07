package dev.kunkun.onomixel.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Only visible sources request expensive searches; following an existing slot is cheap and per-frame. */
public final class LayoutScheduler {
    public static final long INTERVAL_NANOS = 100_000_000L;
    public record Work(long id, long lastSolveNanos, boolean visible, boolean preferred, boolean initialized) {}

    private static final Comparator<Work> OLDEST_COMPARATOR = (a, b) -> {
        int c = Boolean.compare(a.initialized, b.initialized);
        return c != 0 ? c : Long.compare(a.lastSolveNanos, b.lastSolveNanos);
    };

    private LayoutScheduler() {}

    public static int budget(int visibleCount, int displayLimit) {
        return Math.clamp((Math.min(visibleCount, displayLimit) + 3) / 4, 4, 12);
    }

    public static Set<Long> select(List<Work> work, int displayLimit, long now) {
        if (work == null || work.isEmpty()) return Set.of();
        int visibleCount = 0;
        List<Work> due = new ArrayList<>(work.size());
        for (int i = 0; i < work.size(); i++) {
            Work w = work.get(i);
            if (w != null && w.visible) {
                visibleCount++;
                if (now - w.lastSolveNanos >= INTERVAL_NANOS) {
                    due.add(w);
                }
            }
        }
        if (due.isEmpty()) return Set.of();

        int budget = budget(visibleCount, displayLimit);
        due.sort(OLDEST_COMPARATOR);

        Set<Long> selected = new LinkedHashSet<>(Math.min(due.size(), budget) * 2);
        int preferredLimit = Math.max(0, budget - 1);
        // Leave one fair ticket for the rest of the visible scene when the desired captions are busy.
        for (int i = 0; i < due.size() && selected.size() < preferredLimit; i++) {
            Work w = due.get(i);
            if (w.preferred) {
                selected.add(w.id);
            }
        }
        for (int i = 0; i < due.size() && selected.size() < budget; i++) {
            Work w = due.get(i);
            selected.add(w.id);
        }
        return Collections.unmodifiableSet(selected);
    }
}
