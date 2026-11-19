package com.panda.realsize

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds a real 3D model (GLB) of a product from its straight-on photo.
 *
 * Model space: x = right, y = up along the surface, z = out of the surface towards the viewer,
 * metres. The surface (ceiling or wall) is the plane z = 0.
 *
 * Pieces of each model, by glTF node name:
 * - "shadow": soft contact shadow printed on the surface around the product.
 * - "sides":  the product's thickness, a stack of the photo's silhouette (so the outline is exact).
 * - "face":   the photo itself on the front, with metal / paint reflections.
 * - "lens", "halo": the lit lamp and its glare when the light is on.
 */
object ProductModels {

    enum class LightState { WARM, WHITE, OFF }

    class Spec(
        val widthMm: Float,
        val heightMm: Float,
        val category: String,
        val round: Boolean,
        val lensFraction: Float
    )

    private const val CORNER_FRACTION = 0.05f
    private const val SHADOW_TEXTURE_SIZE = 128
    private const val GLOW_TEXTURE_SIZE = 128
    private const val MATERIAL_MAP_MAX_SIZE = 256
    private const val SLICE_SPACING_MM = 0.7f
    private const val MAX_SLICES = 56

    /** How far each kind of product stands out of the surface, in millimetres. */
    fun depthMm(category: String): Float = when (category) {
        "spot" -> 4f
        "panel" -> 7f
        "track" -> 40f
        "holder" -> 25f
        "wallbox" -> 30f
        "power" -> 0f   // real CAD model: its own geometry, the outline sits on the surface
        else -> 4f
    }

    /**
     * Soft shadow on the surface under a product that has a real 3D model: its top-view outline
     * (the photo's transparency) blurred by [blurMm] and spread a little beyond the footprint.
     */
    fun buildContactShadow(spec: Spec, topView: Bitmap, blurMm: Float, opacity: Float): ByteArray {
        val writer = GlbWriter()
        writer.useExtension("KHR_materials_unlit")
        val spreadMm = blurMm * 1.5f
        val totalWidthMm = spec.widthMm + spreadMm * 2f
        val totalHeightMm = spec.heightMm + spreadMm * 2f
        val pixelsPerMm = 256f / max(totalWidthMm, totalHeightMm)
        val mapWidth = max(8, (totalWidthMm * pixelsPerMm).roundToInt())
        val mapHeight = max(8, (totalHeightMm * pixelsPerMm).roundToInt())
        val footprintWidth = max(1, (spec.widthMm * pixelsPerMm).roundToInt())
        val footprintHeight = max(1, (spec.heightMm * pixelsPerMm).roundToInt())
        val footprint = Bitmap.createScaledBitmap(topView, footprintWidth, footprintHeight, true)
        val footprintPixels = IntArray(footprintWidth * footprintHeight)
        footprint.getPixels(footprintPixels, 0, footprintWidth, 0, 0, footprintWidth, footprintHeight)
        if (footprint !== topView) footprint.recycle()

        val coverage = FloatArray(mapWidth * mapHeight)
        val offsetX = (mapWidth - footprintWidth) / 2
        val offsetY = (mapHeight - footprintHeight) / 2
        for (row in 0 until footprintHeight) {
            for (column in 0 until footprintWidth) {
                val targetRow = row + offsetY
                val targetColumn = column + offsetX
                if (targetRow in 0 until mapHeight && targetColumn in 0 until mapWidth) {
                    coverage[targetRow * mapWidth + targetColumn] = Color.alpha(footprintPixels[row * footprintWidth + column]) / 255f
                }
            }
        }
        val blurRadius = max(1, (blurMm * pixelsPerMm).roundToInt())
        repeat(2) {
            boxBlur(coverage, mapWidth, mapHeight, blurRadius, horizontal = true)
            boxBlur(coverage, mapWidth, mapHeight, blurRadius, horizontal = false)
        }
        val pixels = IntArray(mapWidth * mapHeight) { pixelIndex ->
            Color.argb((coverage[pixelIndex] * opacity * 255f).roundToInt().coerceIn(0, 255), 0, 0, 0)
        }
        val shadowMap = Bitmap.createBitmap(pixels, mapWidth, mapHeight, Bitmap.Config.ARGB_8888)
        val texture = writer.addPngTexture(png(shadowMap))
        val material = writer.addMaterial(
            JSONObject()
                .put("name", "shadow")
                .put("alphaMode", "BLEND")
                .put(
                    "pbrMetallicRoughness", JSONObject()
                        .put("baseColorFactor", numbers(0f, 0f, 0f, 1f))
                        .put("baseColorTexture", JSONObject().put("index", texture))
                )
                .put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
        )
        writer.addMeshNode(
            "shadow",
            listOf(quad(totalWidthMm / 2000f, totalHeightMm / 2000f, 0.0004f, material, vertexColor = null))
        )
        return writer.build()
    }

