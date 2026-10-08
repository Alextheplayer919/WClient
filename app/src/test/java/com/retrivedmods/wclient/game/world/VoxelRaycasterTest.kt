package com.retrivedmods.wclient.game.world

import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins down the DDA voxel walk used for line-of-sight: first opaque voxel after the start,
 * axis-aligned and diagonal segments, negative coordinates, flat axes, and the distance cap.
 */
class VoxelRaycasterTest {

    private fun opaqueAt(vararg voxels: Vector3i): (Int, Int, Int) -> Boolean {
        val set = voxels.toSet()
        return { x, y, z -> set.contains(Vector3i.from(x, y, z)) }
    }

    @Test
    fun straightLineHitsWall() {
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(0.5f, 0.5f, 0.5f),
            Vector3f.from(0.5f, 0.5f, 5.5f),
            16f,
            opaqueAt(Vector3i.from(0, 0, 3))
        )
        assertEquals(Vector3i.from(0, 0, 3), hit)
    }

    @Test
    fun clearLineReturnsNull() {
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(0.5f, 0.5f, 0.5f),
            Vector3f.from(0.5f, 0.5f, 5.5f),
            16f,
            opaqueAt(Vector3i.from(9, 9, 9))
        )
        assertNull(hit)
    }

    @Test
    fun skipsTheStartingVoxel() {
        // Opaque only in the voxel the ray starts in -> no hit.
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(0.5f, 0.5f, 0.5f),
            Vector3f.from(5.5f, 0.5f, 0.5f),
            16f,
            opaqueAt(Vector3i.from(0, 0, 0))
        )
        assertNull(hit)
    }

    @Test
    fun verticalLine() {
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(0.5f, 0.5f, 0.5f),
            Vector3f.from(0.5f, 20.5f, 0.5f),
            32f,
            opaqueAt(Vector3i.from(0, 5, 0))
        )
        assertEquals(Vector3i.from(0, 5, 0), hit)
    }

    @Test
    fun diagonalFindsFirstOpaqueVoxel() {
        // p(t) = (0.5, 0.5, 0.5) + t * (3, 0, 5): voxel entry order is
        // (0,0,1) @ t=0.1 -> (1,0,1) @ t~0.167 -> (1,0,2) @ t=0.3 -> ...
        // (1,0,2) is an unambiguous interior crossing, so it must be reported.
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(0.5f, 0.5f, 0.5f),
            Vector3f.from(3.5f, 0.5f, 5.5f),
            16f,
            opaqueAt(Vector3i.from(1, 0, 2))
        )
        assertEquals(Vector3i.from(1, 0, 2), hit)
    }

    @Test
    fun negativeCoordinates() {
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(-10.5f, 0.5f, -10.5f),
            Vector3f.from(-20.5f, 0.5f, -10.5f),
            16f,
            opaqueAt(Vector3i.from(-15, 0, -10))
        )
        assertEquals(Vector3i.from(-15, 0, -10), hit)
    }

    @Test
    fun stopsAtMaxDistance() {
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(0.5f, 0.5f, 0.5f),
            Vector3f.from(0.5f, 0.5f, 50.5f),
            5f,
            opaqueAt(Vector3i.from(0, 0, 10))
        )
        assertNull(hit)
    }

    @Test
    fun flatAxesDoNotLoop() {
        // dy == 0 and dz == 0 -> the Y/Z tMax values stay at infinity; the walk must still
        // terminate via the X axis.
        val hit = VoxelRaycaster.firstOpaque(
            Vector3f.from(0.5f, 0.5f, 0.5f),
            Vector3f.from(10.5f, 0.5f, 0.5f),
            16f,
            opaqueAt(Vector3i.from(7, 0, 0))
        )
        assertEquals(Vector3i.from(7, 0, 0), hit)
    }

    @Test
    fun degenerateSegmentReturnsNull() {
        val p = Vector3f.from(1.5f, 1.5f, 1.5f)
        assertNull(VoxelRaycaster.firstOpaque(p, p, 16f, { _, _, _ -> true }))
    }
}
