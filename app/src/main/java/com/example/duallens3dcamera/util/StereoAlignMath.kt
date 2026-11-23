package com.example.duallens3dcamera.util

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Pure-Kotlin numerics for stereo correction of a wide/ultrawide pair. Mirrors
 * tools/stereo_fix/stereo_fix.py (refine_alignment and helpers) so results can be checked against
 * the Python reference in unit tests. No Android or OpenCV dependencies.
 *
 * Model, mapping ultrawide pixels (distorted) to wide pixels:
 *   n_d = (p_d - c) / R,   n_d = n_u * (1 + k * |n_u|^2)     (OpenCV k1, R = half diagonal)
 *   p_u = c + R * n_u
 *   wide = [[a, -b], [b, a]] * p_u + (tx, ty)
 * Scale, rotation and ty are fitted from vertical errors only, so horizontal parallax (depth)
 * is preserved; tx is chosen afterwards to place the stereo window.
 */
object StereoAlignMath {

    data class Model(
        val a: Double, val b: Double, val ty: Double, val k: Double,
        val cx: Double, val cy: Double, val r: Double
    ) {
        val scale: Double get() = hypot(a, b)
        val rotationDeg: Double get() = Math.toDegrees(kotlin.math.atan2(b, a))
    }

    class Fit(
        val model: Model,
        val inliers: BooleanArray,
        val verticalResiduals: DoubleArray,
        /** Ultrawide points after removing distortion. */
        val corrX: DoubleArray,
        val corrY: DoubleArray
    ) {
        val inlierCount: Int get() = inliers.count { it }
    }

    /** Distorted ultrawide pixels -> corrected pixels (fixed-point inverse of the k1 model). */
    fun undistort(x: DoubleArray, y: DoubleArray, k: Double, cx: Double, cy: Double, r: Double): Pair<DoubleArray, DoubleArray> {
        val ox = DoubleArray(x.size)
        val oy = DoubleArray(y.size)
        for (i in x.indices) {
            val ndx = (x[i] - cx) / r
            val ndy = (y[i] - cy) / r
            var ux = ndx
            var uy = ndy
            repeat(10) {
                val f = 1.0 + k * (ux * ux + uy * uy)
                ux = ndx / f
                uy = ndy / f
            }
            ox[i] = cx + ux * r
            oy[i] = cy + uy * r
        }
        return ox to oy
    }

    private class VFit(val a: Double, val b: Double, val ty: Double, val use: BooleanArray, val res: DoubleArray)

    /** IRLS on y_ref = b*x + a*y + ty (linear in a, b, ty), as in stereo_fix._vertical_fit. */
    private fun verticalFit(refY: DoubleArray, x: DoubleArray, y: DoubleArray, start: BooleanArray): VFit {
        var use = start.copyOf()
        var a = 1.0; var b = 0.0; var ty = 0.0
        val n = refY.size
        val res = DoubleArray(n)
        repeat(8) {
            // Normal equations for columns [x, y, 1] -> solution (b, a, ty).
            var sxx = 0.0; var sxy = 0.0; var sx = 0.0; var syy = 0.0; var sy = 0.0; var s1 = 0.0
            var tx0 = 0.0; var ty0 = 0.0; var t1 = 0.0
            for (i in 0 until n) if (use[i]) {
                sxx += x[i] * x[i]; sxy += x[i] * y[i]; sx += x[i]
                syy += y[i] * y[i]; sy += y[i]; s1 += 1.0
                tx0 += x[i] * refY[i]; ty0 += y[i] * refY[i]; t1 += refY[i]
            }
            val sol = solve3(
                arrayOf(doubleArrayOf(sxx, sxy, sx), doubleArrayOf(sxy, syy, sy), doubleArrayOf(sx, sy, s1)),
                doubleArrayOf(tx0, ty0, t1)
            ) ?: return VFit(a, b, ty, use, res)
            b = sol[0]; a = sol[1]; ty = sol[2]
            for (i in 0 until n) res[i] = refY[i] - (b * x[i] + a * y[i] + ty)
            val inl = (0 until n).filter { use[it] }.map { res[it] }
            val med = median(inl)
            val mad = median(inl.map { abs(it - med) }) * 1.4826 + 1e-6
            val thr = max(3.0 * mad, 1.0)
            use = BooleanArray(n) { abs(res[it]) < thr }
        }
        return VFit(a, b, ty, use, res)
    }

    /** Coarse k grid (-0.15..0.15) then a fine step, as in stereo_fix.best_k. */
    val K_COARSE: DoubleArray = DoubleArray(25) { Math.round((-0.15 + 0.0125 * it) * 1e4) / 1e4 }

