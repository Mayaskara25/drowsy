package com.drowsy.perception

import kotlin.math.hypot

/** EAR = (||p1-p5||+||p2-p4||) / (2*||p0-p3||) — 6 points per eye (§8). */
fun eyeAspectRatio(pts: List<Point2D>): Float {
    require(pts.size == 6) { "EAR needs 6 points" }
    fun dist(a: Point2D, b: Point2D) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    val v1 = dist(pts[1], pts[5]); val v2 = dist(pts[2], pts[4]); val h = dist(pts[0], pts[3])
    if (h < 1e-6f) return 0f
    return (v1 + v2) / (2f * h)
}

/** MAR = vertical / horizontal (inner lip if 8 pts) (§8). */
fun mouthAspectRatio(pts: List<Point2D>): Float {
    if (pts.size < 4) return 0f
    fun dist(a: Point2D, b: Point2D) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    val h = dist(pts[0], pts[1]); val v = dist(pts[2], pts[3])
    if (h < 1e-6f) return 0f
    return v / h
}

/** Lightweight head-pose heuristic (§8). Replace with solvePnP for production. */
fun estimateHeadPose(landmarks: List<Point2D>): HeadPose {
    if (landmarks.size < 264) return HeadPose(0f, 0f, 0f, false)
    val nose = landmarks[1]
    val leftEye = landmarks[33]
    val rightEye = landmarks[263]
    val eyeCenter = Point2D((leftEye.x + rightEye.x)/2f, (leftEye.y + rightEye.y)/2f)
    val dx = nose.x - eyeCenter.x
    val dy = nose.y - eyeCenter.y
    var yaw = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), 0.15)) .toFloat()
    var pitch = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), 0.12)).toFloat()
    var roll = Math.toDegrees(kotlin.math.atan2((rightEye.y-leftEye.y).toDouble(), (rightEye.x-leftEye.x).toDouble())).toFloat()
    pitch = pitch.coerceIn(-60f, 60f); yaw = yaw.coerceIn(-60f, 60f); roll = roll.coerceIn(-45f, 45f)
    val abnormal = kotlin.math.abs(pitch) > 25f || kotlin.math.abs(yaw) > 30f || kotlin.math.abs(roll) > 25f
    return HeadPose(pitch, yaw, roll, abnormal)
}