    private fun boxBlur(values: FloatArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lineLength = if (horizontal) width else height
        val lineCount = if (horizontal) height else width
        val line = FloatArray(lineLength)
        for (lineIndex in 0 until lineCount) {
            for (position in 0 until lineLength) {
                line[position] = values[if (horizontal) lineIndex * width + position else position * width + lineIndex]
            }
            var runningSum = 0f
            for (position in -radius..radius) runningSum += line[position.coerceIn(0, lineLength - 1)]
            for (position in 0 until lineLength) {
                values[if (horizontal) lineIndex * width + position else position * width + lineIndex] = runningSum / (radius * 2 + 1)
                runningSum += line[(position + radius + 1).coerceIn(0, lineLength - 1)] - line[(position - radius).coerceIn(0, lineLength - 1)]
            }
        }
    }

    fun build(spec: Spec, photo: Bitmap, variantLabel: String, lightState: LightState): ByteArray {
        val writer = GlbWriter()
        val halfWidth = spec.widthMm / 2000f
        val halfHeight = spec.heightMm / 2000f
        val depth = depthMm(spec.category) / 1000f

        addContactShadow(writer, spec, halfWidth, halfHeight, depth)

        val photoTexture = writer.addPngTexture(png(photo))
        val surfaceTexture = writer.addPngTexture(png(metallicRoughnessMap(photo, variantLabel)))
        val faceMaterial = writer.addMaterial(photoMaterial(photoTexture, surfaceTexture))

        val sliceCount = ceil(depth * 1000f / SLICE_SPACING_MM).toInt().coerceIn(1, MAX_SLICES)
        writer.addMeshNode("sides", listOf(sideSlices(halfWidth, halfHeight, depth, sliceCount, faceMaterial)))
        writer.addMeshNode("face", listOf(quad(halfWidth, halfHeight, depth, faceMaterial, vertexColor = null)))

        if (spec.lensFraction > 0f && lightState != LightState.OFF) {
            addLamp(writer, spec, halfWidth, halfHeight, depth, lightState)
        }
        return writer.build()
    }

    /** A thin red outline drawn just above the product while it is selected. */
    fun buildSelectionFrame(spec: Spec): ByteArray {
        val writer = GlbWriter()
        writer.useExtension("KHR_materials_unlit")
        val red = writer.addMaterial(
            JSONObject()
                .put("name", "selection")
                .put("pbrMetallicRoughness", JSONObject().put("baseColorFactor", numbers(0.86f, 0.1f, 0.14f, 1f)))
                .put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
        )
        val halfWidth = spec.widthMm / 2000f
        val halfHeight = spec.heightMm / 2000f
        val height = depthMm(spec.category) / 1000f + 0.0008f
        val gap = 0.0025f
        val lineWidth = 0.0025f
        val inner = outline(spec.round, halfWidth + gap, halfHeight + gap, pointCount = 72)
        val outer = outline(spec.round, halfWidth + gap + lineWidth, halfHeight + gap + lineWidth, pointCount = 72)
        val pointCount = inner.size / 2
        val positions = FloatArray(pointCount * 2 * 3)
        val normals = FloatArray(pointCount * 2 * 3)
        val textureCoordinates = FloatArray(pointCount * 2 * 2)
        for (pointIndex in 0 until pointCount) {
            val innerVertex = pointIndex * 2
            val outerVertex = pointIndex * 2 + 1
            positions[innerVertex * 3] = inner[pointIndex * 2]
            positions[innerVertex * 3 + 1] = inner[pointIndex * 2 + 1]
            positions[innerVertex * 3 + 2] = height
            positions[outerVertex * 3] = outer[pointIndex * 2]
            positions[outerVertex * 3 + 1] = outer[pointIndex * 2 + 1]
            positions[outerVertex * 3 + 2] = height
            normals[innerVertex * 3 + 2] = 1f
            normals[outerVertex * 3 + 2] = 1f
        }
        val triangleIndices = IntArray(pointCount * 6)
        for (pointIndex in 0 until pointCount) {
            val nextIndex = (pointIndex + 1) % pointCount
            val innerNow = pointIndex * 2
            val outerNow = pointIndex * 2 + 1
            val innerNext = nextIndex * 2
            val outerNext = nextIndex * 2 + 1
            val base = pointIndex * 6
            triangleIndices[base] = innerNow; triangleIndices[base + 1] = outerNow; triangleIndices[base + 2] = outerNext
            triangleIndices[base + 3] = innerNow; triangleIndices[base + 4] = outerNext; triangleIndices[base + 5] = innerNext
        }
        writer.addMeshNode("frame", listOf(GlbWriter.MeshPart(positions, normals, textureCoordinates, null, triangleIndices, red)))
        return writer.build()
    }

