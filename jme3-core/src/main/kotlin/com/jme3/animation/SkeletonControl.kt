/*
 * Copyright (c) 2009-2018 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.animation

import com.jme3.export.*
import com.jme3.material.MatParamOverride
import com.jme3.math.FastMath
import com.jme3.math.Matrix4f
import com.jme3.renderer.*
import com.jme3.scene.*
import com.jme3.scene.VertexBuffer.Type
import com.jme3.scene.control.AbstractControl
import com.jme3.scene.control.Control
import com.jme3.scene.mesh.IndexBuffer
import com.jme3.shader.VarType
import com.jme3.util.SafeArrayList
import com.jme3.util.TempVars
import com.jme3.util.clone.Cloner
import com.jme3.util.clone.JmeCloneable

import java.io.IOException
import java.nio.FloatBuffer
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The Skeleton control deforms a model according to a skeleton, It handles the
 * computation of the deformation matrices and performs the transformations on
 * the mesh
 *
 * @author (kme) Ray Long
 * @author (jme) Rémy Bouquet Based on AnimControl by Kirill Vainer
 */
class SkeletonControl : AbstractControl, Cloneable, JmeCloneable {

    /**
     * The skeleton of the model.
     */
    /**
     * returns the skeleton of this control
     *
     * @return
     */
    var skeleton: Skeleton? = null
        private set

    /**
     * List of geometries affected by this control.
     */
    private var targets = SafeArrayList(Geometry::class.java)

    /**
     * Used to track when a mesh was updated. Meshes are only updated if they
     * are visible in at least one camera.
     */
    private var wasMeshUpdated = false

    /**
     * User wishes to use hardware skinning if available.
     */
    /**
     * @return True if hardware skinning is preferable to software skinning.
     * Set to false by default.
     *
     * @see .setHardwareSkinningPreferred
     */
    /**
     * Specifies if hardware skinning is preferred. If it is preferred and
     * supported by GPU, it shall be enabled, if it's not preferred, or not
     * supported by GPU, then it shall be disabled.
     *
     * @param preferred
     * @see .isHardwareSkinningUsed
     */
    @Transient
    var isHardwareSkinningPreferred = true

    /**
     * Hardware skinning is currently being used.
     */
    /**
     * @return True is hardware skinning is activated and is currently used, false otherwise.
     */
    @Transient
    var isHardwareSkinningUsed = false
        private set

    /**
     * Hardware skinning was tested on this GPU, results
     * are stored in [.hwSkinningSupported] variable.
     */
    @Transient
    private var hwSkinningTested = false

    /**
     * If hardware skinning was [tested][.hwSkinningTested], then
     * this variable will be set to true if supported, and false if otherwise.
     */
    @Transient
    private var hwSkinningSupported = false

    /**
     * Bone offset matrices, recreated each frame
     */
    @Transient
    private var offsetMatrices: Array<Matrix4f>? = null


    private var numberOfBonesParam: MatParamOverride? = null
    private var boneMatricesParam: MatParamOverride? = null

    /**
     * Serialization only. Do not use.
     */
    constructor() {}

    private fun switchToHardware() {
        numberOfBonesParam!!.isEnabled = true
        boneMatricesParam!!.isEnabled = true

        // Next full 10 bones (e.g. 30 on 24 bones)
        val numBones = (skeleton!!.boneCount / 10 + 1) * 10
        numberOfBonesParam!!.value = numBones

        for (geometry in targets) {
            val mesh = geometry.mesh
            if (mesh != null && mesh.isAnimated) {
                mesh.prepareForAnim(false)
            }
        }
    }

    private fun switchToSoftware() {
        numberOfBonesParam!!.isEnabled = false
        boneMatricesParam!!.isEnabled = false

        for (geometry in targets) {
            val mesh = geometry.mesh
            if (mesh != null && mesh.isAnimated) {
                mesh.prepareForAnim(true)
            }
        }
    }

    private fun testHardwareSupported(rm: RenderManager): Boolean {

        //Only 255 bones max supported with hardware skinning
        if (skeleton!!.boneCount > 255) {
            return false
        }

        switchToHardware()

        try {
            rm.preloadScene(spatial)
            return true
        } catch (e: RendererException) {
            Logger.getLogger(SkeletonControl::class.java.name).log(Level.WARNING, "Could not enable HW skinning due to shader compile error:", e)
            return false
        }

    }

