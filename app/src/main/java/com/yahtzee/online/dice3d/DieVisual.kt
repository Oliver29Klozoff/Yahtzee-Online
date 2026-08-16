package com.yahtzee.online.dice3d

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.texture.ImageTexture

/**
 * One physical die: a single beveled-cube mesh (rounded-corner look) with one pip-textured
 * material per face, driven each frame by the die's physics body (position + orientation).
 */
class DieVisual(engine: Engine, materialLoader: MaterialLoader) {

    val node: GeometryNode

    init {
        val built = RoundedCubeMesh.build()

        val materials = built.submeshes.map { submesh ->
            val bitmap = DieTextureAtlas.buildFace(submesh.faceValue)
            val texture = ImageTexture.Builder().bitmap(bitmap).build(engine)
            materialLoader.createTextureInstance(texture, false, 0f, 0.22f, 0.08f) as MaterialInstance
        }
        val offsets = built.submeshes.map { it.indexStart until (it.indexStart + it.indexCount) }

        val geometry = Geometry.Builder(RenderableManager.PrimitiveType.TRIANGLES)
            .vertices(built.vertices)
            .indices(built.indices)
            .build(engine)

        node = GeometryNode(
            engine = engine,
            geometry = geometry,
            materialInstances = materials,
            primitivesOffsets = offsets
        )
        node.isShadowCaster = true
        node.isShadowReceiver = true
    }

    fun applyTransform(position: Vec3, orientation: Quat) {
        node.position = Float3(position.x, position.y, position.z)
        node.quaternion = Quaternion(orientation.x, orientation.y, orientation.z, orientation.w)
    }
}
