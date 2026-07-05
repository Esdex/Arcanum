/*
 * Portions of this file are derived from the Gallery project by IacobIonut01.
 * https://github.com/IacobIonut01/Gallery
 *
 * Copyright 2023 IacobIonut01
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications: filter matrices adapted for single-pass ColorMatrix pipeline,
 * added VarFilterDef descriptors, lighting/colour/effects groupings. — Arcanum project, 2026.
 */

package zip.arcanum.arcanum.gallery.editor.adjustments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness5
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.CropDin
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ── Variable filter descriptors ──────────────────────────────────────────────

data class VarFilterDef(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val min: Float,
    val max: Float,
    val default: Float,
    val allowNegative: Boolean = true
)

val lightingFilters = listOf(
    VarFilterDef("brightness", "Brightness", Icons.Outlined.Brightness5,     -1f, 1f, 0f),
    VarFilterDef("tone",       "Tone",       Icons.Outlined.Tonality,          -1f, 1f, 0f),
    VarFilterDef("contrast",   "Contrast",   Icons.Outlined.Contrast,          -1f, 1f, 0f),
    VarFilterDef("blackPoint", "Black Point",Icons.Outlined.RadioButtonUnchecked, -1f, 1f, 0f),
    VarFilterDef("whitePoint", "White Point",Icons.Outlined.Circle,            -1f, 1f, 0f),
    VarFilterDef("highlights", "Highlights", Icons.Outlined.Layers,            -1f, 1f, 0f),
    VarFilterDef("shadows",    "Shadows",    Icons.Outlined.FilterDrama,       -1f, 1f, 0f),
)

val colourFilters = listOf(
    VarFilterDef("saturation", "Saturation", Icons.Outlined.WaterDrop,  0f, 2f, 1f, allowNegative = false),
    VarFilterDef("warmth",     "Warmth",     Icons.Outlined.Thermostat, -1f, 1f, 0f),
    VarFilterDef("tint",       "Tint",       Icons.Outlined.Palette,    -1f, 1f, 0f),
    VarFilterDef("hue",        "Hue",        Icons.Outlined.Gradient,   -1f, 1f, 0f),
    VarFilterDef("blackWhite", "B&W",        Icons.Outlined.FilterBAndW, 0f, 1f, 0f, allowNegative = false),
    VarFilterDef("skinTone",   "Skin Tone",  Icons.Outlined.InvertColors,-1f, 1f, 0f),
    VarFilterDef("blueTone",   "Blue Tone",  Icons.Outlined.Waves,      -1f, 1f, 0f),
)

val effectsFilters = listOf(
    VarFilterDef("vignette",   "Vignette",  Icons.Outlined.Vignette, 0f, 1f, 0f, allowNegative = false),
    VarFilterDef("sharpness",  "Sharpness", Icons.Outlined.Details,  0f, 1f, 0f, allowNegative = false),
    VarFilterDef("denoise",    "Denoise",   Icons.Outlined.Brightness4,0f,1f, 0f, allowNegative = false),
    VarFilterDef("posterize",  "Posterize", Icons.Outlined.Texture,  0f, 1f, 0f, allowNegative = false),
    VarFilterDef("edges",      "Edges",     Icons.Outlined.GridOn,   0f, 1f, 0f, allowNegative = false),
    VarFilterDef("borders",    "Borders",   Icons.Outlined.CropDin,  0f, 1f, 0f, allowNegative = false),
    VarFilterDef("pop",        "Pop",       Icons.Outlined.Contrast, 0f, 1f, 0f, allowNegative = false),
)

val allVarFilters = lightingFilters + colourFilters + effectsFilters

fun defaultSliderValues(): Map<String, Float> =
    allVarFilters.associate { it.key to it.default }

// ── ColorMatrix computation ──────────────────────────────────────────────────

private fun saturationMatrix(s: Float) = FloatArray(20).also { m ->
    m[0]  = 0.213f*(1-s)+s; m[1]  = 0.715f*(1-s); m[2]  = 0.072f*(1-s)
    m[5]  = 0.213f*(1-s);   m[6]  = 0.715f*(1-s)+s; m[7]  = 0.072f*(1-s)
    m[10] = 0.213f*(1-s);   m[11] = 0.715f*(1-s);   m[12] = 0.072f*(1-s)+s
    m[18] = 1f
}

