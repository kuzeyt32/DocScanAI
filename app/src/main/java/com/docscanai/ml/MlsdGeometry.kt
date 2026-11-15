package com.docscanai.ml

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ============================================================================================
 * SUBTASK H30 — M-LSD post-processing, ported to plain Kotlin.
 * ============================================================================================
 *
 * This is the on-device counterpart of `tooling/h30_mlsd.py`, which is itself a faithful port of
 * NAVER's `pred_squares` (M-LSD, Apache-2.0). The numpy reference is validated by
 * `tooling/h30_mlsd_test.py` against synthetic maps whose answer is known by construction
 * (rectangle -> exactly four lines and one square, diagonal -> one line and no square, T-junction
 * -> three lines and no square, empty -> nothing, noise -> bounded and deterministic); this file
 * follows the same algorithm step for step so the two can be compared without guessing.
 *
 * **Deviations from NAVER, all deliberate and all also present in the numpy reference:**
 *  - deterministic top-k (stable descending order) instead of `tf.math.top_k`, whose tie order is
 *    unspecified — the device must be reproducible;
 *  - bounds-safe accumulator neighbourhood reads;
 *  - identical merged segments de-duplicated, and the square enumeration capped at
 *    [MAX_SQUARE_CANDIDATES] (the reference is combinatorially unbounded on a dense junction map);
 *  - non-finite intersections clamped instead of being cast to an arbitrary int.
 *
 * Coordinates are `(x, y)` in **frame pixels** for everything this module returns; the model's
 * maps are half the input edge, so [mapToFrame] doubles.
 */
object MlsdGeometry {

    /** Reference `dist_thr`: shorter junction displacements are not a usable line. */
    const val DIST_THR = 20.0

    /** Hard bound on square candidates examined per frame (see class KDoc). */
    const val MAX_SQUARE_CANDIDATES = 200_000

    private const val D_QUANT = 1
    private const val THETA_QUANT = 2
    private const val ACC_D_MAX = 512
    private const val ACC_THETA_MAX = 360

    /** NAVER's `pred_squares` defaults. */
    data class Params(
        val score: Double = 0.06,
        val outsideRatio: Double = 0.28,
        val insideRatio: Double = 0.45,
        val wOverlap: Double = 0.0,
        val wDegree: Double = 1.95,
        val wLength: Double = 0.0,
        val wArea: Double = 1.86,
        val wCenter: Double = 0.14
    )

    data class Pt(val x: Double, val y: Double)

