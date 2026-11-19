package com.panda.realsize

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a binary glTF 2.0 file (GLB) in memory: meshes, PBR materials and PNG textures.
 * The live camera builds every product model with it at run time from the real product photo.
 */
class GlbWriter {

    /** One drawable piece of a mesh: vertex arrays in metres plus the material it is drawn with. */
    class MeshPart(
        val positions: FloatArray,
        val normals: FloatArray,
        val textureCoordinates: FloatArray,
        val vertexColors: FloatArray?,
        val triangleIndices: IntArray,
        val materialIndex: Int
    )

    private val binaryBody = ByteArrayOutputStream()
    private val bufferViews = JSONArray()
    private val accessors = JSONArray()
    private val images = JSONArray()
    private val textures = JSONArray()
    private val materials = JSONArray()
    private val meshes = JSONArray()
    private val nodes = JSONArray()
    private val extensionsUsed = LinkedHashSet<String>()

    fun useExtension(extensionName: String) {
        extensionsUsed.add(extensionName)
    }

    /** Adds a PNG picture and returns its texture index. */
    fun addPngTexture(pngBytes: ByteArray): Int {
        val viewIndex = appendBufferView(pngBytes, target = null)
        images.put(JSONObject().put("bufferView", viewIndex).put("mimeType", "image/png"))
        textures.put(JSONObject().put("source", images.length() - 1).put("sampler", 0))
        return textures.length() - 1
    }

    /** Adds a glTF material object and returns its index. */
    fun addMaterial(material: JSONObject): Int {
        materials.put(material)
        return materials.length() - 1
    }

    /** Adds a named node holding one mesh made of the given parts. */
    fun addMeshNode(nodeName: String, parts: List<MeshPart>) {
        if (parts.isEmpty()) return
        val primitives = JSONArray()
        for (part in parts) {
            val attributes = JSONObject()
            attributes.put("POSITION", addFloatAccessor(part.positions, componentsPerElement = 3, withBounds = true))
            attributes.put("NORMAL", addFloatAccessor(part.normals, componentsPerElement = 3, withBounds = false))
            attributes.put("TEXCOORD_0", addFloatAccessor(part.textureCoordinates, componentsPerElement = 2, withBounds = false))
            part.vertexColors?.let { colors ->
                attributes.put("COLOR_0", addFloatAccessor(colors, componentsPerElement = 4, withBounds = false))
            }
            primitives.put(
                JSONObject()
                    .put("attributes", attributes)
                    .put("indices", addIndexAccessor(part.triangleIndices))
                    .put("material", part.materialIndex)
                    .put("mode", TRIANGLES)
            )
        }
        meshes.put(JSONObject().put("name", nodeName).put("primitives", primitives))
        nodes.put(JSONObject().put("name", nodeName).put("mesh", meshes.length() - 1))
    }

    /** Assembles the GLB container: 12-byte header, JSON chunk, binary chunk. */
    fun build(): ByteArray {
        val root = JSONObject()
        root.put("asset", JSONObject().put("version", "2.0").put("generator", "Panda Real Size"))
        val nodeIndices = JSONArray()
        for (nodeIndex in 0 until nodes.length()) nodeIndices.put(nodeIndex)
        root.put("scene", 0)
        root.put("scenes", JSONArray().put(JSONObject().put("nodes", nodeIndices)))
        root.put("nodes", nodes)
        root.put("meshes", meshes)
        if (materials.length() > 0) root.put("materials", materials)
        if (textures.length() > 0) {
            root.put("textures", textures)
            root.put("images", images)
            root.put(
                "samplers", JSONArray().put(
                    JSONObject()
                        .put("magFilter", LINEAR)
                        .put("minFilter", LINEAR_MIPMAP_LINEAR)
                        .put("wrapS", CLAMP_TO_EDGE)
                        .put("wrapT", CLAMP_TO_EDGE)
                )
            )
        }
        root.put("accessors", accessors)
        root.put("bufferViews", bufferViews)
        padTo4(binaryBody, padByte = 0)
        root.put("buffers", JSONArray().put(JSONObject().put("byteLength", binaryBody.size())))
        if (extensionsUsed.isNotEmpty()) root.put("extensionsUsed", JSONArray(extensionsUsed.toList()))

        val jsonChunk = ByteArrayOutputStream()
        jsonChunk.write(root.toString().toByteArray(Charsets.UTF_8))
        padTo4(jsonChunk, padByte = 0x20)
        val jsonBytes = jsonChunk.toByteArray()
        val binaryBytes = binaryBody.toByteArray()

        val totalLength = 12 + 8 + jsonBytes.size + 8 + binaryBytes.size
        val glb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        glb.putInt(GLB_MAGIC).putInt(2).putInt(totalLength)
        glb.putInt(jsonBytes.size).putInt(CHUNK_JSON).put(jsonBytes)
        glb.putInt(binaryBytes.size).putInt(CHUNK_BINARY).put(binaryBytes)
        return glb.array()
    }

