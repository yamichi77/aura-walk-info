package io.github.yamichi77.aurawalkinfo.spatial

import androidx.compose.ui.graphics.Color
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.BoundingBox
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.ByteBufferRegion
import androidx.xr.scenecore.CustomMesh
import androidx.xr.scenecore.KhronosUnlitMaterial
import androidx.xr.scenecore.MeshBuffer
import androidx.xr.scenecore.MeshEntity
import androidx.xr.scenecore.MeshSubsetTopology
import androidx.xr.scenecore.VertexAttribute
import androidx.xr.scenecore.VertexAttributeType
import androidx.xr.scenecore.VertexLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Each of the two clipped quads can have eight corners. Draw both windings.
private const val MAX_VERTICES_PER_STROKE = 2 * (8 - 2) * 3 * 2
private const val POSITION_BYTES = 3 * Float.SIZE_BYTES

internal class SpatialMarkMesh(private val kind: MarkKind) : AutoCloseable {
    private val capacities = kind.strokeCounts.map { it * MAX_VERTICES_PER_STROKE }
    private val vertexCount = capacities.sum()
    private val vertices = directBuffer(vertexCount * POSITION_BYTES)
    private val vertexData = ByteBufferRegion(vertices, 0, vertices.capacity())
    private val materials = mutableListOf<KhronosUnlitMaterial>()
    private var buffer: MeshBuffer? = null
    private var mesh: CustomMesh? = null
    private var entity: MeshEntity? = null
    private var lastGeometry: List<List<MarkVector>>? = null
    private var lastColors: List<Color> = emptyList()
    private var lastOpacity = -1f

    suspend fun initialize(session: Session, parent: AnchorSpace) {
        val indices = directBuffer(vertexCount * Int.SIZE_BYTES)
        repeat(vertexCount) { indices.putInt(it) }
        indices.rewind()
        val layout = VertexLayout.Builder().addAttribute(VertexAttribute.POSITION, VertexAttributeType.FLOAT3).build()
        val meshBuffer = MeshBuffer.createDynamic(
            session, layout, vertexCount, vertexCount, listOf(vertexData),
            ByteBufferRegion(indices, 0, indices.capacity()),
        )
        buffer = meshBuffer
        val builder = CustomMesh.BuilderFromMeshBuffer(session, meshBuffer)
        var offset = 0
        for (capacity in capacities) {
            builder.addSubset(MeshSubsetTopology.TRIANGLES, offset, capacity)
            offset += capacity
            materials += KhronosUnlitMaterial.create(session, AlphaMode.BLEND).apply {
                setBaseColorFactor(Vector4(0f, 0f, 0f, 0f))
            }
        }
        val customMesh = builder.setBounds(bounds(Vector3.Zero, Vector3.Zero)).build()
        mesh = customMesh
        val node = MeshEntity.create(session, customMesh, materials)
        entity = node
        node.setEnabled(false)
        node.parent = parent
    }

    fun draw(geometry: List<List<MarkVector>>, colors: List<Color>, opacity: Float) {
        val node = checkNotNull(entity)
        if (opacity <= 0f || geometry.all { it.isEmpty() }) {
            if (node.isEnabled()) node.setEnabled(false)
            return
        }

        if (lastGeometry != geometry) {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            var offset = 0
            capacities.forEachIndexed { index, capacity ->
                val points = geometry.getOrNull(index).orEmpty()
                check(points.size <= capacity)
                val previous = lastGeometry?.getOrNull(index).orEmpty()
                val changed = points != previous
                vertices.position(offset)
                for (point in points) {
                    val x = point.x.toFloat()
                    val y = point.y.toFloat()
                    val z = point.z.toFloat()
                    if (changed) vertices.putFloat(x).putFloat(y).putFloat(z)
                    minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
                    maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
                }
                if (changed) {
                    // Clear only newly unused slots. Zero vertices make degenerate triangles.
                    repeat((previous.size - points.size).coerceAtLeast(0) * 3) { vertices.putFloat(0f) }
                    val size = vertices.position() - offset
                    vertices.rewind()
                    checkNotNull(buffer).updateVertexData(0, ByteBufferRegion(vertices, offset, size), offset)
                }
                offset += capacity * POSITION_BYTES
            }
            checkNotNull(mesh).bounds = bounds(Vector3(minX, minY, minZ), Vector3(maxX, maxY, maxZ))
            lastGeometry = geometry
        }
        if (lastColors != colors || lastOpacity != opacity) {
            materials.forEachIndexed { index, material ->
                val color = colors.getOrNull(index) ?: Color.Transparent
                material.setBaseColorFactor(Vector4(color.red, color.green, color.blue, opacity * color.alpha))
            }
            lastColors = colors
            lastOpacity = opacity
        }
        if (!node.isEnabled()) node.setEnabled(true)
    }

    override fun close() {
        entity?.setEnabled(false)
        entity?.parent = null
        entity = null
        mesh?.close()
        mesh = null
        buffer?.close()
        buffer = null
        materials.forEach { it.close() }
        materials.clear()
    }

    private fun bounds(minimum: Vector3, maximum: Vector3): BoundingBox {
        val padding = Vector3(0.001f, 0.001f, 0.001f)
        return BoundingBox.fromMinMax(minimum - padding, maximum + padding)
    }

    private fun directBuffer(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}