    data class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
        val length: Double get() = hypot(x2 - x1, y2 - y1)
        val midX: Double get() = (x1 + x2) / 2.0
        val midY: Double get() = (y1 + y2) / 2.0
    }

    data class Square(val corners: List<Pt>, val score: Double, val support: Double)

    // ======================================================================================
    // 1) Junction / displacement maps -> segments (reference `pred_squares` lines 99-126)
    // ======================================================================================

    /**
     * Reads one segment per junction above [scoreThr] whose displacement is longer than
     * [distThr]. `junctions` are `(y, x)` cells into the half-resolution maps, exactly as the
     * graph emits them; the returned segments are in **map space**.
     */
    fun decodeJunctions(
        junctions: IntArray,
        scores: FloatArray,
        vmap: FloatArray,
        mapH: Int,
        mapW: Int,
        scoreThr: Double = 0.10
    ): List<Seg> {
        val count = min(junctions.size / 2, scores.size)
        if (count == 0 || mapH <= 0 || mapW <= 0) return emptyList()
        val out = ArrayList<Seg>(count)
        for (i in 0 until count) {
            val y = junctions[i * 2]
            val x = junctions[i * 2 + 1]
            if (y < 0 || x < 0 || y >= mapH || x >= mapW) continue
            if (scores[i] <= scoreThr) continue
            val base = (y * mapW + x) * 4
            if (base + 3 >= vmap.size) continue
            val sx = vmap[base]
            val sy = vmap[base + 1]
            val ex = vmap[base + 2]
            val ey = vmap[base + 3]
            if (!sx.isFinite() || !sy.isFinite() || !ex.isFinite() || !ey.isFinite()) continue
            // distance between the two displaced endpoints must exceed dist_thr
            if (hypot(sx - ex, sy - ey) <= DIST_THR) continue
            out.add(
                Seg(
                    x1 = x + sx.toDouble(), y1 = y + sy.toDouble(),
                    x2 = x + ex.toDouble(), y2 = y + ey.toDouble()
                )
            )
        }
        return out
    }

    // ======================================================================================
    // 2) Hough quantisation + 5x5 NMS + top-k (reference lines 127-215)
    // ======================================================================================

    /**
     * Collapses collinear segments into one line per voted `(distance, theta)` cell and returns
     * at most `topK` of them.
     */
    fun mergeSegments(raw: List<Seg>, topK: Int): List<Seg> {
        if (raw.isEmpty()) return emptyList()

        val n = raw.size
        val d = DoubleArray(n)
        val theta = DoubleArray(n)
        for (i in 0 until n) {
            val s = raw[i]
            val diffX = s.x1 - s.x2
            val diffY = s.y1 - s.y2
            // Line through the segment: a*x + b*y = c, distance to the origin.
            val a = diffY
            val b = -diffX
            val c = a * s.x1 + b * s.y1
            d[i] = abs(c) / sqrt(a * a + b * b + 1e-10)
            var t = atan2(diffX, diffY) * 180.0 / Math.PI
            if (t < 0.0) t += 180.0
            theta[i] = t
        }

        val cellD = IntArray(n) { (d[it] / D_QUANT).toInt() }
        val cellT = IntArray(n) { (theta[it] / THETA_QUANT).toInt() }
        val accH = max(ACC_D_MAX / D_QUANT + 1, (cellD.maxOrNull() ?: 0) + 1)
        val accW = max(ACC_THETA_MAX / THETA_QUANT + 1, (cellT.maxOrNull() ?: 0) + 1)
        val acc = IntArray(accH * accW)
        val idxMap = IntArray(accH * accW) { -1 }

        // First index per quantised cell and the count of segments in it.
        val cellKey = HashMap<Long, Int>(n * 2)
        for (i in 0 until n) {
            val key = cellD[i].toLong() * 100_000L + cellT[i]
            val first = cellKey[key]
            if (first == null) cellKey[key] = i
            val cell = cellD[i] * accW + cellT[i]
            acc[cell] += 1
        }
        for ((key, first) in cellKey) {
            val cd = (key / 100_000L).toInt()
            val ct = (key % 100_000L).toInt()
            idxMap[cd * accW + ct] = first
        }
        // Publish the maximum count per cell (the reference writes `counts`).
        val counts = HashMap<Long, Int>(n * 2)
        for (i in 0 until n) {
            val key = cellD[i].toLong() * 100_000L + cellT[i]
            counts[key] = (counts[key] ?: 0) + 1
        }
        for ((key, c) in counts) {
            val cd = (key / 100_000L).toInt()
            val ct = (key % 100_000L).toInt()
            acc[cd * accW + ct] = c
        }

        // 5x5 max-pool NMS (padding = same, i.e. neighbourhood-clamped at the border).
        val keep = BooleanArray(acc.size)
        for (y in 0 until accH) {
            for (x in 0 until accW) {
                val v = acc[y * accW + x]
                if (v <= 0) continue
                var isMax = true
                var dy = -2
                while (dy <= 2 && isMax) {
                    var dx = -2
                    while (dx <= 2) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in 0 until accH && nx in 0 until accW) {
                            if (acc[ny * accW + nx] > v) {
                                isMax = false
                                break
                            }
                        }
                        dx++
                    }
                    dy++
                }
                if (isMax) keep[y * accW + x] = true
            }
        }

        // Deterministic top-k over the kept peaks (stable by cell index).
        val peaks = ArrayList<Int>()
        for (cell in acc.indices) if (keep[cell] && acc[cell] > 0) peaks.add(cell)
        peaks.sortWith(compareByDescending<Int> { acc[it] }.thenBy { it })
        val limit = min(max(topK, 1), peaks.size)

        // Segments grouped by cell, so a peak can pull its neighbours' endpoints.
        val byCell = HashMap<Int, MutableList<Int>>(n * 2)
        for (i in 0 until n) {
            val cell = cellD[i] * accW + cellT[i]
            byCell.getOrPut(cell) { ArrayList(2) }.add(i)
        }

        val merged = ArrayList<Seg>(limit)
        val seen = HashSet<String>(limit * 2)
        for (p in 0 until limit) {
            val cell = peaks[p]
            val y = cell / accW
            val x = cell % accW
            val group = ArrayList<Int>(8)
            for (dy in -2..2) {
                val ny = y + dy
                if (ny < 0 || ny >= accH) continue
                for (dx in -2..2) {
                    val nx = x + dx
                    if (nx < 0 || nx >= accW) continue
                    val nc = ny * accW + nx
                    val indice = idxMap[nc]
                    if (indice < 0) continue
                    group.add(indice)
                    val cnt = acc[nc]
                    if (cnt > 1) {
                        val siblings = byCell[nc] ?: continue
                        var taken = 1
                        for (sib in siblings) {
                            if (sib == indice) continue
                            group.add(sib)
                            taken++
                            if (taken >= cnt) break
                        }
                    }
                }
            }
            if (group.isEmpty()) continue
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (gi in group) {
                val s = raw[gi]
                minX = min(minX, min(s.x1, s.x2))
                maxX = max(maxX, max(s.x1, s.x2))
                minY = min(minY, min(s.y1, s.y2))
                maxY = max(maxY, max(s.y1, s.y2))
            }
            val peakIndex = idxMap[cell]
            val deg = if (peakIndex >= 0) theta[peakIndex] else theta[group[0]]
            val seg = if (deg >= 90.0) Seg(minX, maxY, maxX, minY) else Seg(minX, minY, maxX, maxY)
            val key = "${(seg.x1 * 10).toInt()},${(seg.y1 * 10).toInt()}," +
                "${(seg.x2 * 10).toInt()},${(seg.y2 * 10).toInt()}"
            if (seen.add(key)) merged.add(seg)
        }
        return merged
    }

    // ======================================================================================
    // 3) Segments -> squares (reference lines 216-410 + scoring 436-560)
    // ======================================================================================

    /**
     * Enumerates closed quads from the merged lines and scores them with NAVER's metric.
     * Returns squares in NAVER's corner order (0 = top-left, 1 = top-right, 2 = bottom-right,
     * 3 = bottom-left), best score first. `truncated` reports whether [MAX_SQUARE_CANDIDATES]
     * fired (the result is then partial, which the caller must treat as "no evidence").
     */
    fun findSquares(
        merged: List<Seg>,
        inputEdge: Int,
        params: Params = Params(),
        maxCandidates: Int = MAX_SQUARE_CANDIDATES
    ): Pair<List<Square>, Boolean> {
        if (merged.size < 2) return emptyList<Square>() to false
        val n = merged.size
        val startX = DoubleArray(n) { merged[it].x1 }
        val startY = DoubleArray(n) { merged[it].y1 }
        val endX = DoubleArray(n) { merged[it].x2 }
        val endY = DoubleArray(n) { merged[it].y2 }
        val centerX = DoubleArray(n) { (startX[it] + endX[it]) / 2.0 }
        val centerY = DoubleArray(n) { (startY[it] + endY[it]) / 2.0 }
        val distSeg = DoubleArray(n) { hypot(startX[it] - endX[it], startY[it] - endY[it]) }

        val a = DoubleArray(n) { startY[it] - endY[it] }
        val b = DoubleArray(n) { -(startX[it] - endX[it]) }
        val c = DoubleArray(n) { a[it] * startX[it] + b[it] * startY[it] }

        // Intersections (truncated to int exactly like the reference).
        val interX = Array(n) { IntArray(n) }
        val interY = Array(n) { IntArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                val det = a[i] * b[j] - a[j] * b[i]
                var x = 0.0
                var y = 0.0
                if (abs(det) > 1e-10) {
                    x = (c[i] * b[j] - c[j] * b[i]) / det
                    y = (a[i] * c[j] - a[j] * c[i]) / det
                }
                interX[i][j] = if (!x.isFinite()) 1_000_000 else x.toInt()
                interY[i][j] = if (!y.isFinite()) 1_000_000 else y.toInt()
            }
        }

        val cornerDict = Array(4) { ArrayList<IntArray>() } // {x, y, i, j}
        var evaluated = 0
        var truncated = false

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val ix = interX[i][j]
                val iy = interY[i][j]
                val deg1 = degTo(ix, iy, centerX[i], centerY[i])
                val deg2 = degTo(ix, iy, centerX[j], centerY[j])
                val lo = min(deg1, deg2)
                val hi = max(deg1, deg2)
                var diff = abs(deg1 - deg2)
                if (diff > 180.0) diff = 360.0 - diff
                if (diff <= 60.0 || diff >= 120.0) continue

                val d1Start = hypot(ix - startX[i], iy - startY[i])
                val d1End = hypot(ix - endX[i], iy - endY[i])
                val d1Min = min(d1Start, d1End)
                val d1Max = max(d1Start, d1End)
                val d2Start = hypot(ix - startX[j], iy - startY[j])
                val d2End = hypot(ix - endX[j], iy - endY[j])
                val d2Min = min(d2Start, d2End)
                val d2Max = max(d2Start, d2End)

                val ok1 = (d1Max >= distSeg[i] && d1Min <= distSeg[i] * params.outsideRatio) ||
                    (d1Max <= distSeg[i] && d1Min <= distSeg[i] * params.insideRatio)
                val ok2 = (d2Max >= distSeg[j] && d2Min <= distSeg[j] * params.outsideRatio) ||
                    (d2Max <= distSeg[j] && d2Min <= distSeg[j] * params.insideRatio)
                if (!ok1 || !ok2) continue

                val info = when {
                    (lo >= 0.0 && lo <= 45.0 && hi >= 45.0 && hi <= 120.0) ||
                        (hi >= 315.0 && lo >= 45.0 && lo <= 120.0) -> 0
                    lo >= 45.0 && lo <= 125.0 && hi >= 125.0 && hi <= 225.0 -> 1
                    lo >= 125.0 && lo <= 225.0 && hi >= 225.0 && hi <= 315.0 -> 2
                    (lo >= 0.0 && lo <= 45.0 && hi >= 225.0 && hi <= 315.0) ||
                        (hi >= 315.0 && lo >= 225.0 && lo <= 315.0) -> 3
                    else -> -1
                }
                if (info < 0) continue
                cornerDict[info].add(intArrayOf(ix, iy, i, j))
            }
        }

        // Junction-graph walk: a corner may only join one that shares one of its lines.
        val byLine = Array(4) { HashMap<Int, MutableList<IntArray>>() }
        for (cls in 0 until 4) {
            for (corner in cornerDict[cls]) {
                byLine[cls].getOrPut(corner[2]) { ArrayList() }.add(corner)
                byLine[cls].getOrPut(corner[3]) { ArrayList() }.add(corner)
            }
        }

        val quads = ArrayList<DoubleArray>()   // 8 doubles: x0,y0 .. x3,y3
        val connects = ArrayList<IntArray>()   // 4 segment indices 01,12,23,30
        val segPairs = ArrayList<IntArray>()   // 8: i0,j0 .. i3,j3

        fun share(line: Int, k: Int, t: Int) = line == k || line == t

        outer@ for (c0 in cornerDict[0]) {
            val n1 = ArrayList<IntArray>()
            byLine[1][c0[2]]?.let { n1.addAll(it) }
            byLine[1][c0[3]]?.let { n1.addAll(it) }
            for (c1 in distinctByIdentity(n1)) {
                if (c1 === c0) continue
                val n2 = ArrayList<IntArray>()
                byLine[2][c1[2]]?.let { n2.addAll(it) }
                byLine[2][c1[3]]?.let { n2.addAll(it) }
                for (c2 in distinctByIdentity(n2)) {
                    evaluated++
                    if (evaluated > maxCandidates) {
                        truncated = true
                        break@outer
                    }
                    val n3 = ArrayList<IntArray>()
                    byLine[3][c2[2]]?.let { n3.addAll(it) }
                    byLine[3][c2[3]]?.let { n3.addAll(it) }
                    for (c3 in distinctByIdentity(n3)) {
                        val closes = share(c3[2], c0[2], c0[3]) || share(c3[3], c0[2], c0[3])
                        if (!closes) continue
                        quads.add(
                            doubleArrayOf(
                                c0[0].toDouble(), c0[1].toDouble(),
                                c1[0].toDouble(), c1[1].toDouble(),
                                c2[0].toDouble(), c2[1].toDouble(),
                                c3[0].toDouble(), c3[1].toDouble()
                            )
                        )
                        connects.add(
                            intArrayOf(
                                sharedLine(c0, c1), sharedLine(c1, c2),
                                sharedLine(c2, c3), sharedLine(c3, c0)
                            )
                        )
                        segPairs.add(
                            intArrayOf(c0[2], c0[3], c1[2], c1[3], c2[2], c2[3], c3[2], c3[3])
                        )
                    }
                }
            }
        }

        if (quads.isEmpty()) return emptyList<Square>() to truncated
        val scores = scoreSquares(quads, connects, merged, distSeg, params, inputEdge)
        val indexed = scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
        val out = ArrayList<Square>(indexed.size)
        for (idx in indexed) {
            val q = quads[idx]
            val corners = listOf(
                Pt(q[0], q[1]), Pt(q[2], q[3]), Pt(q[4], q[5]), Pt(q[6], q[7])
            )
            out.add(Square(corners, scores[idx], 0.0))
        }
        return out to truncated
    }

    private fun distinctByIdentity(list: List<IntArray>): List<IntArray> {
        if (list.size < 2) return list
        val seen = java.util.IdentityHashMap<IntArray, Boolean>()
        val out = ArrayList<IntArray>(list.size)
        for (c in list) if (seen.put(c, true) == null) out.add(c)
        return out
    }

    private fun sharedLine(a: IntArray, b: IntArray): Int =
        if (a[2] == b[2] || a[2] == b[3]) a[2] else a[3]

    /** Angle in degrees from `(px,py)` to `(cx,cy)`, normalised to `[0, 360)`. */
    private fun degTo(px: Int, py: Int, cx: Double, cy: Double): Double {
        var d = atan2(cy - py, cx - px) * 180.0 / Math.PI
        if (d < 0.0) d += 360.0
        return d
    }

    /**
     * NAVER's square score: overlap (coverage of each side by its supporting segment), degree
     * symmetry, area, distance from the map centre, and side-length symmetry.
     */
    private fun scoreSquares(
        quads: List<DoubleArray>,
        connects: List<IntArray>,
        merged: List<Seg>,
        distSeg: DoubleArray,
        params: Params,
        inputEdge: Int
    ): DoubleArray {
        val n = quads.size
        val overlap = DoubleArray(n)
        val degree = DoubleArray(n)
        val length = DoubleArray(n)

        for (q in 0 until n) {
            val quad = quads[q]
            val conn = connects[q]
            var cover = 0.0
            var perimeter = 0.0
            val sideLen = DoubleArray(4)
            val deg = DoubleArray(4)
            for (side in 0 until 4) {
                val segIdx = conn[side]
                val connectDist = distSeg[segIdx]
                val a = side * 2
                val bb = ((side + 1) % 4) * 2
                // Distances from THIS side's start/end corners to the connect segment.
                val sMin = min(
                    hypot(quad[a] - merged[segIdx].x1, quad[a + 1] - merged[segIdx].y1),
                    hypot(quad[a] - merged[segIdx].x2, quad[a + 1] - merged[segIdx].y2)
                )
                val sMax = max(
                    hypot(quad[a] - merged[segIdx].x1, quad[a + 1] - merged[segIdx].y1),
                    hypot(quad[a] - merged[segIdx].x2, quad[a + 1] - merged[segIdx].y2)
                )
                val eMin = min(
                    hypot(quad[bb] - merged[segIdx].x1, quad[bb + 1] - merged[segIdx].y1),
                    hypot(quad[bb] - merged[segIdx].x2, quad[bb + 1] - merged[segIdx].y2)
                )
                val eMax = max(
                    hypot(quad[bb] - merged[segIdx].x1, quad[bb + 1] - merged[segIdx].y1),
                    hypot(quad[bb] - merged[segIdx].x2, quad[bb + 1] - merged[segIdx].y2)
                )
                val sOut = sMax > connectDist
                val eOut = eMax > connectDist
                val sCov = if (sOut) 0.0 else -1.0
                val sPeri = if (sOut) 1.0 else -1.0
                val eCov = if (eOut) 0.0 else -1.0
                val ePeri = if (eOut) 1.0 else -1.0
                cover += connectDist + sCov * sMin + eCov * eMin
                perimeter += connectDist + sPeri * sMin + ePeri * eMin
                sideLen[side] = connectDist + sPeri * sMin + ePeri * eMin
                // Interior angle at the side's start corner: between (prev - cur) and (next - cur).
                val prev = ((side + 3) % 4) * 2
                val next = ((side + 1) % 4) * 2
                val v1x = quad[prev] - quad[a]
                val v1y = quad[prev + 1] - quad[a + 1]
                val v2x = quad[next] - quad[a]
                val v2y = quad[next + 1] - quad[a + 1]
                val n1 = hypot(v1x, v1y)
                val n2 = hypot(v2x, v2y)
                val icos = if (n1 < 1e-9 || n2 < 1e-9) 1.0 else
                    ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)
                deg[side] = Math.toDegrees(kotlin.math.acos(icos))
            }
            overlap[q] = if (perimeter > 1e-9) cover / perimeter else 0.0
            val r1 = ratio(deg[0], deg[2])
            val r2 = ratio(deg[1], deg[3])
            degree[q] = (r1 + r2) / 2.0
            length[q] = (ratio(sideLen[0], sideLen[2]) + ratio(sideLen[1], sideLen[3])) / 2.0
        }

        val maxOverlap = overlap.maxOrNull() ?: 0.0
        if (maxOverlap > 0.0) for (i in 0 until n) overlap[i] /= maxOverlap

        val mapSize = inputEdge / 2.0
        val cx = mapSize / 2.0
        val cy = mapSize / 2.0
        val out = DoubleArray(n)
        for (q in 0 until n) {
            val quad = quads[q]
            var area = 0.0
            for (k in 0 until 4) {
                val x1 = quad[k * 2]
                val y1 = quad[k * 2 + 1]
                val x2 = quad[((k + 1) % 4) * 2]
                val y2 = quad[((k + 1) % 4) * 2 + 1]
                area += x1 * y2 - x2 * y1
            }
            area = abs(area) / 2.0 / (mapSize * mapSize)
            val mx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4.0
            val my = (quad[1] + quad[3] + quad[5] + quad[7]) / 4.0
            val center = hypot(mx - cx, my - cy) / (mapSize / sqrt(2.0))
            out[q] = params.wOverlap * overlap[q] +
                params.wDegree * degree[q] +
                params.wArea * area -
                params.wCenter * center +
                params.wLength * length[q]
        }
        return out
    }

    private fun ratio(a: Double, b: Double): Double {
        if (b <= 1e-9 && a <= 1e-9) return 0.0
        if (b <= 1e-9) return 0.0
        val r = a / b
        return if (r > 1.0) (if (r > 1e-9) 1.0 / r else 0.0) else r
    }

    // ======================================================================================
    // 4) Map -> frame
    // ======================================================================================

    /**
     * Scales map-space coordinates to frame pixels. The maps are half the model input, so a map
     * coordinate covers `2 * frame / edge` pixels (H29's "factor of two" risk).
     *
     * A segment has **two** endpoints: both are scaled. Scaling only the first turns every line
     * into a diagonal, which is exactly the defect the H30 calibration caught in the numpy port.
     */
    fun mapToFrame(seg: Seg, frameW: Int, frameH: Int, inputEdge: Int): Seg {
        val sx = 2.0 / inputEdge * frameW
        val sy = 2.0 / inputEdge * frameH
        return Seg(seg.x1 * sx, seg.y1 * sy, seg.x2 * sx, seg.y2 * sy)
    }

    fun mapToFrame(pt: Pt, frameW: Int, frameH: Int, inputEdge: Int): Pt {
        val sx = 2.0 / inputEdge * frameW
        val sy = 2.0 / inputEdge * frameH
        return Pt(pt.x * sx, pt.y * sy)
    }

    // ======================================================================================
    // 5) Geometry shared with the merge rule
    // ======================================================================================

    fun polygonArea(pts: List<Pt>): Double {
        if (pts.size < 3) return 0.0
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    fun pointInConvexQuad(x: Double, y: Double, quad: List<Pt>): Boolean {
        if (quad.size < 3) return false
        var positive = false
        var negative = false
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % quad.size]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (cross > 1e-6) positive = true
            if (cross < -1e-6) negative = true
            if (positive && negative) return false
        }
        return true
    }

    /** `(width, height)` of the quad's mean top/bottom and left/right edge vectors. */
    fun quadAxes(quad: List<Pt>): Pair<Pt, Pt> {
        require(quad.size == 4)
        val wx = ((quad[1].x - quad[0].x) + (quad[2].x - quad[3].x)) / 2.0
        val wy = ((quad[1].y - quad[0].y) + (quad[2].y - quad[3].y)) / 2.0
        val hx = ((quad[3].x - quad[0].x) + (quad[2].x - quad[1].x)) / 2.0
        val hy = ((quad[3].y - quad[0].y) + (quad[2].y - quad[1].y)) / 2.0
        return Pt(wx, wy) to Pt(hx, hy)
    }

    /** Longest contiguous run of `seg` inside `quad`, as a fraction of the quad's own extent. */
    fun lineThroughQuad(quad: List<Pt>, seg: Seg, minInsideFrac: Double = 0.80): Boolean {
        if (quad.size != 4) return false
        val dx = seg.x2 - seg.x1
        val dy = seg.y2 - seg.y1
        val len = hypot(dx, dy)
        if (len < 1e-6) return false
        val ux = dx / len
        val uy = dy / len
        var tmin = Double.MAX_VALUE
        var tmax = -Double.MAX_VALUE
        for (p in quad) {
            val t = (p.x - seg.x1) * ux + (p.y - seg.y1) * uy
            tmin = min(tmin, t)
            tmax = max(tmax, t)
        }
        val extent = tmax - tmin
        if (extent < 1e-6) return false
        val steps = 96
        var run = 0
        var best = 0
        for (s in 0 until steps) {
            val t = (s + 0.5) / steps
            if (pointInConvexQuad(seg.x1 + dx * t, seg.y1 + dy * t, quad)) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return (best.toDouble() / steps) * len >= minInsideFrac * extent
    }

    /** Fraction of the quad's perimeter supported by near-collinear detected segments. */
    fun quadLineSupport(quad: List<Pt>, segments: List<Seg>, relTol: Double = 0.015, angleTolDeg: Double = 10.0): Double {
        if (quad.size != 4 || segments.isEmpty()) return 0.0
        val cosTol = cos(Math.toRadians(angleTolDeg))
        var acc = 0.0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val elen = hypot(ex, ey)
            if (elen < 1e-6) continue
            val ux = ex / elen
            val uy = ey / elen
            val cover = ArrayList<DoubleArray>(4)
            for (s in segments) {
                val sdx = s.x2 - s.x1
                val sdy = s.y2 - s.y1
                val slen = hypot(sdx, sdy)
                if (slen < 1e-6) continue
                if (abs((sdx * ux + sdy * uy) / slen) < cosTol) continue
                val off0 = abs((s.x1 - a.x) * uy - (s.y1 - a.y) * ux)
                val off1 = abs((s.x2 - a.x) * uy - (s.y2 - a.y) * ux)
                if (off0 > relTol * elen || off1 > relTol * elen) continue
                var t0 = ((s.x1 - a.x) * ux + (s.y1 - a.y) * uy) / elen
                var t1 = ((s.x2 - a.x) * ux + (s.y2 - a.y) * uy) / elen
                if (t0 > t1) { val tmp = t0; t0 = t1; t1 = tmp }
                val lo = max(t0, 0.0)
                val hi = min(t1, 1.0)
                if (hi > lo) cover.add(doubleArrayOf(lo, hi))
            }
            if (cover.isEmpty()) continue
            cover.sortBy { it[0] }
            var total = 0.0
            var lo = cover[0][0]
            var hi = cover[0][1]
            for (k in 1 until cover.size) {
                if (cover[k][0] <= hi) {
                    hi = max(hi, cover[k][1])
                } else {
                    total += hi - lo
                    lo = cover[k][0]
                    hi = cover[k][1]
                }
            }
            total += hi - lo
            acc += min(1.0, total)
        }
        return acc / 4.0
    }

    /** Splits a convex quad by an infinite line into `(positiveSide, negativeSide)`. */
    fun splitQuadByLine(quad: List<Pt>, line: Seg): Pair<List<Pt>?, List<Pt>?> {
        if (quad.size != 4) return null to null
        val dx = line.x2 - line.x1
        val dy = line.y2 - line.y1
        if (hypot(dx, dy) < 1e-9) return null to null
        val side = DoubleArray(4) { dx * (quad[it].y - line.y1) - dy * (quad[it].x - line.x1) }
        if (side.all { it >= -1e-9 } || side.all { it <= 1e-9 }) return null to null

        fun clip(sign: Double): List<Pt>? {
            val out = ArrayList<Pt>(6)
            for (i in 0 until 4) {
                val cur = quad[i]
                val next = quad[(i + 1) % 4]
                val sc = side[i] * sign
                val sn = side[(i + 1) % 4] * sign
                if (sc >= -1e-9) out.add(cur)
                if ((sc > 1e-9 && sn < -1e-9) || (sc < -1e-9 && sn > 1e-9)) {
                    val t = sc / (sc - sn)
                    out.add(Pt(cur.x + (next.x - cur.x) * t, cur.y + (next.y - cur.y) * t))
                }
            }
            return if (out.size >= 3) out else null
        }
        return clip(1.0) to clip(-1.0)
    }
}