    private fun appendBufferView(bytes: ByteArray, target: Int?): Int {
        padTo4(binaryBody, padByte = 0)
        val byteOffset = binaryBody.size()
        binaryBody.write(bytes)
        val view = JSONObject().put("buffer", 0).put("byteOffset", byteOffset).put("byteLength", bytes.size)
        if (target != null) view.put("target", target)
        bufferViews.put(view)
        return bufferViews.length() - 1
    }

    private fun addFloatAccessor(values: FloatArray, componentsPerElement: Int, withBounds: Boolean): Int {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (component in values) bytes.putFloat(component)
        val viewIndex = appendBufferView(bytes.array(), ARRAY_BUFFER)
        val accessor = JSONObject()
            .put("bufferView", viewIndex)
            .put("componentType", FLOAT)
            .put("count", values.size / componentsPerElement)
            .put("type", TYPE_NAMES.getValue(componentsPerElement))
        if (withBounds) {
            val minimum = DoubleArray(componentsPerElement) { Double.MAX_VALUE }
            val maximum = DoubleArray(componentsPerElement) { -Double.MAX_VALUE }
            for (valueIndex in values.indices) {
                val axis = valueIndex % componentsPerElement
                minimum[axis] = minOf(minimum[axis], values[valueIndex].toDouble())
                maximum[axis] = maxOf(maximum[axis], values[valueIndex].toDouble())
            }
            accessor.put("min", JSONArray(minimum.toList()))
            accessor.put("max", JSONArray(maximum.toList()))
        }
        accessors.put(accessor)
        return accessors.length() - 1
    }

    private fun addIndexAccessor(triangleIndices: IntArray): Int {
        val bytes = ByteBuffer.allocate(triangleIndices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (vertexIndex in triangleIndices) bytes.putInt(vertexIndex)
        val viewIndex = appendBufferView(bytes.array(), ELEMENT_ARRAY_BUFFER)
        accessors.put(
            JSONObject()
                .put("bufferView", viewIndex)
                .put("componentType", UNSIGNED_INT)
                .put("count", triangleIndices.size)
                .put("type", "SCALAR")
        )
        return accessors.length() - 1
    }

    private fun padTo4(stream: ByteArrayOutputStream, padByte: Int) {
        while (stream.size() % 4 != 0) stream.write(padByte)
    }

    companion object {
        private const val GLB_MAGIC = 0x46546C67
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BINARY = 0x004E4942
        private const val FLOAT = 5126
        private const val UNSIGNED_INT = 5125
        private const val ARRAY_BUFFER = 34962
        private const val ELEMENT_ARRAY_BUFFER = 34963
        private const val TRIANGLES = 4
        private const val LINEAR = 9729
        private const val LINEAR_MIPMAP_LINEAR = 9987
        private const val CLAMP_TO_EDGE = 33071
        private val TYPE_NAMES = mapOf(2 to "VEC2", 3 to "VEC3", 4 to "VEC4")
    }
}
