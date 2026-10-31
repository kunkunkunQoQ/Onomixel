package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.OnomixelConfig;
import dev.kunkun.onomixel.sound.AcousticEnvironmentReactor;
import dev.kunkun.onomixel.sound.SoundEventPolicy;
import dev.kunkun.onomixel.sound.SoundSourceProfile;
import dev.kunkun.onomixel.sound.SpatialAnchorResolver;
import dev.kunkun.onomixel.sound.SoundDefinitions;
import dev.kunkun.onomixel.sound.SoundDefinition;
import dev.kunkun.onomixel.sound.SoundSemantics;
import dev.kunkun.onomixel.subtitle.BattlefieldClusterLOD;
import dev.kunkun.onomixel.subtitle.BossChoreographyDirector;
import dev.kunkun.onomixel.subtitle.CaptionSettings;
import dev.kunkun.onomixel.subtitle.CaptionPlacement;
import dev.kunkun.onomixel.subtitle.CaptionPlacement.Point;
import dev.kunkun.onomixel.subtitle.CaptionVisuals;
import dev.kunkun.onomixel.subtitle.KineticTypography;
import dev.kunkun.onomixel.subtitle.SmoothAnchor;
import dev.kunkun.onomixel.subtitle.StreamingCaptionSession;
import dev.kunkun.onomixel.subtitle.SubtitlePool;
import dev.kunkun.onomixel.subtitle.VisualType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Client-thread layout: visible-only searches, per-frame camera-relative targets, bounded world motion. */
public final class DynamicSubtitleLayout {
    private final Map<Long, State> states = new HashMap<>();
    private final CaptionTextCache texts;
    private ClientLevel level;
    private long frame;
    private LayoutBudget.Counts lastWork = new LayoutBudget.Counts(0, 0, 0, 0);

    public DynamicSubtitleLayout() { this(new CaptionTextCache()); }
    DynamicSubtitleLayout(CaptionTextCache texts) { this.texts = texts; }
    public LayoutBudget.Counts lastWork() { return lastWork; }

    private static final class State {
        SmoothAnchor motion;
        Point target;
        int slot = -1;
        long lastSolve = Long.MIN_VALUE / 2;
        long switchedAt, lastFrame, firstShown, drawnFrame = -1;
        CaptionRepulsion.RepulsionOffset repulsion = CaptionRepulsion.RepulsionOffset.ZERO;
    }

    public record Placed(SubtitlePool.Subtitle subtitle, CaptionTransform transform, CaptionTextCache.Text text, float alphaMultiplier) {
        public Placed(SubtitlePool.Subtitle subtitle, CaptionTransform transform, CaptionTextCache.Text text) {
            this(subtitle, transform, text, 1.0f);
        }
        public Point point() { return transform.point(); }
        public CaptionVisuals.Effect effect() { return transform.effect(); }
        public float baseScale() { return transform.baseScale(); }
    }
    private record Size(float halfWidth, float halfHeight) {}
    private record Source(AABB body, List<AABB> parts, Point emitter, BlockPos ignoredBlock) {}
    private record Frame(Point eye, Point right, Point up, Point forward, CaptionSettings settings, long now,
                         boolean submerged, boolean lowHealth) {}
    private record Job(SubtitlePool.Subtitle event, State state, Source source, Size size,
                       CaptionTextCache.Text text, VisualType type, CaptionVisuals.Effect effect, Point pivot, List<Point> points,
                       List<BillboardProjection.Bounds> silhouettes, List<BillboardProjection.Bounds> slotBounds,
                       boolean darkAmbient, float alphaMultiplier) {}

    public void clear() { states.clear(); level = null; lastWork = new LayoutBudget.Counts(0, 0, 0, 0); }

    public List<Placed> layout(Minecraft minecraft, SubtitlePool pool, CameraRenderState camera, long now) {
        return layout(minecraft, pool, camera, now, OnomixelConfig.snapshot());
    }