    fun refine(
        refX: DoubleArray, refY: DoubleArray, movX: DoubleArray, movY: DoubleArray,
        baseInliers: BooleanArray, movW: Int, movH: Int, fitDistortion: Boolean = true
    ): Fit {
        val cx = movW / 2.0
        val cy = movH / 2.0
        val r = hypot(movW.toDouble(), movH.toDouble()) / 2.0

        fun evaluate(k: Double): Pair<Double, Fit> {
            val (ux, uy) = undistort(movX, movY, k, cx, cy, r)
            val v = verticalFit(refY, ux, uy, baseInliers)
            val score = median((v.res.indices).filter { v.use[it] }.map { abs(v.res[it]) })
            return score to Fit(Model(v.a, v.b, v.ty, k, cx, cy, r), v.use, v.res, ux, uy)
        }

        if (!fitDistortion) return evaluate(0.0).second
        fun argmin(ks: List<Double>): Double {
            var best = ks[0]; var bestScore = evaluate(best).first
            for (k in ks.drop(1)) {
                val s = evaluate(k).first
                if (s < bestScore) { best = k; bestScore = s }
            }
            return best
        }
        val coarse = argmin(K_COARSE.toList())
        val k = argmin(List(21) { coarse + 0.00125 * (it - 10) })
        return evaluate(k).second
    }

    /** x_ref - (a*x_corr - b*y_corr): horizontal shift plus per-point parallax. */
    fun horizontalOffsets(refX: DoubleArray, corrX: DoubleArray, corrY: DoubleArray, a: Double, b: Double): DoubleArray =
        DoubleArray(refX.size) { refX[it] - (a * corrX[it] - b * corrY[it]) }

    /**
     * Horizontal shift placing the nearest content (percentile [windowPct] of parallax) at the
     * screen plane, everything else behind it.
     */
    fun windowShift(offsetsInliers: DoubleArray, wideIsLeft: Boolean, windowPct: Double = 98.0): Double =
        if (wideIsLeft) percentile(offsetsInliers, windowPct) else percentile(offsetsInliers, 100.0 - windowPct)

    /** numpy.percentile (linear interpolation). */
    fun percentile(values: DoubleArray, p: Double): Double {
        require(values.isNotEmpty())
        val s = values.sortedArray()
        val pos = p / 100.0 * (s.size - 1)
        val lo = floor(pos).toInt().coerceIn(0, s.size - 1)
        val hi = (lo + 1).coerceAtMost(s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun median(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else percentile(values.toDoubleArray(), 50.0)

    /**
     * Histogram-matching lookup table: maps src levels so their CDF follows ref's
     * (np.interp(src_cdf, ref_cdf, 0..255), clipped and truncated to 8 bits).
     */
    fun histogramMatchLut(srcHist: DoubleArray, refHist: DoubleArray): IntArray {
        require(srcHist.size == 256 && refHist.size == 256)
        val sTot = srcHist.sum().takeIf { it > 0 } ?: return IntArray(256) { it }
        val rTot = refHist.sum().takeIf { it > 0 } ?: return IntArray(256) { it }
        val sCdf = DoubleArray(256); val rCdf = DoubleArray(256)
        var acc = 0.0
        for (i in 0 until 256) { acc += srcHist[i]; sCdf[i] = acc / sTot }
        acc = 0.0
        for (i in 0 until 256) { acc += refHist[i]; rCdf[i] = acc / rTot }
        return IntArray(256) { i -> floor(interp(sCdf[i], rCdf).coerceIn(0.0, 255.0)).toInt() }
    }

    /** np.interp(x, xp, arange(len(xp))) for non-decreasing xp. */
    private fun interp(x: Double, xp: DoubleArray): Double {
        val n = xp.size
        if (x <= xp[0]) return 0.0
        if (x >= xp[n - 1]) return (n - 1).toDouble()
        // Last j with xp[j] <= x (searchsorted 'right' - 1); then xp[j] <= x < xp[j+1].
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val j = lo
        return j + (x - xp[j]) / (xp[j + 1] - xp[j])
    }

    /**
     * Shrinks a rectangle until it holds only valid pixels (stereo_fix.largest_valid_rect).
     * [valid] is row-major, size w*h. Returns [x0, y0, x1, y1) .
     */
    fun largestValidRect(valid: BooleanArray, w: Int, h: Int): IntArray {
        var x0 = 0; var y0 = 0; var x1 = w; var y1 = h
        while (x1 - x0 > 16 && y1 - y0 > 16) {
            var left = 0; var right = 0; var top = 0; var bottom = 0
            var all = true
            for (yy in y0 until y1) {
                val row = yy * w
                for (xx in x0 until x1) if (!valid[row + xx]) { all = false }
                if (!valid[row + x0]) left++
                if (!valid[row + x1 - 1]) right++
            }
            if (all) break
            for (xx in x0 until x1) {
                if (!valid[y0 * w + xx]) top++
                if (!valid[(y1 - 1) * w + xx]) bottom++
            }
            // Python max() over dict keys in order l, r, t, b keeps the first maximum.
            val counts = intArrayOf(left, right, top, bottom)
            var side = 0
            for (i in 1 until 4) if (counts[i] > counts[side]) side = i
            when (side) { 0 -> x0++; 1 -> x1--; 2 -> y0++; else -> y1-- }
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val a = Array(3) { r -> doubleArrayOf(m[r][0], m[r][1], m[r][2], v[r]) }
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-12) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            for (r in 0 until 3) if (r != col) {
                val f = a[r][col] / a[col][col]
                for (c in col until 4) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(3) { a[it][3] / a[it][it] }
    }
}
