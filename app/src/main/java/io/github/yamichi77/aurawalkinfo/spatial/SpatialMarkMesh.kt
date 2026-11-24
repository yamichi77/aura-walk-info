package io.github.yamichi77.aurawalkinfo.spatial

import android.app.Activity
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.BoundingBox
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.ByteBufferRegion
import androidx.xr.scenecore.CustomMesh
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.KhronosUnlitMaterial
import androidx.xr.scenecore.MeshBuffer
import androidx.xr.scenecore.MeshEntity
import androidx.xr.scenecore.MeshSubsetTopology
import androidx.xr.scenecore.VertexAttribute
import androidx.xr.scenecore.VertexAttributeType
import androidx.xr.scenecore.VertexLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val POSITION_BYTES = 3 * Float.SIZE_BYTES

internal class SpatialMarkMesh(private val capacities: List<Int>) {
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

    suspend fun initialize(session: Session, parent: Entity, pose: Pose = Pose.Identity): MeshEntity {
        entity?.let { return it }
        val meshBuffer = buffer ?: run {
            val indices = directBuffer(vertexCount * Int.SIZE_BYTES)
            repeat(vertexCount) { indices.putInt(it) }
            indices.rewind()
            val layout = VertexLayout.Builder().addAttribute(VertexAttribute.POSITION, VertexAttributeType.FLOAT3).build()
            MeshBuffer.createDynamic(
                session, layout, vertexCount, vertexCount, listOf(vertexData),
                ByteBufferRegion(indices, 0, indices.capacity()),
            ).also { buffer = it }
        }
        while (materials.size < capacities.size) {
            materials += KhronosUnlitMaterial.create(session, AlphaMode.BLEND).apply {
                setBaseColorFactor(Vector4(0f, 0f, 0f, 0f))
            }
        }
        val customMesh = mesh ?: run {
            val builder = CustomMesh.BuilderFromMeshBuffer(session, meshBuffer)
            var offset = 0
            for (capacity in capacities) {
                builder.addSubset(MeshSubsetTopology.TRIANGLES, offset, capacity)
                offset += capacity
            }
            builder.setBounds(bounds(Vector3.Zero, Vector3.Zero)).build().also { mesh = it }
        }
        val node = MeshEntity.create(session, customMesh, materials, pose = pose)
        entity = node
        node.setEnabled(false)
        node.parent = parent
        return node
    }

    fun draw(geometry: List<List<MarkVector>>, colors: List<Color>, opacity: Float) {
        val node = entity ?: return
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

    fun hide() {
        entity?.hideSpatialEntity()
    }

    fun removeFromScene() {
        entity?.hideSpatialEntity(detach = true)
    }

    private fun bounds(minimum: Vector3, maximum: Vector3): BoundingBox {
        val padding = Vector3(0.001f, 0.001f, 0.001f)
        return BoundingBox.fromMinMax(minimum - padding, maximum + padding)
    }

    private fun directBuffer(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}

// SceneCore owns entity reclamation. A detached MeshEntity retains the mesh and materials,
// and CustomMesh retains its buffer. Closing them here can leave a native node using them.
internal fun hideSpatialEntity(detach: Boolean, disable: () -> Unit, detachFromScene: () -> Unit) {
    disable()
    if (detach) detachFromScene()
}

internal fun Entity.hideSpatialEntity(detach: Boolean = false) {
    hideSpatialEntity(detach, { setEnabled(false) }, { parent = null })
}

internal fun Lifecycle.canRemoveSpatialEntities(context: Context): Boolean {
    val activity = context as? Activity
    return currentState == Lifecycle.State.RESUMED &&
        activity?.isFinishing != true && activity?.isChangingConfigurations != true
}
