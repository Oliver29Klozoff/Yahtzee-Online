package com.yahtzee.online.dice3d

import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.texture.ImageTexture

/**
 * One physical die's 6 renderable faces. Each face is a flat plane positioned/rotated to sit
 * on a cube face; every frame we recompute each plane's world transform from the die's physics
 * body (position + orientation), since the library has no group/parent-node shortcut we can
 * build without a raw Filament entity.
 */
class DieVisual(engine: Engine, materialLoader: MaterialLoader) {

    private data class Face(val value: Int, val localOffset: Vec3, val localNormal: Vec3, val up: Vec3)

    private val faces = listOf(
        Face(1, Vec3(0f, 0.5f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, -1f)),
        Face(6, Vec3(0f, -0.5f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f)),
        Face(2, Vec3(0.5f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
        Face(5, Vec3(-0.5f, 0f, 0f), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f)),
        Face(3, Vec3(0f, 0f, 0.5f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)),
        Face(4, Vec3(0f, 0f, -0.5f), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f))
    )

    val planeNodes: List<PlaneNode>
    private val faceOrder: List<Face>

    init {
        val nodes = mutableListOf<PlaneNode>()
        val order = mutableListOf<Face>()
        for (face in faces) {
            val bitmap = DieTextureAtlas.buildFace(face.value)
            val texture = ImageTexture.Builder().bitmap(bitmap).build(engine)
            val material = materialLoader.createTextureInstance(texture, false, 0f, 0.55f, 0f)
            val node = PlaneNode(
                engine = engine,
                size = Float3(1f, 0f, 1f),
                center = Float3(0f, 0f, 0f),
                normal = Float3(0f, 1f, 0f),
                uvScale = dev.romainguy.kotlin.math.Float2(1f, 1f),
                materialInstance = material
            )
            nodes.add(node)
            order.add(face)
        }
        planeNodes = nodes
        faceOrder = order
    }

    /** Recomputes each face plane's world position/rotation from the die's current physics state. */
    fun applyTransform(position: Vec3, orientation: Quat) {
        for ((index, face) in faceOrder.withIndex()) {
            val node = planeNodes[index]
            val worldOffset = orientation.rotate(face.localOffset)
            node.position = Float3(
                position.x + worldOffset.x,
                position.y + worldOffset.y,
                position.z + worldOffset.z
            )
            node.quaternion = faceQuaternion(orientation, face)
        }
    }

    private fun faceQuaternion(dieOrientation: Quat, face: Face): Quaternion {
        val worldNormal = dieOrientation.rotate(face.localNormal)
        val worldUp = dieOrientation.rotate(face.up)
        val rotation = alignPlane(worldNormal, worldUp)
        return Quaternion(rotation.x, rotation.y, rotation.z, rotation.w)
    }

    /** Builds a quaternion rotating a plane whose default normal is +Y to face [normal], with [up] as its in-plane up. */
    private fun alignPlane(normal: Vec3, up: Vec3): Quat {
        val n = normal.normalized()
        val defaultNormal = Vec3(0f, 1f, 0f)
        val dot = defaultNormal.dot(n).coerceIn(-1f, 1f)
        val axis = defaultNormal.cross(n)
        val angle = kotlin.math.acos(dot)
        val alignNormal = if (axis.length() < 1e-4f) {
            if (dot > 0f) Quat.IDENTITY else Quat.fromAxisAngle(Vec3(1f, 0f, 0f), Math.PI.toFloat())
        } else {
            Quat.fromAxisAngle(axis, angle)
        }

        val rotatedUpDefault = alignNormal.rotate(Vec3(0f, 0f, -1f))
        val targetUp = up.normalized()
        val dot2 = rotatedUpDefault.dot(targetUp).coerceIn(-1f, 1f)
        val axis2 = rotatedUpDefault.cross(targetUp)
        val angle2 = kotlin.math.acos(dot2)
        val alignUp = if (axis2.length() < 1e-4f) Quat.IDENTITY else Quat.fromAxisAngle(axis2, angle2)

        return (alignUp * alignNormal).normalized()
    }
}