    /**
     * Creates a skeleton control. The list of targets will be acquired
     * automatically when the control is attached to a node.
     *
     * @param skeleton the skeleton
     */
    constructor(skeleton: Skeleton?) {
        if (skeleton == null) {
            throw IllegalArgumentException("skeleton cannot be null")
        }
        this.skeleton = skeleton
        this.numberOfBonesParam = MatParamOverride(VarType.Int, "NumberOfBones", null)
        this.boneMatricesParam = MatParamOverride(VarType.Matrix4Array, "BoneMatrices", null)
    }

    /**
     * If specified the geometry has an animated mesh, add its mesh and material
     * to the lists of animation targets.
     */
    private fun findTargets(geometry: Geometry) {
        val mesh = geometry.mesh
        if (mesh != null && mesh.isAnimated) {
            targets.add(geometry)
        }

    }

    private fun findTargets(node: Node) {
        for (child in node.children) {
            if (child is Geometry) {
                findTargets(child)
            } else if (child is Node) {
                findTargets(child)
            }
        }
    }

    override fun setSpatial(spatial: Spatial?) {
        val oldSpatial = this.spatial
        super.setSpatial(spatial)
        updateTargetsAndMaterials(spatial)

        if (oldSpatial != null) {
            oldSpatial.removeMatParamOverride(numberOfBonesParam)
            oldSpatial.removeMatParamOverride(boneMatricesParam)
        }

        if (spatial != null) {
            spatial.removeMatParamOverride(numberOfBonesParam)
            spatial.removeMatParamOverride(boneMatricesParam)
            spatial.addMatParamOverride(numberOfBonesParam)
            spatial.addMatParamOverride(boneMatricesParam)
        }
    }

    private fun controlRenderSoftware() {
        resetToBind() // reset morph meshes to bind pose

        offsetMatrices = skeleton!!.computeSkinningMatrices()

        for (geometry in targets) {
            val mesh = geometry.mesh
            // NOTE: This assumes code higher up has
            // already ensured this mesh is animated.
            // Otherwise a crash will happen in skin update.
            softwareSkinUpdate(mesh, offsetMatrices!!)
        }
    }

    private fun controlRenderHardware() {
        offsetMatrices = skeleton!!.computeSkinningMatrices()
        boneMatricesParam!!.value = offsetMatrices
    }

    override fun controlRender(rm: RenderManager, vp: ViewPort) {
        if (!wasMeshUpdated) {
            updateTargetsAndMaterials(spatial)

            // Prevent illegal cases. These should never happen.
            assert(hwSkinningTested || !hwSkinningTested && !hwSkinningSupported && !isHardwareSkinningUsed)
            assert(!isHardwareSkinningUsed || isHardwareSkinningUsed && hwSkinningTested && hwSkinningSupported)

            if (isHardwareSkinningPreferred && !hwSkinningTested) {
                hwSkinningTested = true
                hwSkinningSupported = testHardwareSupported(rm)

                if (hwSkinningSupported) {
                    isHardwareSkinningUsed = true

                    Logger.getLogger(SkeletonControl::class.java.name).log(Level.INFO, "Hardware skinning engaged for {0}", spatial)
                } else {
                    switchToSoftware()
                }
            } else if (isHardwareSkinningPreferred && hwSkinningSupported && !isHardwareSkinningUsed) {
                switchToHardware()
                isHardwareSkinningUsed = true
            } else if (!isHardwareSkinningPreferred && isHardwareSkinningUsed) {
                switchToSoftware()
                isHardwareSkinningUsed = false
            }

            if (isHardwareSkinningUsed) {
                controlRenderHardware()
            } else {
                controlRenderSoftware()
            }

            wasMeshUpdated = true
        }
    }

    override fun controlUpdate(tpf: Float) {
        wasMeshUpdated = false
    }

