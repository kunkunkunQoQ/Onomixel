package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.OnomixelConfig;
import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.sound.AcousticEnvironmentReactor;
import dev.kunkun.onomixel.sound.SoundEventPolicy;
import dev.kunkun.onomixel.sound.SoundSourceProfile;
import dev.kunkun.onomixel.sound.SpatialAnchorResolver;
import dev.kunkun.onomixel.sound.SoundDefinitions;
import dev.kunkun.onomixel.sound.SoundDefinition;
import dev.kunkun.onomixel.sound.SoundSemantics;
import dev.kunkun.onomixel.subtitle.BattlefieldClusterLOD;
import dev.kunkun.onomixel.subtitle.BattlefieldClusterTracker;
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
import net.minecraft.locale.Language;
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
    public static final double SCORE_PENALTY_CROSSHAIR_CORE = 150.0;
    public static final double SCORE_PENALTY_CROSSHAIR_BUFFER = 80.0;
    public static final double SCORE_PENALTY_BUFFER_DEFAULT = 25.0;
    public static final double SCORE_PENALTY_WEAPON_MASK = 60.0;
    public static final double MIN_HORIZONTAL_CLEARANCE = 0.68;
    public static final double PIVOT_MIN_EYE_DISTANCE_SQ = 0.85 * 0.85;
    public static final double PIVOT_FORWARD_PUSH = 0.90;

    static final long RAY_CACHE_TTL_NANOS = RayCachePolicy.RAY_CACHE_TTL_NANOS;
    static final double RAY_EYE_MOVE_SQ = RayCachePolicy.RAY_EYE_MOVE_SQ;
    static final double RAY_TARGET_MOVE_SQ = RayCachePolicy.RAY_TARGET_MOVE_SQ;
    static final double RAY_LOOK_COS_THRESHOLD = RayCachePolicy.RAY_LOOK_COS_THRESHOLD;
    private static final LayoutBudget.Counts ZERO_COUNTS = new LayoutBudget.Counts(0, 0, 0, 0);

    private final Map<Long, State> states = new HashMap<>();
    private final CaptionTextCache texts;
    private ClientLevel level;
    private long frame;
    private LayoutBudget.Counts lastWork = ZERO_COUNTS;
    private final LayoutBudget budget = new LayoutBudget(12);
    private final List<BillboardProjection.Bounds> scratchOtherBounds = new ArrayList<>(32);
    private final double[] scratchScores = new double[16];
    private final double[] scratchRawScores = new double[16];
    private final List<BillboardProjection.Bounds> scratchRouteObstacles = new ArrayList<>(32);
    private final Set<Long> scratchLiveIds = new HashSet<>(32);
    private final List<SubtitlePool.Subtitle> scratchEligible = new ArrayList<>(32);
    private final List<LayoutBudget.Candidate> scratchPreparation = new ArrayList<>(32);
    private final Set<Long> scratchPrepared = new HashSet<>(32);
    private final List<LayoutBudget.Candidate> scratchShortlistRest = new ArrayList<>(32);
    private final BattlefieldClusterTracker clusterTracker = new BattlefieldClusterTracker();
    private final List<SubtitlePool.Subtitle> scratchClusteredCandidates = new ArrayList<>(32);
    private final List<BattlefieldClusterLOD.SpotlightZone> scratchSpotlights = new ArrayList<>(8);
    private final List<Job> scratchJobs = new ArrayList<>(24);
    private final List<BillboardProjection.Bounds> scratchModels = new ArrayList<>(24);
    private final Map<Long, BillboardProjection.Bounds> scratchOccupied = new HashMap<>(32);
    private final Map<Long, BillboardProjection.Bounds> scratchRepulsionBounds = new HashMap<>(32);
    private final List<LayoutScheduler.Work> scratchWork = new ArrayList<>(24);
    private final List<Placed> scratchPlaced = new ArrayList<>(16);
    private int lastVpWidth = -1;
    private int lastVpHeight = -1;
    private CrosshairComfortZone cachedCrosshairZone;

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
        Source cachedSource;
        String cachedSourceSoundId;
        double cachedSourceX, cachedSourceY, cachedSourceZ;
        long cachedSourceNanos = Long.MIN_VALUE / 2;
        boolean cachedDarkAmbient;
        long cachedDarkAmbientNanos = Long.MIN_VALUE / 2;
        double cachedDarkX, cachedDarkY, cachedDarkZ;
        long sourceRayNanos = Long.MIN_VALUE / 2;
        double sourceRayEyeX, sourceRayEyeY, sourceRayEyeZ;
        double sourceRayTargetX, sourceRayTargetY, sourceRayTargetZ;
        boolean sourceRayClear;
        long finalRayNanos = Long.MIN_VALUE / 2;
        int finalRaySlot = -1;
        double finalRayEyeX, finalRayEyeY, finalRayEyeZ;
        double finalRayTargetX, finalRayTargetY, finalRayTargetZ;
        double finalRayLookX, finalRayLookY, finalRayLookZ;
        boolean finalRayClear;
    }

    /**
     * Immutable layout result for a single visible 3D world-space subtitle in the current frame.
     * <p>
     * <b>Coordinate System:</b>
     * <ul>
     *   <li>{@link #point()}: World coordinates [X, Y, Z] in Minecraft block/world units (Double precision),
     *       representing the anchor position for billboard rendering.</li>
     *   <li>{@link #transform()}: Contains the final resolved world position, dynamic kinetic scale,
     *       orientation billboard rotation, and 2D projected screen bounds.</li>
     *   <li>{@link #alphaMultiplier()}: Environmental alpha multiplier in {@code [0.0f, 1.0f]}
     *       (e.g., proximity/distance fade, submerged view, dark ambient, low health pulsation).</li>
     * </ul>
     *
     * @param subtitle Original audio event metadata (soundId, source position, category, lifetime)
     * @param transform Resolved world-space transform and screen projection bounds
     * @param text Cached glyph sequence, styling, and visual advance metrics
     * @param alphaMultiplier Environmental opacity multiplier in [0.0f, 1.0f]
     */
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
                       boolean darkAmbient, float alphaMultiplier, boolean hitstopEligible, float extraScale) {}

    /**
     * Groups hot-cell subtitles for xN clustering only when they resolve to identical
     * displayed text and matching visual styling/source semantics.
     */
    static String clusterGroupKey(SubtitlePool.Subtitle event) {
        return BattlefieldClusterLOD.styleGroupingKey(event, DynamicSubtitleLayout::translateKey);
    }

    private static String translateKey(String rawKey) {
        Language language = Language.getInstance();
        return language != null ? language.getOrDefault(rawKey) : rawKey;
    }

    public void clear() {
        states.clear();
        clusterTracker.clear();
        level = null;
        lastWork = ZERO_COUNTS;
        lastVpWidth = -1;
        lastVpHeight = -1;
        cachedCrosshairZone = null;
        clearFrameScratch();
    }

    private void clearFrameScratch() {
        scratchOtherBounds.clear();
        scratchRouteObstacles.clear();
        scratchLiveIds.clear();
        scratchEligible.clear();
        scratchPreparation.clear();
        scratchPrepared.clear();
        scratchShortlistRest.clear();
        scratchClusteredCandidates.clear();
        scratchSpotlights.clear();
        scratchJobs.clear();
        scratchModels.clear();
        scratchOccupied.clear();
        scratchRepulsionBounds.clear();
        scratchWork.clear();
        scratchPlaced.clear();
    }

    public List<Placed> layout(Minecraft minecraft, SubtitlePool pool, CameraRenderState camera, long now) {
        return layout(minecraft, pool, camera, now, OnomixelConfig.snapshot());
    }

    public List<Placed> layout(Minecraft minecraft, SubtitlePool pool, CameraRenderState camera, long now, CaptionSettings settings) {
        if (level != minecraft.level) { states.clear(); clusterTracker.clear(); level = minecraft.level; }
        if (level == null || !camera.initialized) return List.of();
        frame++;
        BossChoreographyDirector.getInstance().cleanExpiredFields(now);
        boolean hasActiveClusters = clusterTracker.hasActiveClusters(now);
        var activeSubtitles = pool.activeView();
        if (activeSubtitles.isEmpty() && !hasActiveClusters && states.isEmpty()) {
            lastWork = ZERO_COUNTS;
            return List.of();
        }
        scratchLiveIds.clear();
        for (int i = 0, size = activeSubtitles.size(); i < size; i++) {
            scratchLiveIds.add(activeSubtitles.get(i).id());
        }
        states.keySet().removeIf(id -> (id > 0 && !scratchLiveIds.contains(id)) || (id < 0 && now - states.get(id).lastFrame > 2_000_000_000L));
        if (activeSubtitles.isEmpty() && !hasActiveClusters) {
            scratchLiveIds.clear();
            lastWork = ZERO_COUNTS;
            return List.of();
        }

        try (var scope = GeometrySlotRing.openScope()) {
            Vec3 eye = camera.pos;
            Point eyePoint = point(eye);
            Point right = rotated(1, 0, 0, camera, scope), up = rotated(0, 1, 0, camera, scope), forward = rotated(0, 0, -1, camera, scope);
            Matrix4f projection = scope.mat4(camera.projectionMatrix).mul(camera.viewRotationMatrix);
            var window = minecraft.getWindow();
            int vpW = window.getWidth();
            int vpH = window.getHeight();
            var viewport = CaptionReadability.Viewport.pixels(vpW, vpH, window.getGuiScale());
            if (cachedCrosshairZone == null || vpW != lastVpWidth || vpH != lastVpHeight) {
                cachedCrosshairZone = CrosshairComfortZone.forViewportNdc(vpW, vpH, 40.0, 80.0, 20.0);
                lastVpWidth = vpW;
                lastVpHeight = vpH;
            }
            CrosshairComfortZone crosshairZone = cachedCrosshairZone;
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
            budget.reset(limit);
            scratchJobs.clear();
            scratchModels.clear();
            List<Job> jobs = scratchJobs;
            List<BillboardProjection.Bounds> models = scratchModels;

            // Cheap projection uses approximate source bounds, without block shapes or world rays.
            scratchEligible.clear();
            scratchSpotlights.clear();
            List<SubtitlePool.Subtitle> eligible = scratchEligible;
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
                if (BattlefieldClusterLOD.isCriticalThreat(event.soundId())) {
                    scratchSpotlights.add(new BattlefieldClusterLOD.SpotlightZone(event.x(), event.y(), event.z(), event.soundId()));
                }
            }
            if (eligible.isEmpty() && !hasActiveClusters) {
                clearFrameScratch();
                lastWork = budget.counts();
                return List.of();
            }

            // Battlefield clustering & Macro-LOD pass (stateful continuity + cross-boundary absorption):
            List<SubtitlePool.Subtitle> candidatesForLayout = eligible;
            if (hasActiveClusters
                    || BattlefieldClusterLOD.hasPotentialCluster(
                            eligible, BattlefieldClusterLOD.DEFAULT_PROMOTION_THRESHOLD, DynamicSubtitleLayout::clusterGroupKey)) {
                candidatesForLayout = clusterTracker.resolve(
                        eligible,
                        now,
                        BattlefieldClusterLOD.DEFAULT_PROMOTION_THRESHOLD,
                        DynamicSubtitleLayout::clusterGroupKey,
                        scratchClusteredCandidates);
                if (candidatesForLayout.isEmpty()) {
                    clearFrameScratch();
                    lastWork = budget.counts();
                    return List.of();
                }
            }

            Set<Long> prepared = scratchPrepared;
            if (candidatesForLayout.size() <= budget.preparationLimit()) {
                prepared.clear();
                for (int i = 0, csize = candidatesForLayout.size(); i < csize; i++) {
                    prepared.add(candidatesForLayout.get(i).id());
                }
            } else {
                scratchPreparation.clear();
                for (int i = 0, csize = candidatesForLayout.size(); i < csize; i++) {
                    var event = candidatesForLayout.get(i);
                    State previous = states.get(event.id());
                    scratchPreparation.add(new LayoutBudget.Candidate(event.id(), previous != null && previous.drawnFrame == frame - 1));
                }
                budget.shortlistInto(scratchPreparation, limit, frame, prepared, scratchShortlistRest);
            }

            // Prepare only sources which could actually be drawn. Hidden/offscreen/faded events spend no search tickets.
            for (int cIdx = 0, csize = candidatesForLayout.size(); cIdx < csize; cIdx++) {
            var event = candidatesForLayout.get(cIdx);
            if (!prepared.contains(event.id()) || !budget.prepare()) continue;
            State existingState = states.get(event.id());
            Source source = resolveSource(existingState, event, now);
            var sourceBounds = projectBox(source.body, eye, projection);
            if (sourceBounds == null || !sourceBounds.intersectsScreen() || !budget.sourceRay()) continue;
            if (existingState == null) {
                existingState = new State();
                existingState.lastFrame = now;
                existingState.cachedSource = source;
                existingState.cachedSourceSoundId = event.soundId();
                existingState.cachedSourceX = event.sourceX();
                existingState.cachedSourceY = event.sourceY();
                existingState.cachedSourceZ = event.sourceZ();
                existingState.cachedSourceNanos = now;
                states.put(event.id(), existingState);
            }
            State state = existingState;
            boolean sourceClear;
            if (shouldReuseRayCheck(now, state.sourceRayNanos,
                    eye.x, eye.y, eye.z, state.sourceRayEyeX, state.sourceRayEyeY, state.sourceRayEyeZ,
                    source.emitter.x(), source.emitter.y(), source.emitter.z(),
                    state.sourceRayTargetX, state.sourceRayTargetY, state.sourceRayTargetZ,
                    0, 0, 0, 0, 0, 0, false)) {
                sourceClear = state.sourceRayClear;
            } else {
                sourceClear = SpatialAnchorResolver.clear(minecraft, eye, vec(source.emitter), source.ignoredBlock);
                state.sourceRayNanos = now;
                state.sourceRayEyeX = eye.x;
                state.sourceRayEyeY = eye.y;
                state.sourceRayEyeZ = eye.z;
                state.sourceRayTargetX = source.emitter.x();
                state.sourceRayTargetY = source.emitter.y();
                state.sourceRayTargetZ = source.emitter.z();
                state.sourceRayClear = sourceClear;
            }
            if (!sourceClear) continue;
            CustomWordRule customRule = SoundCustomizationManager.resolve(event.soundId());
            var type = CaptionVisuals.type(event.soundId(), customRule);
            double age = state.motion == null ? 0 : (now - state.firstShown) / 1_000_000_000.0;
            double trigger = (now - event.triggeredNanos()) / 1_000_000_000.0;
            if (submerged) {
                age *= 0.5;
                trigger *= 0.5;
            }
            var effect = CaptionVisuals.sample(type, event.soundId(), customRule, age, trigger, event.id(),
                    settings.style(), settings.aestheticStyle(), settings.colorblindMode());
            boolean isBold = customRule != null && customRule.bold() != null ? customRule.bold() : type.isBold();
            boolean isItalic = customRule != null && customRule.italic() != null ? customRule.italic() : type.isItalic();
            CaptionTextCache.Text text;
            if (BattlefieldClusterLOD.isClusterSubtitle(event) && event.repeatCount() > 1) {
                text = texts.get(minecraft, event.translationKey(), isBold, isItalic,
                        null, BattlefieldClusterLOD.MultiplierBadge.formatSuffix(event.repeatCount()));
            } else if (event.soundId().startsWith("onomixel.chorus.")) {
                int badgeIdx = event.translationKey().lastIndexOf(" ×");
                String suffix = badgeIdx >= 0 ? event.translationKey().substring(badgeIdx) : null;
                text = texts.get(minecraft, event.soundId(), isBold, isItalic, null, suffix);
            } else {
                text = texts.get(minecraft, event.translationKey(), isBold, isItalic);
            }
            text = texts.animate(text, settings.kineticTypography()
                    ? KineticTypography.resolveMode(type.id(), event.soundId(), customRule) : KineticTypography.Mode.NONE, age);
            Size size = new Size(text.halfWidth() + 2, text.halfHeight() + 2);
            boolean ambient = event.semantics() == SoundSemantics.AMBIENT || event.semantics() == SoundSemantics.IDLE_VOICE;
            boolean darkAmbient = resolveDarkAmbient(state, ambient, event.sourceX(), event.sourceY(), event.sourceZ(), now);
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
            if (!block && pivot.distanceSquared(eyePoint) < PIVOT_MIN_EYE_DISTANCE_SQ) pivot = pivot.plus(forward, PIVOT_FORWARD_PUSH);
            double distance = Math.sqrt(pivot.distanceSquared(eyePoint));
            Point toward = direction(pivot, eyePoint);
            Point contact = new Point(event.x(), event.y(), event.z());
            double contactDot = (contact.x() - center.x()) * toward.x() + (contact.y() - center.y()) * toward.y()
                    + (contact.z() - center.z()) * toward.z();
            boolean useContact = block && contactDot > 0.15;
            if (useContact) pivot = contact;
            boolean selfHurt = isSelfHurt(event.soundId(), distance);
            boolean hitstopEligible = MangaHitstopEffect.isEligible(event.soundId(), event.semantics());
            float extraScale = (customRule != null && customRule.scaleMultiplier() != null) ? customRule.scaleMultiplier() : 1.0f;
            if (BossChoreographyDirector.isBossSound(event.soundId())) {
                var bossType = BossChoreographyDirector.identifyBoss(event.soundId());
                var stage = BossChoreographyDirector.identifyStage(bossType, event.soundId());
                if (stage != null) {
                    extraScale *= stage.visualScale();
                }
            }
            var streamingSession = StreamingCaptionSession.getSessionBySound(event.soundId());
            float streamingAlpha = 1.0f;
            if (streamingSession != null && (streamingSession.isActive() || streamingSession.isFadingOut())) {
                extraScale *= streamingSession.breathingScale(now);
                streamingAlpha = streamingSession.alpha(now);
            }
            float spotlightAlpha = scratchSpotlights.isEmpty()
                    ? 1.0f
                    : BattlefieldClusterLOD.evaluateSpotlightAlpha(event.x(), event.y(), event.z(), event.soundId(), scratchSpotlights, 1.0f);
            float totalAlphaMultiplier = bossPrivilege.alphaMultiplier() * spotlightAlpha * streamingAlpha;
            float reservedScale = scale(distance, settings, selfHurt) * extraScale;
            double above = Math.max(0.12, source.body.maxY - pivot.y()) + reserve.halfHeight * reservedScale + 0.16 + envelope.maxRise();
            double below = Math.max(0.12, pivot.y() - source.body.minY) + reserve.halfHeight * reservedScale + 0.16 + envelope.maxRise();
            double push = block ? (useContact ? 0.16 : extent(source.body, toward) + 0.12) : 0.14;
            push = Math.min(push, Math.max(0, distance - 0.70));
            double horizontal = extent(source.body, right) + reserve.halfWidth * reservedScale + 0.17 + envelope.maxSide();
            if (selfHurt) {
                horizontal = Math.max(horizontal, MIN_HORIZONTAL_CLEARANCE);
            }
            List<Point> points = CaptionPlacement.aroundSource(pivot, toward, right, up, push,
                    horizontal, above, below,
                    (event.id() & 1) == 0 ? 1 : -1);
            List<BillboardProjection.Bounds> silhouettes;
            if (source.parts.size() == 1) {
                var bounds = projectBox(source.parts.get(0), eye, projection);
                silhouettes = bounds != null ? List.of(padded(bounds, viewport)) : List.of();
            } else {
                silhouettes = new ArrayList<>(source.parts.size());
                for (int p = 0, psize = source.parts.size(); p < psize; p++) {
                    var bounds = projectBox(source.parts.get(p), eye, projection);
                    if (bounds != null) silhouettes.add(padded(bounds, viewport));
                }
            }
            List<BillboardProjection.Bounds> slots = new ArrayList<>(points.size());
            for (int p = 0, psize = points.size(); p < psize; p++) {
                Point candidate = points.get(p);
                var transform = resolve(event, size, effect, candidate, darkAmbient, context,
                        CaptionRepulsion.RepulsionOffset.ZERO, hitstopEligible, extraScale);
                slots.add(enclose(project(transform.point(), reserve, transform.baseScale(), camera, projection),
                        transform.project(eyePoint, camera.orientation, projection)));
            }
            jobs.add(new Job(event, state, source, size, text, type, effect, pivot,
                    points, silhouettes, slots, darkAmbient, totalAlphaMultiplier, hitstopEligible, extraScale));
            models.addAll(silhouettes);
        }

        if (jobs.isEmpty()) {
            lastWork = budget.counts();
            clearFrameScratch();
            return List.of();
        }

        // Reproject all previously drawn text, including captions later in distance order. Never reserve oneself.
        Map<Long, BillboardProjection.Bounds> occupied = scratchOccupied;
        occupied.clear();
        List<LayoutScheduler.Work> work = scratchWork;
        work.clear();
        for (int i = 0, jsize = jobs.size(); i < jsize; i++) {
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
        if (!tickets.isEmpty()) {
            for (int jobIndex = 0, jsize = jobs.size(); jobIndex < jsize; jobIndex++) {
                Job job = jobs.get(jobIndex);
                if (!tickets.contains(job.event.id())) continue;
                State state = job.state;
                state.finalRayNanos = Long.MIN_VALUE / 2;
                collectOtherBounds(occupied, job.event.id(), scratchOtherBounds);
                int numPoints = job.points.size();
                double[] scores = numPoints <= scratchScores.length ? scratchScores : new double[numPoints];
                double[] rawScores = numPoints <= scratchRawScores.length ? scratchRawScores : new double[numPoints];
                java.util.Arrays.fill(scores, 0, numPoints, Double.POSITIVE_INFINITY);
                java.util.Arrays.fill(rawScores, 0, numPoints, Double.POSITIVE_INFINITY);
                int best = -1;
                double bestScore = Double.POSITIVE_INFINITY;
                boolean selfHurt = isSelfHurt(job.event.soundId(), Math.sqrt(job.pivot.distanceSquared(eyePoint)));
                for (int slot = 0; slot < numPoints; slot++) {
                    Point candidate = job.points.get(slot);
                    if (!CaptionReadability.fullyOnScreen(job.slotBounds.get(slot))) continue;
                    double score = CaptionReadability.score(job.slotBounds.get(slot), models, scratchOtherBounds, viewport,
                            candidate.distanceSquared(job.pivot));
                    var sb = job.slotBounds.get(slot);
                    var zone = crosshairZone.zoneOf(sb);
                    if (zone == CrosshairComfortZone.Zone.CORE) {
                        if (selfHurt) {
                            continue;
                        }
                        score += SCORE_PENALTY_CROSSHAIR_CORE;
                    } else if (zone == CrosshairComfortZone.Zone.BUFFER) {
                        score += selfHurt ? SCORE_PENALTY_CROSSHAIR_BUFFER : SCORE_PENALTY_BUFFER_DEFAULT;
                    }
                    if (weaponMask.intersects(sb)) {
                        score += SCORE_PENALTY_WEAPON_MASK;
                    }
                    score += job.type.slotScoreBonus(slot);
                    if (slot == 1) score += 0.08;
                    double deltaY = job.event.y() - eyePoint.y();
                    if (deltaY >= 2.5 && (slot == 2 || slot == 3 || slot == 4)) {
                        score -= 0.15;
                    } else if (deltaY <= -2.5 && slot == 5) {
                        score -= 0.15;
                    }
                    if (Double.isFinite(score)) {
                        rawScores[slot] = score;
                    }
                }
                if (state.slot >= 0 && state.slot < numPoints && Double.isFinite(rawScores[state.slot])) {
                    int curSlot = state.slot;
                    var transform = resolve(job, job.points.get(curSlot), context, 0);
                    if (transform.point().distanceSquared(eyePoint) >= 0.65 * 0.65
                            && budget.searchRays(6)
                            && reachable(minecraft, eye, job.source, transform, camera)) {
                        scores[curSlot] = rawScores[curSlot];
                        best = curSlot;
                        bestScore = rawScores[curSlot];
                    }
                    rawScores[curSlot] = Double.POSITIVE_INFINITY;
                }
                while (true) {
                    int candidateSlot = -1;
                    double minRaw = bestScore;
                    for (int slot = 0; slot < numPoints; slot++) {
                        if (rawScores[slot] < minRaw) {
                            minRaw = rawScores[slot];
                            candidateSlot = slot;
                        }
                    }
                    if (candidateSlot < 0) break;
                    rawScores[candidateSlot] = Double.POSITIVE_INFINITY;
                    var transform = resolve(job, job.points.get(candidateSlot), context, 0);
                    if (transform.point().distanceSquared(eyePoint) < 0.65 * 0.65
                            || !budget.searchRays(6)
                            || !reachable(minecraft, eye, job.source, transform, camera)) continue;
                    scores[candidateSlot] = minRaw;
                    best = candidateSlot;
                    bestScore = minRaw;
                    break;
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
        }

        List<Placed> result = scratchPlaced;
        result.clear();
        // Every caption reads the same frame snapshot for soft repulsion, independent of draw order.
        Map<Long, BillboardProjection.Bounds> repulsionBounds = scratchRepulsionBounds;
        repulsionBounds.clear();
        if (!occupied.isEmpty()) {
            repulsionBounds.putAll(occupied);
        }
        int rainCount = 0;
        for (int jIdx = 0, jsize = jobs.size(); jIdx < jsize; jIdx++) {
            Job job = jobs.get(jIdx);
            State state = job.state;
            if (state.motion == null || state.target == null || state.slot < 0) continue;
            boolean rain = SoundEventPolicy.isRain(job.event.soundId());
            if (rain && rainCount >= 2) { occupied.remove(job.event.id()); continue; }
            Point previous = state.motion.position();
            List<BillboardProjection.Bounds> routeObstacles = scratchRouteObstacles;
            routeObstacles.clear();
            routeObstacles.addAll(job.silhouettes);
            routeObstacles.add(viewport.reticle());
            collectOtherBounds(occupied, job.event.id(), scratchOtherBounds);
            routeObstacles.addAll(scratchOtherBounds);
            var currentBounds = resolve(job, previous, context, state.repulsion)
                    .project(eyePoint, camera.orientation, projection);
            int nextSlot = CaptionRoute.nextSlot(currentBounds, job.slotBounds, state.slot, routeObstacles);
            Point target = job.points.get(nextSlot);
            double elapsed = (now - state.lastFrame) / 1_000_000_000.0;
            Point position = state.motion.advance(target, elapsed);
            state.lastFrame = now;
            if (!budget.finalRays(7)) { occupied.remove(job.event.id()); continue; }
            if (previous.distanceSquared(position) > 1e-4
                    && !SpatialAnchorResolver.clear(minecraft, vec(previous), vec(position), null)) {
                // A wall changed during the animation. Re-anchor while hidden and request a fresh safe slot.
                state.motion.reset(state.target);
                state.lastSolve = Long.MIN_VALUE / 2;
                state.finalRayNanos = Long.MIN_VALUE / 2;
                occupied.remove(job.event.id());
                continue;
            }
            // Resolve every displacement and scale before checking the exact geometry submitted below.
            var unrepelled = resolve(job, position, context, CaptionRepulsion.RepulsionOffset.ZERO);
            state.repulsion = CaptionRepulsion.advance(state.repulsion, CaptionRepulsion.target(job.event.id(),
                    unrepelled.project(eyePoint, camera.orientation, projection), repulsionBounds,
                    crosshairZone, weaponMask), elapsed);
            var transform = resolve(job, position, context, state.repulsion);
            boolean canReuseFinalRay = state.finalRayClear
                    && state.finalRaySlot == state.slot
                    && shouldReuseRayCheck(now, state.finalRayNanos,
                            eye.x, eye.y, eye.z, state.finalRayEyeX, state.finalRayEyeY, state.finalRayEyeZ,
                            transform.point().x(), transform.point().y(), transform.point().z(),
                            state.finalRayTargetX, state.finalRayTargetY, state.finalRayTargetZ,
                            forward.x(), forward.y(), forward.z(),
                            state.finalRayLookX, state.finalRayLookY, state.finalRayLookZ,
                            true);
            if (!canReuseFinalRay) {
                boolean isReachable = reachable(minecraft, eye, job.source, transform, camera);
                if (!isReachable) {
                    state.lastSolve = Long.MIN_VALUE / 2;
                    state.finalRayNanos = Long.MIN_VALUE / 2;
                    state.finalRayClear = false;
                    occupied.remove(job.event.id());
                    continue;
                }
                state.finalRayNanos = now;
                state.finalRaySlot = state.slot;
                state.finalRayEyeX = eye.x;
                state.finalRayEyeY = eye.y;
                state.finalRayEyeZ = eye.z;
                state.finalRayTargetX = transform.point().x();
                state.finalRayTargetY = transform.point().y();
                state.finalRayTargetZ = transform.point().z();
                state.finalRayLookX = forward.x();
                state.finalRayLookY = forward.y();
                state.finalRayLookZ = forward.z();
                state.finalRayClear = true;
            }
            var rectangle = transform.project(eyePoint, camera.orientation, projection);
            collectOtherBounds(occupied, job.event.id(), scratchOtherBounds);
            if (CaptionReadability.needsEscape(rectangle, viewport)
                    || CaptionReadability.score(rectangle, models, scratchOtherBounds, viewport, 0) > 8) {
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
        List<Placed> immutableResult = result.isEmpty() ? List.of() : List.copyOf(result);
        clearFrameScratch();
        return immutableResult;
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

    static boolean shouldReuseRayCheck(
            long now, long cachedNanos,
            double eyeX, double eyeY, double eyeZ,
            double cachedEyeX, double cachedEyeY, double cachedEyeZ,
            double targetX, double targetY, double targetZ,
            double cachedTargetX, double cachedTargetY, double cachedTargetZ,
            double lookX, double lookY, double lookZ,
            double cachedLookX, double cachedLookY, double cachedLookZ,
            boolean checkLook
    ) {
        return RayCachePolicy.shouldReuse(
                now, cachedNanos,
                eyeX, eyeY, eyeZ, cachedEyeX, cachedEyeY, cachedEyeZ,
                targetX, targetY, targetZ, cachedTargetX, cachedTargetY, cachedTargetZ,
                lookX, lookY, lookZ, cachedLookX, cachedLookY, cachedLookZ,
                checkLook
        );
    }

    private Source resolveSource(State state, SubtitlePool.Subtitle event, long now) {
        if (state != null && state.cachedSource != null
                && now >= state.cachedSourceNanos
                && now - state.cachedSourceNanos < RAY_CACHE_TTL_NANOS
                && state.cachedSourceX == event.sourceX()
                && state.cachedSourceY == event.sourceY()
                && state.cachedSourceZ == event.sourceZ()
                && java.util.Objects.equals(state.cachedSourceSoundId, event.soundId())) {
            return state.cachedSource;
        }
        Source resolved = source(event);
        if (state != null) {
            state.cachedSource = resolved;
            state.cachedSourceSoundId = event.soundId();
            state.cachedSourceX = event.sourceX();
            state.cachedSourceY = event.sourceY();
            state.cachedSourceZ = event.sourceZ();
            state.cachedSourceNanos = now;
        }
        return resolved;
    }

    private boolean resolveDarkAmbient(State state, boolean ambient, double x, double y, double z, long now) {
        if (!ambient) {
            return false;
        }
        if (state.cachedDarkAmbientNanos != Long.MIN_VALUE / 2
                && now >= state.cachedDarkAmbientNanos
                && now - state.cachedDarkAmbientNanos < RAY_CACHE_TTL_NANOS
                && state.cachedDarkX == x
                && state.cachedDarkY == y
                && state.cachedDarkZ == z) {
            return state.cachedDarkAmbient;
        }
        boolean dark = level.getRawBrightness(BlockPos.containing(x, y, z), 0) <= 4;
        state.cachedDarkAmbient = dark;
        state.cachedDarkAmbientNanos = now;
        state.cachedDarkX = x;
        state.cachedDarkY = y;
        state.cachedDarkZ = z;
        return dark;
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
            return new Source(body, List.of(body), new Point(x, y + 0.05, z), block);
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
        return resolve(job.event, job.size, job.effect, position, job.darkAmbient, frame, repulsion,
                job.hitstopEligible, job.extraScale);
    }

    private static CaptionTransform resolve(Job job, Point position, Frame frame, double repulsion) {
        return resolve(job.event, job.size, job.effect, position, job.darkAmbient, frame,
                new CaptionRepulsion.RepulsionOffset(0.0, repulsion), job.hitstopEligible, job.extraScale);
    }

    private static CaptionTransform resolve(SubtitlePool.Subtitle event, Size size, CaptionVisuals.Effect sampled,
                                             Point position, boolean darkAmbient, Frame frame,
                                             CaptionRepulsion.RepulsionOffset repulsion,
                                             boolean hitstopEligible, float extraScale) {
        double distance = Math.sqrt(position.distanceSquared(frame.eye));
        boolean selfHurt = isSelfHurt(event.soundId(), distance);
        double dx = event.sourceX() - frame.eye.x(), dz = event.sourceZ() - frame.eye.z();
        var effect = sampled;

        // 2-Frame Manga Hitstop (~35ms) & Radial Comic Starburst Spikes (100ms)
        long elapsedNanos = frame.now - event.createdNanos();
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
            point = new Point(point.x() + (dx / divisor) * 0.10, point.y(), point.z() + (dz / divisor) * 0.10);
        }
        if (frame.submerged) {
            int tintedRgb = AcousticEnvironmentReactor.tintHydrological(effect.rgb(), 0x3F76E4);
            effect = new CaptionVisuals.Effect(effect.scale(), effect.tiltDegrees(), effect.side(), effect.rise(), tintedRgb, effect.stretchX(), effect.stretchY());
        }
        double wave = frame.submerged
                ? AcousticEnvironmentReactor.calculateRefractiveWobble(frame.now / 1_000_000_000.0, event.sourceX() + event.sourceZ())
                : 0;
        point = point.plus(frame.right, repulsion.dx()).plus(frame.up, wave + repulsion.dy());
        float baseScale = scale(distance, frame.settings, selfHurt) * extraScale;
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

    private static void collectOtherBounds(Map<Long, BillboardProjection.Bounds> occupied, long self,
                                           List<BillboardProjection.Bounds> destination) {
        destination.clear();
        for (var entry : occupied.entrySet()) {
            if (entry.getKey() != self && entry.getValue() != null) {
                destination.add(entry.getValue());
            }
        }
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
    private static Point rotated(float x, float y, float z, CameraRenderState camera, GeometrySlotRing.Scope scope) {
        var vector = scope.vec3(x, y, z).rotate(camera.orientation);
        return new Point(vector.x, vector.y, vector.z);
    }
    private static Point direction(Point from, Point to) {
        double distance = Math.max(0.0001, Math.sqrt(from.distanceSquared(to)));
        return new Point((to.x() - from.x()) / distance, (to.y() - from.y()) / distance, (to.z() - from.z()) / distance);
    }
    private static Vec3 vec(Point point) { return new Vec3(point.x(), point.y(), point.z()); }
    private static Point point(Vec3 point) { return new Point(point.x, point.y, point.z); }

}
