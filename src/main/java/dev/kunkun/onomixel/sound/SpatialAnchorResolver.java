package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.CaptionPlacement.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Source metadata and transparent-aware visibility shared by creation and dynamic layout. */
public final class SpatialAnchorResolver {
    private SpatialAnchorResolver() {}

    public static Point initialAnchor(Minecraft minecraft, String id, double x, double y, double z) {
        var definition = SoundDefinitions.find(id);
        return initialAnchor(minecraft, definition, x, y, z);
    }

    public static Point initialAnchor(Minecraft minecraft, SoundDefinition definition, double x, double y, double z) {
        if (definition == null) return new Point(x, y + 0.25, z);
        String id = definition.soundId();
        var sourceKind = definition.source().kind();
        if (definition.semantics() == SoundSemantics.STEP || definition.semantics() == SoundSemantics.LANDING
                || sourceKind == SoundDefinition.SourceKind.GROUND) return new Point(x, y + 0.12, z);
        if (SoundEventPolicy.isRain(id) || sourceKind == SoundDefinition.SourceKind.RAIN) {
            BlockPos support = BlockPos.containing(x, y, z);
            var shape = minecraft.level.getBlockState(support).getCollisionShape(minecraft.level, support);
            double top = support.getY() + Math.max(shape.isEmpty() ? 0 : shape.bounds().maxY,
                    minecraft.level.getFluidState(support).getHeight(minecraft.level, support));
            return new Point(support.getX() + 0.5, top + 0.85, support.getZ() + 0.5);
        }
        if (isBlock(id) || definition.source().kind() == SoundDefinition.SourceKind.BLOCK) {
            BlockPos source = BlockPos.containing(x, y, z);
            if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(source)) {
                Vec3 contact = hit.getLocation();
                var face = hit.getDirection();
                return new Point(contact.x + face.getStepX() * 0.06, contact.y + face.getStepY() * 0.06,
                        contact.z + face.getStepZ() * 0.06);
            }
            return new Point(x, y + 0.10, z);
        }
        double height = sourceKind == SoundDefinition.SourceKind.ENTITY
                ? definition.source().height() : sourceKind == SoundDefinition.SourceKind.DEFAULT ? SoundPlacement.mobHeight(id) : 0;
        return new Point(x, y + (height > 0 ? height * 0.84 : 0.25), z);
    }

    public static boolean isBlock(String id) { return id.startsWith("minecraft:block."); }

    public static boolean clear(Minecraft minecraft, Vec3 from, Vec3 to, BlockPos ignored) {
        if (minecraft.level == null) return false;
        return minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, CollisionContext.empty()) {
            @Override
            public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
                // Glass has empty occlusion geometry; collider rays would incorrectly block it.
                return pos.equals(ignored) ? Shapes.empty() : state.getOcclusionShape();
            }
        }).getType() == HitResult.Type.MISS;
    }
}