    public List<Placed> layout(Minecraft minecraft, SubtitlePool pool, CameraRenderState camera, long now, CaptionSettings settings) {
        if (level != minecraft.level) { states.clear(); level = minecraft.level; }
        if (level == null || !camera.initialized) return List.of();
        try (var scope = GeometrySlotRing.openScope()) {
            frame++;
            BossChoreographyDirector.getInstance().cleanExpiredFields(now);
            Set<Long> live = new HashSet<>();
            for (var subtitle : pool.snapshot()) live.add(subtitle.id());
            states.keySet().removeIf(id -> (id > 0 && !live.contains(id)) || (id < 0 && now - states.get(id).lastFrame > 2_000_000_000L));

            Vec3 eye = camera.pos;
            Point eyePoint = point(eye);
            Point right = rotated(1, 0, 0, camera), up = rotated(0, 1, 0, camera), forward = rotated(0, 0, -1, camera);
            Matrix4f projection = scope.mat4(camera.projectionMatrix).mul(camera.viewRotationMatrix);
            var window = minecraft.getWindow();
            var viewport = CaptionReadability.Viewport.pixels(window.getWidth(), window.getHeight(), window.getGuiScale());
            CrosshairComfortZone crosshairZone = CrosshairComfortZone.forViewportNdc(
                    window.getWidth(), window.getHeight(), 40.0, 80.0, 20.0);
            boolean isFirstPerson = minecraft.options != null && minecraft.options.getCameraType().isFirstPerson();
            FirstPersonWeaponMask.HandSide handSide = (minecraft.options != null && minecraft.player != null
                    && minecraft.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT)
                    ? FirstPersonWeaponMask.HandSide.LEFT : FirstPersonWeaponMask.HandSide.RIGHT;
            FirstPersonWeaponMask.WeaponState weaponState = FirstPersonWeaponMask.WeaponState.DEFAULT;
            if (minecraft.player != null && minecraft.player.isUsingItem()) {
                var item = minecraft.player.getUseItem();
                if (item.getItem() instanceof net.minecraft.world.item.BowItem || item.getItem() instanceof net.minecraft.world.item.CrossbowItem) {
                    weaponState = FirstPersonWeaponMask.WeaponState.BOW_AIMING;
                } else if (item.getItem() instanceof net.minecraft.world.item.ShieldItem) {
                    weaponState = FirstPersonWeaponMask.WeaponState.SHIELD_BLOCKING;
                }
            }
            FirstPersonWeaponMask weaponMask = FirstPersonWeaponMask.createNdc(isFirstPerson, handSide, weaponState);

            int limit = settings.maxVisible();
            LayoutBudget budget = new LayoutBudget(limit);
            List<Job> jobs = new ArrayList<>();
            List<BillboardProjection.Bounds> models = new ArrayList<>();

            // Cheap projection uses approximate source bounds, without block shapes or world rays.
            List<SubtitlePool.Subtitle> eligible = new ArrayList<>();
            boolean raining = level.isRainingAt(BlockPos.containing(eye));
            boolean submerged = !level.getFluidState(BlockPos.containing(eye)).isEmpty();
            Frame context = new Frame(eyePoint, right, up, forward, settings, now, submerged,
                    minecraft.player != null && minecraft.player.getHealth() <= 6.0F);
            for (var event : pool.candidates(eye.x, eye.y, eye.z, settings.maxDistance(), now)) {
                var bossPrivilege = BossChoreographyDirector.getInstance().evaluatePrivilege(event, now);
                if (!event.semantics().allowed(settings.mode())
                        || event.alpha(now, settings.fade()) * settings.opacity() * bossPrivilege.alphaMultiplier() * 255 < 4) continue;
                if (SoundEventPolicy.isRain(event.soundId()) && !raining) continue;
                var bounds = projectBox(approximateBody(event), eye, projection);
                if (bounds == null || !bounds.intersectsScreen()) continue;
                eligible.add(event);
            }

            // Battlefield clustering & Macro-LOD pass:
            List<SubtitlePool.Subtitle> candidatesForLayout = eligible;
            if (eligible.size() >= BattlefieldClusterLOD.DEFAULT_PROMOTION_THRESHOLD) {
                var battlefield = BattlefieldClusterLOD.processSubtitles(eligible);
                if (battlefield.hasClusters()) {
                    candidatesForLayout = new ArrayList<>();
                    for (var cluster : battlefield.clusters()) {
                        var sub = BattlefieldClusterLOD.createClusterSubtitle(cluster, now);
                        if (sub != null) candidatesForLayout.add(sub);
                    }
                    Set<Long> clusteredIds = new HashSet<>();
                    for (var cluster : battlefield.clusters()) {
                        for (var member : cluster.members()) {
                            clusteredIds.add(member.id());
                        }
                    }
                    for (var sub : eligible) {
                        if (!clusteredIds.contains(sub.id())) {
                            candidatesForLayout.add(sub);
                        }
                    }
                }
            }

            List<LayoutBudget.Candidate> preparation = new ArrayList<>();
            for (var event : candidatesForLayout) {
                State previous = states.get(event.id());
                preparation.add(new LayoutBudget.Candidate(event.id(), previous != null && previous.drawnFrame == frame - 1));
            }
            Set<Long> prepared = budget.shortlist(preparation, limit, frame);

            // Prepare only sources which could actually be drawn. Hidden/offscreen/faded events spend no search tickets.
            for (var event : candidatesForLayout) {
            if (!prepared.contains(event.id()) || !budget.prepare()) continue;
            Source source = source(event);
            var sourceBounds = projectBox(source.body, eye, projection);
            if (sourceBounds == null || !sourceBounds.intersectsScreen()
                    || !budget.sourceRay() || !SpatialAnchorResolver.clear(minecraft, eye, vec(source.emitter), source.ignoredBlock)) continue;
            State state = states.computeIfAbsent(event.id(), ignored -> new State());
            var type = CaptionVisuals.type(event.soundId());
            double age = state.motion == null ? 0 : (now - state.firstShown) / 1_000_000_000.0;
            double trigger = (now - event.triggeredNanos()) / 1_000_000_000.0;
            if (submerged) {
                age *= 0.5;
                trigger *= 0.5;
            }
            var effect = CaptionVisuals.sample(type, event.soundId(), age, trigger, event.id(), settings.style(),
                    settings.aestheticStyle(), settings.colorblindMode());
            var text = texts.get(minecraft, event.translationKey(), type.isBold(), type.isItalic());
            text = texts.animate(text, settings.kineticTypography()
                    ? KineticTypography.resolveMode(type.id(), event.soundId()) : KineticTypography.Mode.NONE, age);
            Size size = new Size(text.halfWidth() + 2, text.halfHeight() + 2);
            boolean ambient = event.semantics() == SoundSemantics.AMBIENT || event.semantics() == SoundSemantics.IDLE_VOICE;
            boolean darkAmbient = ambient && level.getRawBrightness(BlockPos.containing(
                    event.sourceX(), event.sourceY(), event.sourceZ()), 0) <= 4;
            // Reserve the largest pop and tilt envelope rather than just the unanimated baseline.
            var envelope = type.motionEnvelope();
            var reserved = CaptionGeometry.reserve(size.halfWidth, size.halfHeight, envelope);
            Size reserve = new Size(reserved.halfWidth(), reserved.halfHeight());
            double height = sourceHeight(event, SoundDefinitions.source(event.soundId()));
            boolean block = source.ignoredBlock != null;
            Point center = point(source.body.getCenter());
            Point pivot = height > 0 || SoundEventPolicy.isRain(event.soundId()) ? source.emitter : center;
            var bossPrivilege = BossChoreographyDirector.getInstance().evaluatePrivilege(event, now);
            if (bossPrivilege.displacement().x() != 0 || bossPrivilege.displacement().y() != 0 || bossPrivilege.displacement().z() != 0) {
                pivot = pivot.plus(bossPrivilege.displacement());
            }
            if (!block && pivot.distanceSquared(eyePoint) < 0.85 * 0.85) pivot = pivot.plus(forward, 0.90);
            double distance = Math.sqrt(pivot.distanceSquared(eyePoint));
            Point toward = direction(pivot, eyePoint);
            Point contact = new Point(event.x(), event.y(), event.z());
            double contactDot = (contact.x() - center.x()) * toward.x() + (contact.y() - center.y()) * toward.y()
                    + (contact.z() - center.z()) * toward.z();
            boolean useContact = block && contactDot > 0.15;
            if (useContact) pivot = contact;
            boolean selfHurt = isSelfHurt(event.soundId(), distance);
            float reservedScale = scale(distance, settings, selfHurt);
            double above = Math.max(0.12, source.body.maxY - pivot.y()) + reserve.halfHeight * reservedScale + 0.16 + envelope.maxRise();
            double below = Math.max(0.12, pivot.y() - source.body.minY) + reserve.halfHeight * reservedScale + 0.16 + envelope.maxRise();
            double push = block ? (useContact ? 0.16 : extent(source.body, toward) + 0.12) : 0.14;
            push = Math.min(push, Math.max(0, distance - 0.70));
            double horizontal = extent(source.body, right) + reserve.halfWidth * reservedScale + 0.17 + envelope.maxSide();
            if (selfHurt) {
                horizontal = Math.max(horizontal, 0.68);
            }
            List<Point> points = CaptionPlacement.aroundSource(pivot, toward, right, up, push,
                    horizontal, above, below,
                    (event.id() & 1) == 0 ? 1 : -1);
            List<BillboardProjection.Bounds> silhouettes = new ArrayList<>();
            for (AABB part : source.parts) {
                var bounds = projectBox(part, eye, projection);
                if (bounds != null) silhouettes.add(padded(bounds, viewport));
            }
            List<BillboardProjection.Bounds> slots = new ArrayList<>();
            for (Point candidate : points) {
                var transform = resolve(event, size, effect, candidate, darkAmbient, context, 0);
                slots.add(enclose(project(transform.point(), reserve, transform.baseScale(), camera, projection),
                        transform.project(eyePoint, camera.orientation, projection)));
            }
            jobs.add(new Job(event, state, source, size, text, type, effect, pivot,
                    points, silhouettes, slots, darkAmbient, bossPrivilege.alphaMultiplier()));
            models.addAll(silhouettes);
        }

        // Reproject all previously drawn text, including captions later in distance order. Never reserve oneself.
        Map<Long, BillboardProjection.Bounds> occupied = new HashMap<>();
        List<LayoutScheduler.Work> work = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            State state = job.state;
            if (state.motion != null && state.slot >= 0) {
                // Camera geometry follows every frame; only choosing a different slot is rate-limited.
                state.target = job.points.get(state.slot);
                if (state.drawnFrame == frame - 1) {
                    var previous = resolve(job, state.motion.position(), context, state.repulsion)
                            .project(eyePoint, camera.orientation, projection);
                    if (previous != null && previous.intersectsScreen()) occupied.put(job.event.id(), previous);
                }
                if (CaptionReadability.needsEscape(job.slotBounds.get(state.slot), viewport)) state.lastSolve = Long.MIN_VALUE / 2;
            }
            work.add(new LayoutScheduler.Work(job.event.id(), state.lastSolve, true,
                    i < limit || state.drawnFrame == frame - 1, state.motion != null));
        }
        Set<Long> tickets = LayoutScheduler.select(work, limit, now);

        // Solve all allocated jobs before applying the drawing limit, so later tickets cannot be discarded by a break.
        for (int jobIndex = 0; jobIndex < jobs.size(); jobIndex++) {
            Job job = jobs.get(jobIndex);
            if (!tickets.contains(job.event.id())) continue;
            State state = job.state;
            List<BillboardProjection.Bounds> others = otherBounds(occupied, job.event.id());
            double[] scores = new double[job.points.size()];
            java.util.Arrays.fill(scores, Double.POSITIVE_INFINITY);
            int best = -1;
            double bestScore = Double.POSITIVE_INFINITY;
            boolean selfHurt = isSelfHurt(job.event.soundId(), Math.sqrt(job.pivot.distanceSquared(eyePoint)));
            for (int slot = 0; slot < job.points.size(); slot++) {
                Point candidate = job.points.get(slot);
                if (!CaptionReadability.fullyOnScreen(job.slotBounds.get(slot))) continue;
                double score = CaptionReadability.score(job.slotBounds.get(slot), models, others, viewport,
                        candidate.distanceSquared(job.pivot));
                var sb = job.slotBounds.get(slot);
                var zone = crosshairZone.zoneOf(sb);
                if (zone == CrosshairComfortZone.Zone.CORE) {
                    if (selfHurt) {
                        continue;
                    }
                    score += 150.0;
                } else if (zone == CrosshairComfortZone.Zone.BUFFER) {
                    score += selfHurt ? 80.0 : 25.0;
                }
                if (weaponMask.intersects(sb)) {
                    score += 60.0;
                }
                score += job.type.slotScoreBonus(slot);
                if (slot == 1) score += 0.08;
                double deltaY = job.event.y() - eyePoint.y();
                if (deltaY >= 2.5 && (slot == 2 || slot == 3 || slot == 4)) {
                    score -= 0.15;
                } else if (deltaY <= -2.5 && slot == 5) {
                    score -= 0.15;
                }
                if (!Double.isFinite(score) || (slot != state.slot && score >= bestScore)) continue;
                var transform = resolve(job, candidate, context, 0);
                if (transform.point().distanceSquared(eyePoint) < 0.65 * 0.65
                        || !budget.searchRays(6)
                        || !reachable(minecraft, eye, job.source, transform, camera)) continue;
                scores[slot] = score;
                if (score < bestScore) { best = slot; bestScore = score; }
            }
            state.lastSolve = now;
            if (best < 0) {
                state.target = null;
                state.slot = -1;
                occupied.remove(job.event.id());
                continue;
            }
            double currentScore = state.slot < 0 ? Double.POSITIVE_INFINITY : scores[state.slot];
            if (state.slot >= 0 && CaptionReadability.needsEscape(job.slotBounds.get(state.slot), viewport)
                    && !CaptionReadability.needsEscape(job.slotBounds.get(best), viewport)) currentScore = Double.POSITIVE_INFINITY;
            int selected = SmoothAnchor.stableSlot(state.slot, best, currentScore, bestScore,
                    (now - state.switchedAt) / 1_000_000_000.0);
            if (selected != state.slot) state.switchedAt = now;
            state.slot = selected;
            state.target = job.points.get(selected);
            if (state.motion == null) {
                state.motion = new SmoothAnchor(state.target);
                state.firstShown = now;
                state.lastFrame = now;
            }
            if (state.drawnFrame == frame - 1 || jobIndex < limit) {
                occupied.put(job.event.id(), resolve(job, state.motion.position(), context, state.repulsion)
                        .project(eyePoint, camera.orientation, projection));
            }
        }

        List<Placed> result = new ArrayList<>();
        // Every caption reads the same frame snapshot for soft repulsion, independent of draw order.
        Map<Long, BillboardProjection.Bounds> repulsionBounds = new HashMap<>(occupied);
        int rainCount = 0;
        for (Job job : jobs) {
            State state = job.state;
            if (state.motion == null || state.target == null || state.slot < 0) continue;
            boolean rain = SoundEventPolicy.isRain(job.event.soundId());
            if (rain && rainCount >= 2) { occupied.remove(job.event.id()); continue; }
            Point previous = state.motion.position();
            List<BillboardProjection.Bounds> routeObstacles = new ArrayList<>(job.silhouettes);
            routeObstacles.add(viewport.reticle());
            routeObstacles.addAll(otherBounds(occupied, job.event.id()));
            var currentBounds = resolve(job, previous, context, state.repulsion)
                    .project(eyePoint, camera.orientation, projection);
            int nextSlot = CaptionRoute.nextSlot(currentBounds, job.slotBounds, state.slot, routeObstacles);
            Point target = job.points.get(nextSlot);
            double elapsed = (now - state.lastFrame) / 1_000_000_000.0;
            Point position = state.motion.advance(target, elapsed);
            state.lastFrame = now;
            if (!budget.finalRays(7)) { occupied.remove(job.event.id()); continue; }
            if (!SpatialAnchorResolver.clear(minecraft, vec(previous), vec(position), null)) {
                // A wall changed during the animation. Re-anchor while hidden and request a fresh safe slot.
                state.motion.reset(state.target);
                state.lastSolve = Long.MIN_VALUE / 2;
                occupied.remove(job.event.id());
                continue;
            }
            // Resolve every displacement and scale before checking the exact geometry submitted below.
            var unrepelled = resolve(job, position, context, CaptionRepulsion.RepulsionOffset.ZERO);
            state.repulsion = CaptionRepulsion.advance(state.repulsion, CaptionRepulsion.target(job.event.id(),
                    unrepelled.project(eyePoint, camera.orientation, projection), repulsionBounds,
                    crosshairZone, weaponMask), elapsed);
            var transform = resolve(job, position, context, state.repulsion);
            if (!reachable(minecraft, eye, job.source, transform, camera)) {
                state.lastSolve = Long.MIN_VALUE / 2;
                occupied.remove(job.event.id());
                continue;
            }
            var rectangle = transform.project(eyePoint, camera.orientation, projection);
            if (CaptionReadability.needsEscape(rectangle, viewport)
                    || CaptionReadability.score(rectangle, models, otherBounds(occupied, job.event.id()), viewport, 0) > 8) {
                state.lastSolve = Math.min(state.lastSolve, now - LayoutScheduler.INTERVAL_NANOS);
            }
            if (!CaptionReadability.fullyOnScreen(rectangle)) { occupied.remove(job.event.id()); continue; }
            occupied.put(job.event.id(), rectangle);
            state.drawnFrame = frame;
            result.add(new Placed(job.event, transform, job.text, job.alphaMultiplier));
            if (rain) rainCount++;
            if (result.size() == limit) break;
        }
        lastWork = budget.counts();
        return List.copyOf(result);
        }
    }

    public static float scale(double distance) {
        return scale(distance, OnomixelConfig.snapshot());
    }

    public static float scale(double distance, CaptionSettings settings) {
        return scale(distance, settings, false);
    }

    public static float scale(double distance, CaptionSettings settings, boolean selfHurt) {
        double factor = settings.distanceScaling() ? Math.clamp(Math.sqrt(distance / 6), 0.75, 1.8) : 1;
        return (float) (0.030 * settings.textScale() * factor * CaptionVisuals.nearDistanceFactor(distance, selfHurt));
    }

    public static boolean isSelfHurt(String soundId, double distance) {
        return CaptionVisuals.isSelfHurt(soundId, distance);
    }

    private AABB approximateBody(SubtitlePool.Subtitle event) {
        double x = event.sourceX(), y = event.sourceY(), z = event.sourceZ();
        var profile = SoundDefinitions.source(event.soundId());
        if (event.semantics() == SoundSemantics.STEP || event.semantics() == SoundSemantics.LANDING
                || profile.kind() == SoundDefinition.SourceKind.GROUND)
            return new AABB(x - 0.18, y, z - 0.18, x + 0.18, y + 0.18, z + 0.18);
        if (SpatialAnchorResolver.isBlock(event.soundId()) || profile.kind() == SoundDefinition.SourceKind.BLOCK
                || SoundEventPolicy.isRain(event.soundId()) || profile.kind() == SoundDefinition.SourceKind.RAIN) {
            BlockPos block = BlockPos.containing(x, y, z);
            return new AABB(block.getX(), block.getY(), block.getZ(), block.getX() + 1,
                    block.getY() + 2, block.getZ() + 1);
        }
        double height = sourceHeight(event, profile);
        double width = profile.width() > 0 ? profile.width() : height > 0 ? Math.clamp(height * 0.42, 0.35, 1.8) : 0.28;
        return new AABB(x - width / 2, y, z - width / 2, x + width / 2, y + (height > 0 ? height : 0.32), z + width / 2);
    }

    private static double sourceHeight(SubtitlePool.Subtitle event, SoundDefinition.Source profile) {
        return switch (profile.kind()) {
            case ENTITY -> profile.height();
            case DEFAULT -> SoundSourceProfile.height(event.soundId(), event.category());
            default -> 0;
        };
    }

    private Source source(SubtitlePool.Subtitle event) {
        double x = event.sourceX(), y = event.sourceY(), z = event.sourceZ();
        var profile = SoundDefinitions.source(event.soundId());
        if (SoundEventPolicy.isRain(event.soundId()) || profile.kind() == SoundDefinition.SourceKind.RAIN) {
            BlockPos support = BlockPos.containing(x, y, z);
            var shape = level.getBlockState(support).getCollisionShape(level, support);
            double top = support.getY() + Math.max(shape.isEmpty() ? 0 : shape.bounds().maxY,
                    level.getFluidState(support).getHeight(level, support));
            AABB surface = new AABB(support.getX(), top, support.getZ(), support.getX() + 1, top + 0.08, support.getZ() + 1);
            // The sound coordinate lies inside the supporting block. Never ignore this roof in a visibility ray.
            return new Source(surface, List.of(surface), new Point(support.getX() + 0.5, top + 0.85, support.getZ() + 0.5), null);
        }
        if (event.semantics() == SoundSemantics.STEP || event.semantics() == SoundSemantics.LANDING
                || profile.kind() == SoundDefinition.SourceKind.GROUND) {
            AABB foot = new AABB(x - 0.18, y, z - 0.18, x + 0.18, y + 0.18, z + 0.18);
            return new Source(foot, List.of(foot), new Point(x, y + 0.12, z), null);
        }
        if (SpatialAnchorResolver.isBlock(event.soundId()) || profile.kind() == SoundDefinition.SourceKind.BLOCK) {
            BlockPos block = BlockPos.containing(x, y, z);
            var shape = level.getBlockState(block).getShape(level, block);
            AABB body = shape.isEmpty() ? new AABB(block.getX(), block.getY(), block.getZ(),
                    block.getX() + 1, block.getY() + 1, block.getZ() + 1) : shape.bounds().move(block);
            List<AABB> parts = shape.isEmpty() ? List.of(body) : shape.toAabbs().stream().limit(16).map(part -> part.move(block)).toList();
            return new Source(body, parts, new Point(x, y + 0.05, z), block);
        }
        double height = sourceHeight(event, profile);
        double width = profile.width() > 0 ? profile.width() : height > 0 ? Math.clamp(height * 0.42, 0.35, 1.8) : 0.28;
        AABB body = new AABB(x - width / 2, y, z - width / 2, x + width / 2, y + (height > 0 ? height : 0.32), z + width / 2);
        return new Source(body, List.of(body), new Point(x, y + (height > 0 ? height * 0.84 : 0.20), z), null);
    }

    private static boolean reachable(Minecraft minecraft, Vec3 eye, Source source, CaptionTransform transform,
                                     CameraRenderState camera) {
        Point candidate = transform.point();
        if (!SpatialAnchorResolver.clear(minecraft, eye, vec(candidate), null)
                || !SpatialAnchorResolver.clear(minecraft, vec(source.emitter), vec(candidate), source.ignoredBlock)) return false;
        GeometrySlotRing ring = GeometrySlotRing.current();
        try (var localScope = ring.open()) {
            Vector3f offset = ring.vec3();
            Quaternionf q = ring.quat();
            transform.orientation(camera.orientation, q);
            for (int corner = 0; corner < 4; corner++) {
                BillboardProjection.cornerOffset(corner, transform.halfWidth(), transform.halfHeight(),
                        transform.scaleX(), transform.scaleY(), q, offset);
                if (!SpatialAnchorResolver.clear(minecraft, eye,
                        new Vec3(candidate.x() + offset.x, candidate.y() + offset.y, candidate.z() + offset.z), null)) return false;
            }
            return true;
        }
    }

    private static Point animated(Point base, Point right, Point up, CaptionVisuals.Effect effect) {
        return base.plus(right, effect.side()).plus(up, effect.rise());
    }

    private static CaptionTransform resolve(Job job, Point position, Frame frame, CaptionRepulsion.RepulsionOffset repulsion) {
        return resolve(job.event, job.size, job.effect, position, job.darkAmbient, frame, repulsion);
    }

    private static CaptionTransform resolve(Job job, Point position, Frame frame, double repulsion) {
        return resolve(job.event, job.size, job.effect, position, job.darkAmbient, frame, new CaptionRepulsion.RepulsionOffset(0.0, repulsion));
    }

    private static CaptionTransform resolve(SubtitlePool.Subtitle event, Size size, CaptionVisuals.Effect sampled,
                                             Point position, boolean darkAmbient, Frame frame, double repulsion) {
        return resolve(event, size, sampled, position, darkAmbient, frame, new CaptionRepulsion.RepulsionOffset(0.0, repulsion));
    }

    private static CaptionTransform resolve(SubtitlePool.Subtitle event, Size size, CaptionVisuals.Effect sampled,
                                             Point position, boolean darkAmbient, Frame frame, CaptionRepulsion.RepulsionOffset repulsion) {
        double distance = Math.sqrt(position.distanceSquared(frame.eye));
        boolean selfHurt = isSelfHurt(event.soundId(), distance);
        double dx = event.sourceX() - frame.eye.x(), dz = event.sourceZ() - frame.eye.z();
        var effect = sampled;

        // 2-Frame Manga Hitstop (~35ms) & Radial Comic Starburst Spikes (100ms)
        long elapsedNanos = frame.now - event.createdNanos();
        boolean hitstopEligible = MangaHitstopEffect.isEligible(event.soundId(), event.semantics());
        boolean hitstop = hitstopEligible && MangaHitstopEffect.isHitstopActive(elapsedNanos);
        float starburstAlpha = hitstopEligible ? MangaHitstopEffect.calculateStarburstAlpha(elapsedNanos) : 0.0F;
        if (hitstop) {
            effect = MangaHitstopEffect.applyHitstopEffect(effect, true);
        }
        if (selfHurt) {
            double relRight = dx * frame.right.x() + dz * frame.right.z();
            float tilt = Math.abs(relRight) > 0.25 ? (relRight > 0 ? -14.0F : 14.0F) : 0;
            effect = new CaptionVisuals.Effect(effect.scale(), effect.tiltDegrees() + tilt,
                    effect.side(), effect.rise(), effect.rgb(), effect.stretchX(), effect.stretchY());
        }
        Point point = animated(position, frame.right, frame.up, effect);
        if (selfHurt) {
            point = point.plus(frame.forward, -0.08);
        } else if (event.semantics() == SoundSemantics.ATTACK || event.semantics() == SoundSemantics.DAMAGE) {
            double divisor = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
            point = point.plus(new Point(dx / divisor, 0, dz / divisor), 0.10);
        }
        if (frame.submerged) {
            int tintedRgb = AcousticEnvironmentReactor.tintHydrological(effect.rgb(), 0x3F76E4);
            effect = new CaptionVisuals.Effect(effect.scale(), effect.tiltDegrees(), effect.side(), effect.rise(), tintedRgb, effect.stretchX(), effect.stretchY());
        }
        double wave = frame.submerged
                ? AcousticEnvironmentReactor.calculateRefractiveWobble(frame.now / 1_000_000_000.0, event.sourceX() + event.sourceZ())
                : 0;
        point = point.plus(frame.right, repulsion.dx()).plus(frame.up, wave + repulsion.dy());
        float baseScale = scale(distance, frame.settings, selfHurt);
        if (BossChoreographyDirector.isBossSound(event.soundId())) {
            var bossType = BossChoreographyDirector.identifyBoss(event.soundId());
            var stage = BossChoreographyDirector.identifyStage(bossType, event.soundId());
            if (stage != null) {
                baseScale *= stage.visualScale();
            }
        }
        var streamingSession = StreamingCaptionSession.getSessionBySound(event.soundId());
        if (streamingSession != null && streamingSession.isActive()) {
            baseScale *= streamingSession.breathingScale(frame.now);
        }
        if (selfHurt && frame.lowHealth) baseScale *= (float) (1 + 0.12 * Math.sin(frame.now / 150_000_000.0));
        if (AcousticEnvironmentReactor.isThinAir(point.y(), false)) baseScale *= AcousticEnvironmentReactor.THIN_AIR_SCALE;
        if (darkAmbient && point.distanceSquared(frame.eye) > 64) baseScale *= 0.80F;
        return new CaptionTransform(point, effect, baseScale, size.halfWidth, size.halfHeight, starburstAlpha, hitstop);
    }

    private static BillboardProjection.Bounds enclose(BillboardProjection.Bounds reserved, BillboardProjection.Bounds actual) {
        if (reserved == null || actual == null) return null;
        return new BillboardProjection.Bounds(Math.min(reserved.minX(), actual.minX()), Math.min(reserved.minY(), actual.minY()),
                Math.max(reserved.maxX(), actual.maxX()), Math.max(reserved.maxY(), actual.maxY()));
    }

    private static List<BillboardProjection.Bounds> otherBounds(Map<Long, BillboardProjection.Bounds> occupied, long self) {
        return occupied.entrySet().stream().filter(entry -> entry.getKey() != self && entry.getValue() != null)
                .map(Map.Entry::getValue).toList();
    }

    private static BillboardProjection.Bounds project(Point point, Size size, float scale, CameraRenderState camera, Matrix4f projection) {
        return BillboardProjection.project(point.x() - camera.pos.x, point.y() - camera.pos.y, point.z() - camera.pos.z,
                size.halfWidth, size.halfHeight, scale, camera.orientation, projection);
    }

    private static BillboardProjection.Bounds projectBox(AABB box, Vec3 eye, Matrix4f projection) {
        return BoxProjection.project(box.minX - eye.x, box.minY - eye.y, box.minZ - eye.z,
                box.maxX - eye.x, box.maxY - eye.y, box.maxZ - eye.z, projection);
    }

    private static BillboardProjection.Bounds padded(BillboardProjection.Bounds box, CaptionReadability.Viewport viewport) {
        float x = viewport.marginX() * 0.25F, y = viewport.marginY() * 0.25F;
        return new BillboardProjection.Bounds(box.minX() - x, box.minY() - y, box.maxX() + x, box.maxY() + y);
    }

    private static double extent(AABB box, Point direction) {
        return (Math.abs(direction.x()) * (box.maxX - box.minX) + Math.abs(direction.y()) * (box.maxY - box.minY)
                + Math.abs(direction.z()) * (box.maxZ - box.minZ)) / 2;
    }
    private static Point rotated(float x, float y, float z, CameraRenderState camera) {
        var vector = new Vector3f(x, y, z).rotate(camera.orientation);
        return new Point(vector.x, vector.y, vector.z);
    }
    private static Point direction(Point from, Point to) {
        double distance = Math.max(0.0001, Math.sqrt(from.distanceSquared(to)));
        return new Point((to.x() - from.x()) / distance, (to.y() - from.y()) / distance, (to.z() - from.z()) / distance);
    }
    private static Vec3 vec(Point point) { return new Vec3(point.x(), point.y(), point.z()); }
    private static Point point(Vec3 point) { return new Point(point.x, point.y, point.z); }

}