    // ---------- pieces ----------

    private fun addContactShadow(writer: GlbWriter, spec: Spec, halfWidth: Float, halfHeight: Float, depth: Float) {
        writer.useExtension("KHR_materials_unlit")
        val blur = 0.0025f + depth * 0.4f
        val spread = blur * 1.6f
        val opacity = if (depth < 0.01f) 0.38f else 0.5f
        val texture = writer.addPngTexture(
            png(softShapeTexture(spec.round, halfWidth, halfHeight, spread, blur, opacity))
        )
        val material = writer.addMaterial(
            JSONObject()
                .put("name", "shadow")
                .put("alphaMode", "BLEND")
                .put(
                    "pbrMetallicRoughness", JSONObject()
                        .put("baseColorFactor", numbers(0f, 0f, 0f, 1f))
                        .put("baseColorTexture", JSONObject().put("index", texture))
                )
                .put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
        )
        writer.addMeshNode("shadow", listOf(quad(halfWidth + spread, halfHeight + spread, 0.0004f, material, vertexColor = null)))
    }

    private fun addLamp(writer: GlbWriter, spec: Spec, halfWidth: Float, halfHeight: Float, depth: Float, lightState: LightState) {
        writer.useExtension("KHR_materials_unlit")
        writer.useExtension("KHR_materials_emissive_strength")
        val isPanel = spec.category == "panel"
        val lensRadius = spec.widthMm / 1000f * spec.lensFraction
        val lightColor = if (lightState == LightState.WHITE) floatArrayOf(0.93f, 0.97f, 1f) else floatArrayOf(1f, 0.8f, 0.55f)
        val coreColor = if (lightState == LightState.WHITE) floatArrayOf(0.97f, 0.99f, 1f) else floatArrayOf(1f, 0.93f, 0.8f)

        val haloRadius = if (isPanel) max(halfWidth, halfHeight) * 1.25f else lensRadius * 3.2f
        val haloTexture = writer.addPngTexture(png(glowTexture(plateau = 0f, peakAlpha = if (isPanel) 0.22f else 0.3f, gaussian = true)))
        val haloMaterial = writer.addMaterial(glowMaterial("halo", haloTexture, lightColor, emissiveStrength = 0f))
        writer.addMeshNode("halo", listOf(quad(haloRadius, haloRadius, depth + 0.0002f, haloMaterial, vertexColor = null)))

        val coreTexture = writer.addPngTexture(png(glowTexture(plateau = if (isPanel) 0.8f else 0.45f, peakAlpha = 1f, gaussian = false)))
        val coreMaterial = writer.addMaterial(glowMaterial("lens", coreTexture, coreColor, emissiveStrength = 4f))
        writer.addMeshNode("lens", listOf(quad(lensRadius, lensRadius, depth + 0.0003f, coreMaterial, vertexColor = null)))
    }

    private fun glowMaterial(name: String, texture: Int, color: FloatArray, emissiveStrength: Float): JSONObject {
        val material = JSONObject()
            .put("name", name)
            .put("alphaMode", "BLEND")
            .put(
                "pbrMetallicRoughness", JSONObject()
                    .put("baseColorFactor", numbers(color[0], color[1], color[2], 1f))
                    .put("baseColorTexture", JSONObject().put("index", texture))
            )
        val extensions = JSONObject().put("KHR_materials_unlit", JSONObject())
        if (emissiveStrength > 0f) {
            material.put("emissiveFactor", numbers(color[0], color[1], color[2]))
            material.put("emissiveTexture", JSONObject().put("index", texture))
            extensions.put("KHR_materials_emissive_strength", JSONObject().put("emissiveStrength", emissiveStrength.toDouble()))
        }
        return material.put("extensions", extensions)
    }