    //only do this for software updates
    internal fun resetToBind() {
        for (geometry in targets) {
            val mesh = geometry.mesh
            if (mesh != null && mesh.isAnimated) {
                val bwBuff = mesh.getBuffer(Type.BoneWeight).data
                val biBuff = mesh.getBuffer(Type.BoneIndex).data
                if (!biBuff.hasArray() || !bwBuff.hasArray()) {
                    mesh.prepareForAnim(true) // prepare for software animation
                }
                val bindPos = mesh.getBuffer(Type.BindPosePosition)
                val bindNorm = mesh.getBuffer(Type.BindPoseNormal)
                val pos = mesh.getBuffer(Type.Position)
                val norm = mesh.getBuffer(Type.Normal)
                val pb = pos.data as FloatBuffer
                val nb = norm.data as FloatBuffer
                val bpb = bindPos.data as FloatBuffer
                val bnb = bindNorm.data as FloatBuffer
                pb.clear()
                nb.clear()
                bpb.clear()
                bnb.clear()

                //reset bind tangents if there is a bind tangent buffer
                val bindTangents = mesh.getBuffer(Type.BindPoseTangent)
                if (bindTangents != null) {
                    val tangents = mesh.getBuffer(Type.Tangent)
                    val tb = tangents.data as FloatBuffer
                    val btb = bindTangents.data as FloatBuffer
                    tb.clear()
                    btb.clear()
                    tb.put(btb).clear()
                }


                pb.put(bpb).clear()
                nb.put(bnb).clear()
            }
        }
    }

    override fun cloneForSpatial(spatial: Spatial): Control {
        val clonedNode = spatial as Node
        val clone = SkeletonControl()

        val ctrl = spatial.getControl(AnimControl::class.java)
        if (ctrl != null) {
            // AnimControl is responsible for cloning the skeleton, not
            // SkeletonControl.
            clone.skeleton = ctrl.skeleton
        } else {
            // If there's no AnimControl, create the clone ourselves.
            clone.skeleton = Skeleton(skeleton!!)
        }
        clone.isHardwareSkinningPreferred = this.isHardwareSkinningPreferred
        clone.isHardwareSkinningUsed = this.isHardwareSkinningUsed
        clone.hwSkinningSupported = this.hwSkinningSupported
        clone.hwSkinningTested = this.hwSkinningTested

        clone.setSpatial(clonedNode)

        // Fix attachments for the cloned node
        for (i in 0 until clonedNode.quantity) {
            // go through attachment nodes, apply them to correct bone
            val child = clonedNode.getChild(i)
            if (child is Node) {
                val originalBone = child.getUserData<Any>("AttachedBone") as Bone

                if (originalBone != null) {
                    val clonedBone = clone.skeleton!!.getBone(originalBone.name)

                    child.setUserData("AttachedBone", clonedBone)
                    clonedBone!!.setAttachmentsNode(child)
                }
            }
        }

        return clone
    }

    override fun jmeClone(): Any {
        return super.jmeClone()
    }

    override fun cloneFields(cloner: Cloner, original: Any) {
        super.cloneFields(cloner, original)

        this.skeleton = cloner.clone<Skeleton>(skeleton)

        // If the targets were cloned then this will clone them.  If the targets
        // were shared then this will share them.
        this.targets = cloner.clone(targets)

        this.numberOfBonesParam = cloner.clone<MatParamOverride>(numberOfBonesParam)
        this.boneMatricesParam = cloner.clone<MatParamOverride>(boneMatricesParam)
    }

    /**
     * Access the attachments node of the named bone. If the bone doesn't
     * already have an attachments node, create one and attach it to the scene
     * graph. Models and effects attached to the attachments node will follow
     * the bone's motions.
     *
     * @param boneName the name of the bone
     * @return the attachments node of the bone
     */
    fun getAttachmentsNode(boneName: String): Node {
        val b = skeleton!!.getBone(boneName)
                ?: throw IllegalArgumentException("Given bone name does not exist " + "in the skeleton.")

        updateTargetsAndMaterials(spatial)
        val boneIndex = skeleton!!.getBoneIndex(b)
        val n = b.getAttachmentsNode(boneIndex, targets)
        /*
         * Select a node to parent the attachments node.
         */
        val parent: Node
        if (spatial is Node) {
            parent = spatial as Node // the usual case
        } else {
            parent = spatial.parent
        }
        parent.attachChild(n)

        return n
    }