fun buildPreviewMatrix(sliders: Map<String, Float>, filterMatrix: FloatArray?): ColorMatrix {
    val cm = ColorMatrix() // identity

    // Brightness
    val br = sliders["brightness"] ?: 0f
    if (br != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f,0f,0f,0f,br*255,  0f,1f,0f,0f,br*255,  0f,0f,1f,0f,br*255,  0f,0f,0f,1f,0f
    )))

    // Contrast: slider is in [-1,1], 0 = neutral → map to scale factor ct where 0→1, ±1→2/0
    val ctRaw = sliders["contrast"] ?: 0f
    if (ctRaw != 0f) {
        val ct = 1f + ctRaw
        cm.timesAssign(ColorMatrix(floatArrayOf(
            ct,0f,0f,0f,128f*(1-ct),  0f,ct,0f,0f,128f*(1-ct),  0f,0f,ct,0f,128f*(1-ct),  0f,0f,0f,1f,0f
        )))
    }

    // Tone
    val tone = sliders["tone"] ?: 0f
    if (tone != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f,0f,0f,0f,tone*40,  0f,1f,0f,0f,tone*40,  0f,0f,1f,0f,tone*40,  0f,0f,0f,1f,0f
    )))

    // BlackPoint
    val bp = sliders["blackPoint"] ?: 0f
    if (bp != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f,0f,0f,0f,bp*50,  0f,1f,0f,0f,bp*50,  0f,0f,1f,0f,bp*50,  0f,0f,0f,1f,0f
    )))

    // WhitePoint
    val wp = sliders["whitePoint"] ?: 0f
    if (wp != 0f) {
        val scale = 1f / (1f - wp*0.5f).coerceIn(0.5f, 2f)
        cm.timesAssign(ColorMatrix(floatArrayOf(
            scale,0f,0f,0f,0f,  0f,scale,0f,0f,0f,  0f,0f,scale,0f,0f,  0f,0f,0f,1f,0f
        )))
    }

    // Highlights
    val hi = sliders["highlights"] ?: 0f
    if (hi != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f,0f,0f,0f,hi*30,  0f,1f,0f,0f,hi*30,  0f,0f,1f,0f,hi*30,  0f,0f,0f,1f,0f
    )))

    // Shadows
    val sh = sliders["shadows"] ?: 0f
    if (sh != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f,0f,0f,0f,sh*25,  0f,1f,0f,0f,sh*25,  0f,0f,1f,0f,sh*25,  0f,0f,0f,1f,0f
    )))

    // Saturation
    val sat = sliders["saturation"] ?: 1f
    if (sat != 1f) cm.timesAssign(ColorMatrix(saturationMatrix(sat)))

    // Warmth
    val wm = sliders["warmth"] ?: 0f
    if (wm != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f+wm*0.3f,0f,0f,0f,0f,  0f,1f+wm*0.1f,0f,0f,0f,  0f,0f,1f-wm*0.3f,0f,0f,  0f,0f,0f,1f,0f
    )))

    // Tint
    val tint = sliders["tint"] ?: 0f
    if (tint != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f+tint*0.1f,0f,0f,0f,0f,  0f,1f-tint*0.2f,0f,0f,0f,  0f,0f,1f+tint*0.1f,0f,0f,  0f,0f,0f,1f,0f
    )))

    // Hue
    val hue = sliders["hue"] ?: 0f
    if (hue != 0f) {
        val ang = hue * 180f * (Math.PI.toFloat() / 180f)
        val c = cos(ang); val s = sin(ang)
        val lR=0.213f; val lG=0.715f; val lB=0.072f
        cm.timesAssign(ColorMatrix(floatArrayOf(
            lR+c*(1-lR)+s*(-lR),   lG+c*(-lG)+s*(-lG),   lB+c*(-lB)+s*(1-lB),   0f,0f,
            lR+c*(-lR)+s*(0.143f), lG+c*(1-lG)+s*(0.140f),lB+c*(-lB)+s*(-0.283f),0f,0f,
            lR+c*(-lR)+s*(-(1-lR)),lG+c*(-lG)+s*(lG),    lB+c*(1-lB)+s*(lB),    0f,0f,
            0f,0f,0f,1f,0f
        )))
    }

    // BlackWhite (desaturation)
    val bw = sliders["blackWhite"] ?: 0f
    if (bw != 0f) cm.timesAssign(ColorMatrix(saturationMatrix(1f - bw)))

    // SkinTone
    val sk = sliders["skinTone"] ?: 0f
    if (sk != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f+sk*0.15f,0f,0f,0f,sk*8f,  0f,1f+sk*0.05f,0f,0f,0f,  0f,0f,1f-sk*0.05f,0f,0f,  0f,0f,0f,1f,0f
    )))

    // BlueTone
    val bt = sliders["blueTone"] ?: 0f
    if (bt != 0f) cm.timesAssign(ColorMatrix(floatArrayOf(
        1f,0f,0f,0f,0f,  0f,1f,0f,0f,0f,  0f,0f,1f+bt*0.4f,0f,bt*20f,  0f,0f,0f,1f,0f
    )))

    // Pop (contrast + saturation boost)
    val pop = sliders["pop"] ?: 0f
    if (pop != 0f) {
        val pc = 1f + pop * 0.5f; val ps = 1f + pop * 0.15f
        val pcm = ColorMatrix(floatArrayOf(pc,0f,0f,0f,128f*(1-pc), 0f,pc,0f,0f,128f*(1-pc), 0f,0f,pc,0f,128f*(1-pc), 0f,0f,0f,1f,0f))
        pcm.timesAssign(ColorMatrix(saturationMatrix(ps)))
        cm.timesAssign(pcm)
    }

    // Named filter (applied last)
    if (filterMatrix != null) cm.timesAssign(ColorMatrix(filterMatrix))

    return cm
}