    private fun photoMaterial(photoTexture: Int, surfaceTexture: Int): JSONObject = JSONObject()
        .put("name", "product")
        .put("alphaMode", "MASK")
        .put("alphaCutoff", 0.5)
        .put(
            "pbrMetallicRoughness", JSONObject()
                .put("baseColorTexture", JSONObject().put("index", photoTexture))
                .put("metallicRoughnessTexture", JSONObject().put("index", surfaceTexture))
                .put("metallicFactor", 1.0)
                .put("roughnessFactor", 1.0)
        )

    /** A flat rectangle facing +z, with the picture's top edge at +y. */
    private fun quad(halfWidth: Float, halfHeight: Float, height: Float, material: Int, vertexColor: FloatArray?): GlbWriter.MeshPart {
        val positions = floatArrayOf(
            -halfWidth, -halfHeight, height,
            halfWidth, -halfHeight, height,
            halfWidth, halfHeight, height,
            -halfWidth, halfHeight, height
        )
        val normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val textureCoordinates = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        val colors = vertexColor?.let { color -> FloatArray(16) { component -> color[component % 4] } }
        return GlbWriter.MeshPart(positions, normals, textureCoordinates, colors, intArrayOf(0, 1, 2, 0, 2, 3), material)
    }

    /** Copies of the photo stacked from the surface up to the face; together they read as the product's sides. */
    private fun sideSlices(halfWidth: Float, halfHeight: Float, depth: Float, sliceCount: Int, material: Int): GlbWriter.MeshPart {
        val positions = FloatArray(sliceCount * 12)
        val normals = FloatArray(sliceCount * 12)
        val textureCoordinates = FloatArray(sliceCount * 8)
        val colors = FloatArray(sliceCount * 16)
        val triangleIndices = IntArray(sliceCount * 6)
        val bottom = 0.0006f
        val cornerUv = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        val cornerSign = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
        for (sliceIndex in 0 until sliceCount) {
            val fraction = sliceIndex.toFloat() / sliceCount
            val height = bottom + (depth - bottom) * fraction
            // sides are darker than the face, a little lighter towards the front edge
            val shade = 0.5f + 0.3f * fraction
            for (corner in 0 until 4) {
                val vertex = sliceIndex * 4 + corner
                positions[vertex * 3] = cornerSign[corner * 2] * halfWidth
                positions[vertex * 3 + 1] = cornerSign[corner * 2 + 1] * halfHeight
                positions[vertex * 3 + 2] = height
                normals[vertex * 3 + 2] = 1f
                textureCoordinates[vertex * 2] = cornerUv[corner * 2]
                textureCoordinates[vertex * 2 + 1] = cornerUv[corner * 2 + 1]
                colors[vertex * 4] = shade
                colors[vertex * 4 + 1] = shade
                colors[vertex * 4 + 2] = shade
                colors[vertex * 4 + 3] = 1f
            }
            val first = sliceIndex * 4
            val base = sliceIndex * 6
            triangleIndices[base] = first; triangleIndices[base + 1] = first + 1; triangleIndices[base + 2] = first + 2
            triangleIndices[base + 3] = first; triangleIndices[base + 4] = first + 2; triangleIndices[base + 5] = first + 3
        }
        return GlbWriter.MeshPart(positions, normals, textureCoordinates, colors, triangleIndices, material)
    }

    // ---------- textures ----------