    /**
     * Enumerate the target meshes of this control.
     *
     * @return a new array
     */
    fun getTargets(): Array<Mesh> {
//        val result = arrayOfNulls<Mesh>(targets.size)
        val result = Array(size = targets.size, init = { Mesh() })
        targets
                .map { it.mesh }
                .forEachIndexed { i, mesh -> result[i] = mesh }

        return result
    }

    /**
     * Update the mesh according to the given transformation matrices
     *
     * @param mesh then mesh
     * @param offsetMatrices the transformation matrices to apply
     */
    private fun softwareSkinUpdate(mesh: Mesh, offsetMatrices: Array<Matrix4f>) {

        val tb = mesh.getBuffer(Type.Tangent)
        if (tb == null) {
            //if there are no tangents use the classic skinning
            applySkinning(mesh, offsetMatrices)
        } else {
            //if there are tangents use the skinning with tangents
            applySkinningTangents(mesh, offsetMatrices, tb)
        }


    }

    /**
     * Method to apply skinning transforms to a mesh's buffers
     *
     * @param mesh the mesh
     * @param offsetMatrices the offset matices to apply
     */
    private fun applySkinning(mesh: Mesh, offsetMatrices: Array<Matrix4f>) {
        val maxWeightsPerVert = mesh.maxNumWeights
        if (maxWeightsPerVert <= 0) {
            throw IllegalStateException("Max weights per vert is incorrectly set!")
        }
        val fourMinusMaxWeights = 4 - maxWeightsPerVert

        // NOTE: This code assumes the vertex buffer is in bind pose
        // resetToBind() has been called this frame
        val vb = mesh.getBuffer(Type.Position)
        val fvb = vb.data as FloatBuffer
        fvb.rewind()

        val nb = mesh.getBuffer(Type.Normal)
        val fnb = nb.data as FloatBuffer
        fnb.rewind()

        // get boneIndexes and weights for mesh
        val ib = IndexBuffer.wrapIndexBuffer(mesh.getBuffer(Type.BoneIndex).data)
        val wb = mesh.getBuffer(Type.BoneWeight).data as FloatBuffer

        wb.rewind()

        val weights = wb.array()
        var idxWeights = 0

        val vars = TempVars.get()

        val posBuf = vars.skinPositions
        val normBuf = vars.skinNormals

        val iterations = FastMath.ceil(fvb.limit() / posBuf.size.toFloat()).toInt()
        var bufLength = posBuf.size
        for (i in iterations - 1 downTo 0) {
            // read next set of positions and normals from native buffer
            bufLength = Math.min(posBuf.size, fvb.remaining())
            fvb.get(posBuf, 0, bufLength)
            fnb.get(normBuf, 0, bufLength)
            val verts = bufLength / 3
            var idxPositions = 0

            // iterate vertices and apply skinning transform for each effecting bone
            for (vert in verts - 1 downTo 0) {
                // Skip this vertex if the first weight is zero.
                if (weights[idxWeights] == 0f) {
                    idxPositions += 3
                    idxWeights += 4
                    continue
                }

                val nmx = normBuf[idxPositions]
                val vtx = posBuf[idxPositions++]
                val nmy = normBuf[idxPositions]
                val vty = posBuf[idxPositions++]
                val nmz = normBuf[idxPositions]
                val vtz = posBuf[idxPositions++]

                var rx = 0f
                var ry = 0f
                var rz = 0f
                var rnx = 0f
                var rny = 0f
                var rnz = 0f

                for (w in maxWeightsPerVert - 1 downTo 0) {
                    val weight = weights[idxWeights]
                    val mat = offsetMatrices[ib.get(idxWeights++)]

                    rx += (mat.m00 * vtx + mat.m01 * vty + mat.m02 * vtz + mat.m03) * weight
                    ry += (mat.m10 * vtx + mat.m11 * vty + mat.m12 * vtz + mat.m13) * weight
                    rz += (mat.m20 * vtx + mat.m21 * vty + mat.m22 * vtz + mat.m23) * weight

                    rnx += (nmx * mat.m00 + nmy * mat.m01 + nmz * mat.m02) * weight
                    rny += (nmx * mat.m10 + nmy * mat.m11 + nmz * mat.m12) * weight
                    rnz += (nmx * mat.m20 + nmy * mat.m21 + nmz * mat.m22) * weight
                }

                idxWeights += fourMinusMaxWeights

                idxPositions -= 3
                normBuf[idxPositions] = rnx
                posBuf[idxPositions++] = rx
                normBuf[idxPositions] = rny
                posBuf[idxPositions++] = ry
                normBuf[idxPositions] = rnz
                posBuf[idxPositions++] = rz
            }

            fvb.position(fvb.position() - bufLength)
            fvb.put(posBuf, 0, bufLength)
            fnb.position(fnb.position() - bufLength)
            fnb.put(normBuf, 0, bufLength)
        }

        vars.release()

        vb.updateData(fvb)
        nb.updateData(fnb)

    }

