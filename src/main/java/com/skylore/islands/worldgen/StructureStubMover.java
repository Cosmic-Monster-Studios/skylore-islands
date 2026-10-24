package com.skylore.islands.worldgen;

import com.mojang.datafixers.util.Either;
import com.skylore.islands.mixin.StructurePiecesBuilderAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.Optional;
import java.util.function.Consumer;

public final class StructureStubMover {
    private StructureStubMover() {}

    /**
     * Move generated pieces so their bounding-box min Y matches {@code targetY}.
     * Do not use {@code stub.position().getY()} — ocean monuments keep pieces at vanilla Y=39
     * while the stub Y is the heightmap, so a stub-based delta leaves the water box in the void.
     */
    public static Optional<Structure.GenerationStub> atY(Structure.GenerationStub stub, int targetY) {
        BlockPos pos = new BlockPos(stub.position().getX(), targetY, stub.position().getZ());
        Either<Consumer<StructurePiecesBuilder>, StructurePiecesBuilder> generator = stub.generator();
        generator.ifRight(existing -> offsetMinY(existing, targetY));
        generator = generator.mapLeft(c -> builder -> {
            c.accept(builder);
            offsetMinY(builder, targetY);
        });
        return Optional.of(new Structure.GenerationStub(pos, generator));
    }

    /** Move generated pieces so their bounding-box max Y matches {@code topY}. */
    public static Optional<Structure.GenerationStub> topAtY(Structure.GenerationStub stub, int topY) {
        Either<Consumer<StructurePiecesBuilder>, StructurePiecesBuilder> generator = stub.generator();
        generator.ifRight(existing -> offsetMaxY(existing, topY));
        generator = generator.mapLeft(c -> builder -> {
            c.accept(builder);
            offsetMaxY(builder, topY);
        });
        return Optional.of(new Structure.GenerationStub(stub.position(), generator));
    }

    public static Optional<Structure.GenerationStub> alignMinCorner(Structure.GenerationStub stub, int destMinX, int destMinY, int destMinZ) {
        BlockPos pos = new BlockPos(destMinX, destMinY, destMinZ);
        Either<Consumer<StructurePiecesBuilder>, StructurePiecesBuilder> generator = stub.generator();
        generator.ifRight(existing -> offsetToMinCorner(existing, destMinX, destMinY, destMinZ));
        generator = generator.mapLeft(c -> builder -> {
            c.accept(builder);
            offsetToMinCorner(builder, destMinX, destMinY, destMinZ);
        });
        return Optional.of(new Structure.GenerationStub(pos, generator));
    }

    private static void offsetMinY(StructurePiecesBuilder builder, int targetY) {
        if (builder.isEmpty()) {
            return;
        }
        int dy = targetY - builder.getBoundingBox().minY();
        if (dy != 0) {
            builder.offsetPiecesVertically(dy);
        }
    }

    private static void offsetMaxY(StructurePiecesBuilder builder, int topY) {
        if (builder.isEmpty()) {
            return;
        }
        int dy = topY - builder.getBoundingBox().maxY();
        if (dy != 0) {
            builder.offsetPiecesVertically(dy);
        }
    }

    public static void offsetPieces(StructurePiecesBuilder builder, int dx, int dy, int dz) {
        if (builder.isEmpty() || (dx == 0 && dy == 0 && dz == 0)) {
            return;
        }
        for (StructurePiece piece : ((StructurePiecesBuilderAccessor) builder).skylore$getPieces()) {
            piece.move(dx, dy, dz);
        }
    }

    private static void offsetToMinCorner(StructurePiecesBuilder builder, int destMinX, int destMinY, int destMinZ) {
        if (builder.isEmpty()) {
            return;
        }
        BoundingBox box = builder.getBoundingBox();
        int dx = destMinX - box.minX();
        int dy = destMinY - box.minY();
        int dz = destMinZ - box.minZ();
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        offsetPieces(builder, dx, dy, dz);
    }
}
