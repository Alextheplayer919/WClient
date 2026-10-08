package com.retrivedmods.wclient.game.world

import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Voxel traversal (Amanatides &amp; Woo) for line-of-sight checks.
 *
 * Pure function over a [isOpaque] predicate so it is unit-testable without a session; [World]
 * wires the predicate to its own block storage.
 */
object VoxelRaycaster {

    /**
     * Walks the voxels along the segment [from] -> [to] and returns the first voxel (after the
     * starting voxel) for which [isOpaque] is true, or `null` when the segment is clear.
     *
     * The segment is walked at most [maxDistance] in length; a segment longer than that is
     * treated as "no hit within reach" (`null`).
     */
    fun firstOpaque(
        from: Vector3f,
        to: Vector3f,
        maxDistance: Float,
        isOpaque: (x: Int, y: Int, z: Int) -> Boolean
    ): Vector3i? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-6f || dist > maxDistance) return null

        // Unit direction components.
        val ux = dx / dist
        val uy = dy / dist
        val uz = dz / dist

        var x = floorToInt(from.x)
        var y = floorToInt(from.y)
        var z = floorToInt(from.z)

        val stepX = if (ux > 0f) 1 else -1
        val stepY = if (uy > 0f) 1 else -1
        val stepZ = if (uz > 0f) 1 else -1

        // Arc length to the next voxel boundary on each axis (INF when the axis is flat).
        val tDeltaX = if (ux != 0f) 1f / abs(ux) else Float.POSITIVE_INFINITY
        val tDeltaY = if (uy != 0f) 1f / abs(uy) else Float.POSITIVE_INFINITY
        val tDeltaZ = if (uz != 0f) 1f / abs(uz) else Float.POSITIVE_INFINITY

        var tMaxX = if (ux > 0f) (x + 1f - from.x) / ux else if (ux < 0f) (from.x - x) / -ux else Float.POSITIVE_INFINITY
        var tMaxY = if (uy > 0f) (y + 1f - from.y) / uy else if (uy < 0f) (from.y - y) / -uy else Float.POSITIVE_INFINITY
        var tMaxZ = if (uz > 0f) (z + 1f - from.z) / uz else if (uz < 0f) (from.z - z) / -uz else Float.POSITIVE_INFINITY

        var first = true
        while (true) {
            if (!first) {
                if (isOpaque(x, y, z)) return Vector3i.from(x, y, z)
            }
            first = false

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > dist) return null
                x += stepX
                tMaxX += tDeltaX
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > dist) return null
                y += stepY
                tMaxY += tDeltaY
            } else {
                if (tMaxZ > dist) return null
                z += stepZ
                tMaxZ += tDeltaZ
            }
        }
    }

    private fun floorToInt(v: Float): Int = Math.floor(v.toDouble()).toInt()
}
