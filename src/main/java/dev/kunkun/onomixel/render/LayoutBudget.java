package dev.kunkun.onomixel.render;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounds preparation and rays separately; solving can never spend final visibility checks. */
public final class LayoutBudget {
    public record Candidate(long id, boolean previouslyDrawn) {}
    public record Counts(int preparations, int sourceRays, int searchRays, int finalRays) {}
    private final int preparationLimit;
    private final int searchLimit;
    private final int finalLimit;
    private int preparations, sourceRays, searchRays, finalRays;

    public LayoutBudget(int displayLimit) {
        preparationLimit = Math.clamp(displayLimit * 2, 16, 64);
        searchLimit = LayoutScheduler.budget(preparationLimit, displayLimit) * 9 * 6;
        finalLimit = preparationLimit * 7;
    }

    public int preparationLimit() { return preparationLimit; }
    public int searchLimit() { return searchLimit; }
    public int finalLimit() { return finalLimit; }

    /** Input already carries semantic priority/fairness; keep drawn captions and rotate a spare share. */
    public Set<Long> shortlist(List<Candidate> candidates, int displayLimit, long frame) {
        Set<Long> selected = new LinkedHashSet<>();
        int preferred = Math.min(displayLimit, preparationLimit - 2);
        for (Candidate candidate : candidates) {
            if (selected.size() >= preferred) break;
            selected.add(candidate.id());
        }
        for (Candidate candidate : candidates) {
            if (selected.size() >= preparationLimit - 1) break;
            if (candidate.previouslyDrawn()) selected.add(candidate.id());
        }
        List<Candidate> rest = new ArrayList<>();
        for (Candidate candidate : candidates) if (!selected.contains(candidate.id())) rest.add(candidate);
        if (!rest.isEmpty()) {
            int start = (int) Math.floorMod(frame, (long) rest.size());
            for (int i = 0; i < rest.size() && selected.size() < preparationLimit; i++)
                selected.add(rest.get((start + i) % rest.size()).id());
        }
        return selected;
    }

    public boolean prepare() {
        if (preparations >= preparationLimit) return false;
        preparations++;
        return true;
    }
    public boolean sourceRay() {
        if (sourceRays >= preparationLimit) return false;
        sourceRays++;
        return true;
    }
    public boolean searchRays(int count) {
        if (count < 0 || searchRays + count > searchLimit) return false;
        searchRays += count;
        return true;
    }
    public boolean finalRays(int count) {
        if (count < 0 || finalRays + count > finalLimit) return false;
        finalRays += count;
        return true;
    }
    public Counts counts() { return new Counts(preparations, sourceRays, searchRays, finalRays); }
}