// ── Non-matrix effects: applied to bitmap on save ───────────────────────────

val nonMatrixKeys = setOf("vignette", "sharpness", "denoise", "posterize", "edges", "borders")

// ── Named filter presets ─────────────────────────────────────────────────────

data class NamedFilter(val name: String, val matrix: FloatArray?)

private fun sat(s: Float): FloatArray = saturationMatrix(s)

private fun withSat(base: FloatArray, s: Float): FloatArray {
    val bm = ColorMatrix(base)
    bm.timesAssign(ColorMatrix(sat(s)))
    return bm.values
}

val namedFilters: List<NamedFilter> = listOf(
    NamedFilter("Original", null),
    NamedFilter("Lite", floatArrayOf(1.05f,0f,0f,0f,10f, 0f,1.05f,0f,0f,10f, 0f,0f,1.05f,0f,10f, 0f,0f,0f,1f,0f)),
    NamedFilter("Playa", floatArrayOf(1.15f,0.05f,0f,0f,15f, 0f,1.08f,0f,0f,10f, 0f,0f,0.92f,0f,5f, 0f,0f,0f,1f,0f)),
    NamedFilter("Honey", floatArrayOf(1.2f,0.1f,0f,0f,10f, 0f,1.05f,0f,0f,8f, 0f,0f,0.8f,0f,0f, 0f,0f,0f,1f,0f)),
    NamedFilter("Isla",  floatArrayOf(1.05f,0f,0.05f,0f,5f, 0f,1.1f,0f,0f,5f, 0.05f,0f,1.1f,0f,10f, 0f,0f,0f,1f,0f)),
    NamedFilter("Desert", withSat(floatArrayOf(1.1f,0.05f,0f,0f,10f, 0f,1.0f,0f,0f,5f, 0f,0f,0.9f,0f,0f, 0f,0f,0f,1f,0f), 0.85f)),
    NamedFilter("Clay",  floatArrayOf(1.1f,0.08f,0.02f,0f,8f, 0.02f,1.0f,0.02f,0f,5f, 0f,0f,0.88f,0f,5f, 0f,0f,0f,1f,0f)),
    NamedFilter("Palma", withSat(floatArrayOf(0.95f,0f,0f,0f,0f, 0f,1.12f,0f,0f,5f, 0f,0f,0.95f,0f,0f, 0f,0f,0f,1f,0f), 1.25f)),
    NamedFilter("Blush", floatArrayOf(1.1f,0.05f,0.05f,0f,12f, 0f,0.98f,0f,0f,5f, 0.02f,0f,1.02f,0f,8f, 0f,0f,0f,1f,0f)),
    NamedFilter("Alpaca",withSat(floatArrayOf(1.1f,0.05f,0f,0f,10f, 0f,1.02f,0f,0f,8f, 0f,0f,0.9f,0f,5f, 0f,0f,0f,1f,0f), 0.9f)),
    NamedFilter("Modena",floatArrayOf(1.15f,0.05f,0f,0f,5f, 0.02f,1.08f,0f,0f,5f, 0f,0f,0.95f,0f,0f, 0f,0f,0f,1f,0f)),
    NamedFilter("West",  withSat(floatArrayOf(0.95f,0f,0.05f,0f,5f, 0f,0.98f,0.02f,0f,5f, 0f,0.05f,1.05f,0f,10f, 0f,0f,0f,1f,0f), 0.8f)),
    NamedFilter("Metro", run {
        val c=1.3f; floatArrayOf(c*0.95f,0f,0.05f,0f,128f*(1-c), 0f,c*0.98f,0.02f,0f,128f*(1-c), 0f,0.02f,c*1.05f,0f,128f*(1-c), 0f,0f,0f,1f,0f)
    }),
    NamedFilter("Reel",  floatArrayOf(1.1f,0f,0f,0f,5f, 0f,1.05f,0.05f,0f,0f, 0f,0.05f,1.15f,0f,10f, 0f,0f,0f,1f,0f)),
    NamedFilter("Bazaar",withSat(floatArrayOf(1.15f,0.05f,0f,0f,5f, 0f,1.05f,0f,0f,3f, 0f,0f,0.92f,0f,0f, 0f,0f,0f,1f,0f), 1.2f)),
    NamedFilter("Ollie", withSat(floatArrayOf(0.95f,0.05f,0f,0f,15f, 0.02f,1.0f,0.03f,0f,12f, 0f,0.05f,0.92f,0f,10f, 0f,0f,0f,1f,0f), 0.75f)),
    NamedFilter("Onyx", run {
        val c=1.3f; val bw = FloatArray(20).also { m ->
            m[0]=0.33f; m[1]=0.33f; m[2]=0.33f; m[6]=0.33f; m[7]=0.33f; m[8]=0.33f; m[11]=0.33f; m[12]=0.33f; m[13]=0.33f; m[18]=1f
        }; val cm=ColorMatrix(bw); cm.timesAssign(ColorMatrix(floatArrayOf(c,0f,0f,0f,128f*(1-c), 0f,c,0f,0f,128f*(1-c), 0f,0f,c,0f,128f*(1-c), 0f,0f,0f,1f,0f))); cm.values
    }),
    NamedFilter("Eiffel",floatArrayOf(0.33f,0.33f,0.33f,0f,5f, 0.33f,0.33f,0.33f,0f,5f, 0.33f,0.33f,0.33f,0f,5f, 0f,0f,0f,1f,0f)),
    NamedFilter("Vogue", run {
        val c=1.25f; floatArrayOf(0.35f*c,0.33f*c,0.32f*c,0f,128f*(1-c)+3f, 0.33f*c,0.34f*c,0.33f*c,0f,128f*(1-c)+2f, 0.32f*c,0.33f*c,0.33f*c,0f,128f*(1-c), 0f,0f,0f,1f,0f)
    }),
    NamedFilter("Vista", run {
        val c=0.85f; floatArrayOf(0.33f*c,0.33f*c,0.33f*c,0f,128f*(1-c)+15f, 0.33f*c,0.33f*c,0.33f*c,0f,128f*(1-c)+15f, 0.33f*c,0.33f*c,0.33f*c,0f,128f*(1-c)+15f, 0f,0f,0f,1f,0f)
    }),
    NamedFilter("Astro", run {
        val s=0.3f; val cm=ColorMatrix(sat(s))
        cm.timesAssign(ColorMatrix(floatArrayOf(0.95f,0f,0f,0f,0f, 0f,0.97f,0f,0f,0f, 0f,0f,1.1f,0f,8f, 0f,0f,0f,1f,0f))); cm.values
    }),
    NamedFilter("Negative", floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f))
)

fun lerpColorMatrix(identity: ColorMatrix, target: ColorMatrix, t: Float): ColorMatrix {
    val v0 = identity.values
    val v1 = target.values
    return ColorMatrix(FloatArray(20) { i -> v0[i] + (v1[i] - v0[i]) * t })
}

fun sharpnessKernelSize(value: Float): Int {
    // Map [0, 1] → kernel [3, 9], must be odd
    val raw = (3 + value * 6).roundToInt().coerceIn(3, 9)
    return if (raw % 2 == 0) raw + 1 else raw
}