    /**
     * Specific method for skinning with tangents to avoid cluttering the
     * classic skinning calculation with null checks that would slow down the
     * process even if tangents don't have to be computed. Also the iteration
     * has additional indexes since tangent has 4 components instead of 3 for
     * pos and norm
     *
     * @param maxWeightsPerVert maximum number of weights per vertex
     * @param mesh the mesh
     * @param offsetMatrices the offsetMaytrices to apply
     * @param tb the tangent vertexBuffer
     */
    private fun applySkinningTangents(mesh: Mesh, offsetMatrices: Array<Matrix4f>, tb: VertexBuffer) {
        val maxWeightsPerVert = mesh.maxNumWeights

        if (maxWeightsPerVert <= 0) {
            throw IllegalStateException("Max weights per vert is incorrectly set!")
        }

        val fourMinusMaxWeights = 4 - maxWeightsPerVert

        // NOTE: This code assumes the vertex buffer is in bind pose
        // resetToBind() has been called this frame
        val vb = mesh.getBuffer(Type.Position)
        val fvb = vb.data as FloatBuffer
        fvb.rewind()

        val nb = mesh.getBuffer(Type.Normal)

        val fnb = nb.data as FloatBuffer
        fnb.rewind()


        val ftb = tb.data as FloatBuffer
        ftb.rewind()


        // get boneIndexes and weights for mesh
        val ib = IndexBuffer.wrapIndexBuffer(mesh.getBuffer(Type.BoneIndex).data)
        val wb = mesh.getBuffer(Type.BoneWeight).data as FloatBuffer

        wb.rewind()

        val weights = wb.array()
        var idxWeights = 0

        val vars = TempVars.get()


        val posBuf = vars.skinPositions
        val normBuf = vars.skinNormals
        val tanBuf = vars.skinTangents

        val iterations = FastMath.ceil(fvb.limit() / posBuf.size.toFloat()).toInt()
        var bufLength = 0
        var tanLength = 0
        for (i in iterations - 1 downTo 0) {
            // read next set of positions and normals from native buffer
            bufLength = Math.min(posBuf.size, fvb.remaining())
            tanLength = Math.min(tanBuf.size, ftb.remaining())
            fvb.get(posBuf, 0, bufLength)
            fnb.get(normBuf, 0, bufLength)
            ftb.get(tanBuf, 0, tanLength)
            val verts = bufLength / 3
            var idxPositions = 0
            //tangents has their own index because of the 4 components
            var idxTangents = 0

            // iterate vertices and apply skinning transform for each effecting bone
            for (vert in verts - 1 downTo 0) {
                // Skip this vertex if the first weight is zero.
                if (weights[idxWeights] == 0f) {
                    idxTangents += 4
                    idxPositions += 3
                    idxWeights += 4
                    continue
                }

                val nmx = normBuf[idxPositions]
                val vtx = posBuf[idxPositions++]
                val nmy = normBuf[idxPositions]
                val vty = posBuf[idxPositions++]
                val nmz = normBuf[idxPositions]
                val vtz = posBuf[idxPositions++]

                val tnx = tanBuf[idxTangents++]
                val tny = tanBuf[idxTangents++]
                val tnz = tanBuf[idxTangents++]

                // skipping the 4th component of the tangent since it doesn't have to be transformed
                idxTangents++

                var rx = 0f
                var ry = 0f
                var rz = 0f
                var rnx = 0f
                var rny = 0f
                var rnz = 0f
                var rtx = 0f
                var rty = 0f
                var rtz = 0f

                for (w in maxWeightsPerVert - 1 downTo 0) {
                    val weight = weights[idxWeights]
                    val mat = offsetMatrices[ib.get(idxWeights++)]

                    rx += (mat.m00 * vtx + mat.m01 * vty + mat.m02 * vtz + mat.m03) * weight
                    ry += (mat.m10 * vtx + mat.m11 * vty + mat.m12 * vtz + mat.m13) * weight
                    rz += (mat.m20 * vtx + mat.m21 * vty + mat.m22 * vtz + mat.m23) * weight

                    rnx += (nmx * mat.m00 + nmy * mat.m01 + nmz * mat.m02) * weight
                    rny += (nmx * mat.m10 + nmy * mat.m11 + nmz * mat.m12) * weight
                    rnz += (nmx * mat.m20 + nmy * mat.m21 + nmz * mat.m22) * weight

                    rtx += (tnx * mat.m00 + tny * mat.m01 + tnz * mat.m02) * weight
                    rty += (tnx * mat.m10 + tny * mat.m11 + tnz * mat.m12) * weight
                    rtz += (tnx * mat.m20 + tny * mat.m21 + tnz * mat.m22) * weight
                }

                idxWeights += fourMinusMaxWeights

                idxPositions -= 3

                normBuf[idxPositions] = rnx
                posBuf[idxPositions++] = rx
                normBuf[idxPositions] = rny
                posBuf[idxPositions++] = ry
                normBuf[idxPositions] = rnz
                posBuf[idxPositions++] = rz

                idxTangents -= 4

                tanBuf[idxTangents++] = rtx
                tanBuf[idxTangents++] = rty
                tanBuf[idxTangents++] = rtz

                //once again skipping the 4th component of the tangent
                idxTangents++
            }

            fvb.position(fvb.position() - bufLength)
            fvb.put(posBuf, 0, bufLength)
            fnb.position(fnb.position() - bufLength)
            fnb.put(normBuf, 0, bufLength)
            ftb.position(ftb.position() - tanLength)
            ftb.put(tanBuf, 0, tanLength)
        }

        vars.release()

        vb.updateData(fvb)
        nb.updateData(fnb)
        tb.updateData(ftb)


    }