    /**
     * glTF metal/roughness map (green = roughness, blue = metalness) guessed from the photo:
     * gold and champagne tones and, on silver variants, light grey tones are metal; the rest is paint.
     */
    private fun metallicRoughnessMap(photo: Bitmap, variantLabel: String): Bitmap {
        val longSide = max(photo.width, photo.height)
        val scale = min(1f, MATERIAL_MAP_MAX_SIZE.toFloat() / longSide)
        val mapWidth = max(4, (photo.width * scale).roundToInt())
        val mapHeight = max(4, (photo.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(photo, mapWidth, mapHeight, true)
        val pixels = IntArray(mapWidth * mapHeight)
        small.getPixels(pixels, 0, mapWidth, 0, 0, mapWidth, mapHeight)
        if (small !== photo) small.recycle()
        val silverVariant = variantLabel.contains("فضي")
        val hsv = FloatArray(3)
        for (pixelIndex in pixels.indices) {
            Color.colorToHSV(pixels[pixelIndex], hsv)
            val hue = hsv[0]
            val saturation = hsv[1]
            val brightness = hsv[2]
            val gold = hue in 28f..60f && saturation in 0.18f..0.8f && brightness > 0.3f
            val silver = silverVariant && saturation < 0.12f && brightness in 0.45f..0.93f
            val metal = gold || silver
            val roughness = if (metal) 0.3f else 0.5f
            val metalness = if (metal) 0.85f else 0f
            pixels[pixelIndex] = Color.argb(255, 0, (roughness * 255).roundToInt(), (metalness * 255).roundToInt())
        }
        return Bitmap.createBitmap(pixels, mapWidth, mapHeight, Bitmap.Config.ARGB_8888)
    }

    /** Black picture whose transparency follows the product outline, blurred: the contact shadow. */
    private fun softShapeTexture(round: Boolean, halfWidth: Float, halfHeight: Float, spread: Float, blur: Float, opacity: Float): Bitmap {
        val size = SHADOW_TEXTURE_SIZE
        val pixels = IntArray(size * size)
        val spanX = halfWidth + spread
        val spanY = halfHeight + spread
        val cornerRadius = min(halfWidth, halfHeight) * 2f * CORNER_FRACTION
        for (row in 0 until size) {
            for (column in 0 until size) {
                val x = ((column + 0.5f) / size * 2f - 1f) * spanX
                val y = (1f - (row + 0.5f) / size * 2f) * spanY
                val distance = if (round) ellipseDistance(x, y, halfWidth, halfHeight)
                else roundedRectDistance(x, y, halfWidth, halfHeight, cornerRadius)
                val coverage = 1f - smoothStep(-blur * 0.5f, blur, distance)
                val alpha = (coverage * opacity * 255f).roundToInt().coerceIn(0, 255)
                pixels[row * size + column] = Color.argb(alpha, 0, 0, 0)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** White disc fading out towards its rim: the lit lens (flat core) or its glare (gaussian). */
    private fun glowTexture(plateau: Float, peakAlpha: Float, gaussian: Boolean): Bitmap {
        val size = GLOW_TEXTURE_SIZE
        val pixels = IntArray(size * size)
        for (row in 0 until size) {
            for (column in 0 until size) {
                val x = (column + 0.5f) / size * 2f - 1f
                val y = (row + 0.5f) / size * 2f - 1f
                val radius = hypot(x, y)
                val strength = if (gaussian) exp(-radius * radius * 5f) * (1f - smoothStep(0.8f, 1f, radius))
                else 1f - smoothStep(plateau, 1f, radius)
                val alpha = (strength * peakAlpha * 255f).roundToInt().coerceIn(0, 255)
                pixels[row * size + column] = Color.argb(alpha, 255, 255, 255)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    // ---------- small helpers ----------

    /** Points (x, y pairs) around an ellipse or a rounded rectangle. */
    private fun outline(round: Boolean, halfWidth: Float, halfHeight: Float, pointCount: Int): FloatArray {
        val points = FloatArray(pointCount * 2)
        val cornerRadius = min(halfWidth, halfHeight) * 2f * CORNER_FRACTION
        for (pointIndex in 0 until pointCount) {
            val angle = Math.PI * 2.0 * pointIndex / pointCount
            val directionX = cos(angle).toFloat()
            val directionY = sin(angle).toFloat()
            if (round) {
                points[pointIndex * 2] = directionX * halfWidth
                points[pointIndex * 2 + 1] = directionY * halfHeight
            } else {
                // walk outwards along the ray until it leaves the rounded rectangle
                var low = 0f
                var high = halfWidth + halfHeight
                repeat(24) {
                    val middle = (low + high) / 2f
                    if (roundedRectDistance(directionX * middle, directionY * middle, halfWidth, halfHeight, cornerRadius) < 0f) low = middle else high = middle
                }
                points[pointIndex * 2] = directionX * low
                points[pointIndex * 2 + 1] = directionY * low
            }
        }
        return points
    }

    private fun ellipseDistance(x: Float, y: Float, halfWidth: Float, halfHeight: Float): Float =
        (hypot(x / halfWidth, y / halfHeight) - 1f) * min(halfWidth, halfHeight)

    private fun roundedRectDistance(x: Float, y: Float, halfWidth: Float, halfHeight: Float, cornerRadius: Float): Float {
        val insetX = abs(x) - (halfWidth - cornerRadius)
        val insetY = abs(y) - (halfHeight - cornerRadius)
        val outside = hypot(max(insetX, 0f), max(insetY, 0f))
        val inside = min(max(insetX, insetY), 0f)
        return outside + inside - cornerRadius
    }

    private fun smoothStep(edgeLow: Float, edgeHigh: Float, input: Float): Float {
        val clamped = ((input - edgeLow) / (edgeHigh - edgeLow)).coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private fun numbers(vararg components: Float): JSONArray {
        val array = JSONArray()
        for (component in components) array.put(component.toDouble())
        return array
    }

    private fun png(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