    @Throws(IOException::class)
    override fun write(ex: JmeExporter) {
        super.write(ex)
        val oc = ex.getCapsule(this)
        oc.write(skeleton, "skeleton", null)

        oc.write(numberOfBonesParam, "numberOfBonesParam", null)
        oc.write(boneMatricesParam, "boneMatricesParam", null)
    }

    @Throws(IOException::class)
    override fun read(im: JmeImporter) {
        super.read(im)
        val `in` = im.getCapsule(this)
        skeleton = `in`.readSavable("skeleton", null) as Skeleton

        numberOfBonesParam = `in`.readSavable("numberOfBonesParam", null) as MatParamOverride
        boneMatricesParam = `in`.readSavable("boneMatricesParam", null) as MatParamOverride

        if (numberOfBonesParam == null) {
            numberOfBonesParam = MatParamOverride(VarType.Int, "NumberOfBones", null)
            boneMatricesParam = MatParamOverride(VarType.Matrix4Array, "BoneMatrices", null)
            getSpatial().addMatParamOverride(numberOfBonesParam)
            getSpatial().addMatParamOverride(boneMatricesParam)
        }
    }

    /**
     * Update the lists of animation targets.
     *
     * @param spatial the controlled spatial
     */
    private fun updateTargetsAndMaterials(spatial: Spatial?) {
        targets.clear()

        if (spatial is Node) {
//            findTargets(spatial as Node?)
            findTargets(spatial as Node)
        } else if (spatial is Geometry) {
//            findTargets(spatial as Geometry?)
            findTargets(spatial as Geometry)
        }
    }
}
