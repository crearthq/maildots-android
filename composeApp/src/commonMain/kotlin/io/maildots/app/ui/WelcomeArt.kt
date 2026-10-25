package io.maildots.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Çizim alanı: bütün sahneler 320 x 240 birimlik tuvale göre çizilir ve ekrana sığdırılır. */
private const val W = 320f
private const val H = 240f

private class Palette(
    val blue: Color,
    val soft: Color,
    val ink: Color,
    val paper: Color,
    val tint: Color,
    val line: Color,
    val muted: Color,
    val violet: Color,
)

/** [from]..[to] aralığını 0..1'e taşır (yumuşatılmış). */
private fun seg(p: Float, from: Float, to: Float): Float =
    FastOutSlowInEasing.transform(((p - from) / (to - from)).coerceIn(0f, 1f))

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Hafif taşan "pop" (0 → 1.12 → 1). */
private fun pop(t: Float): Float = if (t <= 0f) 0f else if (t < 0.7f) lerp(0f, 1.12f, t / 0.7f) else lerp(1.12f, 1f, (t - 0.7f) / 0.3f)

/** Yolun baştan [progress] kadarını çizer (çizilerek belirme). */
private fun DrawScope.strokeOn(path: Path, progress: Float, color: Color, width: Float, measure: PathMeasure) {
    if (progress <= 0f) return
    measure.setPath(path, false)
    val part = Path()
    measure.getSegment(0f, measure.length * progress.coerceAtMost(1f), part, true)
    drawPath(part, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.card(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color, stroke: Color, width: Float = 2f) {
    drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(r))
    drawRoundRect(stroke, Offset(x, y), Size(w, h), CornerRadius(r), style = Stroke(width))
}

/**
 * Karşılama sayfasının illüstrasyonu.
 * [active] olduğunda giriş animasyonu baştan oynar; ardından hafif bir döngü sürer.
 */
@Composable
fun WelcomeArt(index: Int, active: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    val palette = Palette(
        blue = colors.primary,
        soft = Brand.Periwinkle,
        ink = colors.onBackground,
        paper = if (dark) colors.surfaceContainerHighest else Color.White,
        tint = colors.primaryContainer,
        line = colors.outline,
        muted = colors.onSurfaceVariant,
        violet = Color(0xFF7A5AF8),
    )
    val intro = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            intro.snapTo(0f)
            intro.animateTo(1f, tween(1700, easing = LinearEasing))
        }
    }
    val loop by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
    )
    val measure = remember { PathMeasure() }

    Canvas(modifier) {
        val k = min(size.width / W, size.height / H)
        clipRect {
            withTransform({
                translate((size.width - W * k) / 2f, (size.height - H * k) / 2f)
                scale(k, k, Offset.Zero)
            }) {
                val p = intro.value
                when (index) {
                    0 -> inbox(p, loop, palette)
                    1 -> shield(p, loop, palette, measure)
                    2 -> barrier(p, loop, palette)
                    else -> plane(p, loop, palette, measure)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 1: zarftan çıkan postalar

private fun DrawScope.inbox(p: Float, loop: Float, c: Palette) {
    val wave = sin(loop * 2f * PI.toFloat())
    drawCircle(c.tint, 96f * seg(p, 0f, 0.3f), Offset(160f, 128f))

    // Çevrede süzülen noktalar (marka motifi).
    val dots = listOf(Triple(44f, 70f, 5f), Triple(282f, 54f, 4f), Triple(292f, 168f, 6f), Triple(34f, 176f, 4f))
    dots.forEachIndexed { i, (x, y, r) ->
        val t = seg(p, 0.5f + i * 0.08f, 0.8f + i * 0.08f)
        val bob = sin((loop + i * 0.23f) * 2f * PI.toFloat()) * 5f
        drawCircle(if (i % 2 == 0) c.soft else c.blue, r * pop(t), Offset(x, y + bob))
    }

    val open = seg(p, 0.12f, 0.42f)
    // Kartlar: arkadan öne, sırayla yükselir.
    val cards = listOf(
        floatArrayOf(-14f, 18f, -8f),
        floatArrayOf(14f, 32f, 7f),
        floatArrayOf(0f, 50f, 0f),
    )
    val avatars = listOf(c.violet, c.soft, c.blue)
    cards.forEachIndexed { i, (dx, finalY, angle) ->
        val t = seg(p, 0.36f + i * 0.13f, 0.72f + i * 0.13f)
        if (t <= 0f) return@forEachIndexed
        val y = lerp(116f, finalY + wave * (1.5f + i), t)
        withTransform({
            translate(dx * t, 0f)
            rotate(angle * t, Offset(160f, y + 40f))
        }) {
            card(96f, y, 128f, 78f, 12f, c.paper, c.line)
            drawCircle(avatars[i], 9f, Offset(116f, y + 22f))
            drawLine(c.ink, Offset(134f, y + 18f), Offset(190f, y + 18f), 4f, StrokeCap.Round)
            drawLine(c.line, Offset(134f, y + 29f), Offset(206f, y + 29f), 3f, StrokeCap.Round)
            drawLine(c.line, Offset(110f, y + 48f), Offset(206f, y + 48f), 3f, StrokeCap.Round)
        }
    }

    // Zarfın ön yüzü.
    val body = seg(p, 0f, 0.22f)
    withTransform({ scale(lerp(0.9f, 1f, body), lerp(0.9f, 1f, body), Offset(160f, 156f)) }) {
        card(76f, 104f, 168f, 104f, 14f, c.paper.copy(alpha = body), c.ink.copy(alpha = body), 2.5f)
        val fold = Path().apply {
            moveTo(78f, 108f)
            lineTo(160f, 164f)
            lineTo(242f, 108f)
        }
        drawPath(fold, c.line.copy(alpha = body), style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (open < 0.5f) {
            // Kapalı kapak: önde, aşağı bakar.
            val closed = Path().apply {
                moveTo(76f, 104f)
                lineTo(160f, lerp(164f, 104f, seg(open, 0f, 0.5f)))
                lineTo(244f, 104f)
            }
            drawPath(closed, c.ink.copy(alpha = body), style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    // Yeni posta rozeti.
    val badge = seg(p, 0.82f, 1f)
    if (badge > 0f) {
        val center = Offset(244f, 104f)
        val ring = (loop * 2f) % 1f
        drawCircle(c.blue.copy(alpha = (1f - ring) * 0.35f * badge), 13f + ring * 14f, center)
        drawCircle(c.paper, 16f * pop(badge), center)
        drawCircle(c.blue, 13f * pop(badge), center)
        drawCircle(Color.White, 4f * pop(badge), center)
    }
}

// ------------------------------------------------------------------ 2: kalkan ve kapanan kilit

private fun DrawScope.shield(p: Float, loop: Float, c: Palette, measure: PathMeasure) {
    val center = Offset(160f, 112f)
    drawCircle(c.tint, 88f * seg(p, 0f, 0.3f), center)

    // Dönen kesikli halka: şifreli bağlantı.
    val ringIn = seg(p, 0.1f, 0.5f)
    rotate(loop * 360f, center) {
        drawCircle(
            c.soft.copy(alpha = ringIn),
            100f,
            center,
            style = Stroke(2.5f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 13f))),
        )
    }
    // Halkada dolaşan veri noktaları.
    repeat(2) { i ->
        val a = (loop + i * 0.5f) * 2f * PI.toFloat()
        drawCircle(c.blue.copy(alpha = ringIn), 5f, Offset(center.x + cos(a) * 100f, center.y + sin(a) * 100f))
    }

    val outline = Path().apply {
        moveTo(160f, 34f)
        lineTo(220f, 58f)
        lineTo(220f, 110f)
        cubicTo(220f, 154f, 194f, 180f, 160f, 194f)
        cubicTo(126f, 180f, 100f, 154f, 100f, 110f)
        lineTo(100f, 58f)
        close()
    }
    drawPath(outline, c.paper.copy(alpha = seg(p, 0.25f, 0.5f)))
    strokeOn(outline, seg(p, 0.05f, 0.55f), c.ink, 3f, measure)

    // Kilit: gövde belirir, kemer yukarıdan inip kapanır.
    val bodyIn = seg(p, 0.45f, 0.62f)
    val close = seg(p, 0.62f, 0.8f)
    if (bodyIn > 0f) {
        val lift = lerp(-12f, 0f, close)
        val shackle = Path().apply {
            moveTo(146f, 108f + lift)
            lineTo(146f, 96f + lift)
            cubicTo(146f, 76f + lift, 174f, 76f + lift, 174f, 96f + lift)
            lineTo(174f, lerp(98f, 108f, close) + lift)
        }
        drawPath(shackle, c.ink.copy(alpha = bodyIn), style = Stroke(5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        withTransform({ scale(pop(bodyIn), pop(bodyIn), Offset(160f, 128f)) }) {
            drawRoundRect(c.blue, Offset(134f, 106f), Size(52f, 44f), CornerRadius(10f))
            drawCircle(Color.White, 5f, Offset(160f, 124f))
            drawLine(Color.White, Offset(160f, 124f), Offset(160f, 136f), 4f, StrokeCap.Round)
        }
    }

    // Şifre alanı: noktalar sırayla yazılır.
    val field = seg(p, 0.5f, 0.68f)
    if (field > 0f) {
        card(104f, 208f, 112f, 26f, 13f, c.paper.copy(alpha = field), c.line.copy(alpha = field))
        repeat(6) { i ->
            val t = seg(p, 0.62f + i * 0.05f, 0.7f + i * 0.05f)
            drawCircle(c.ink, 3.4f * pop(t), Offset(124f + i * 14.4f, 221f))
        }
    }

    // Onay işareti.
    val ok = seg(p, 0.84f, 1f)
    if (ok > 0f) {
        val at = Offset(214f, 170f)
        drawCircle(c.paper, 19f * pop(ok), at)
        drawCircle(c.ink, 16f * pop(ok), at)
        val check = Path().apply {
            moveTo(206.5f, 170.5f)
            lineTo(211.5f, 175.5f)
            lineTo(221.5f, 164.5f)
        }
        strokeOn(check, seg(ok, 0.3f, 1f), Color.White, 3.2f, measure)
    }
}

// ------------------------------------------------------------------ 3: izleyicileri çeviren halka

private fun DrawScope.barrier(p: Float, loop: Float, c: Palette) {
    val center = Offset(160f, 120f)
    val radius = 84f
    drawCircle(c.tint, 70f * seg(p, 0f, 0.3f), center)

    // Koruma halkası: çizilerek belirir.
    val sweep = seg(p, 0.05f, 0.5f)
    drawArc(
        c.blue, -90f, 360f * sweep, false,
        Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
        style = Stroke(3f, cap = StrokeCap.Round),
    )

    // Ortadaki posta.
    val mail = seg(p, 0.15f, 0.42f)
    withTransform({ scale(pop(mail), pop(mail), center) }) {
        card(112f, 86f, 96f, 68f, 12f, c.paper, c.ink, 2.5f)
        val fold = Path().apply {
            moveTo(114f, 90f)
            lineTo(160f, 124f)
            lineTo(206f, 90f)
        }
        drawPath(fold, c.ink, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // İzleyiciler: dışarıdan gelir, halkaya çarpar, geri sekip kaybolur.
    val live = seg(p, 0.45f, 0.7f)
    if (live <= 0f) return
    val angles = floatArrayOf(-158f, -112f, -38f, 14f, 62f, 148f)
    angles.forEachIndexed { i, degrees ->
        val phase = (loop * 2f + i / 6f) % 1f
        val a = degrees * PI.toFloat() / 180f
        val hit = 0.58f
        val distance: Float
        val alpha: Float
        if (phase < hit) {
            distance = lerp(168f, radius + 9f, FastOutSlowInEasing.transform(phase / hit))
            alpha = (phase / 0.15f).coerceAtMost(1f)
        } else {
            val back = (phase - hit) / (1f - hit)
            distance = lerp(radius + 9f, radius + 34f, back)
            alpha = 1f - back
        }
        val at = Offset(center.x + cos(a) * distance, center.y + sin(a) * distance)
        // İzleme pikseli: küçük kare ve içinde göz bebeği.
        rotate(degrees + phase * 90f, at) {
            drawRoundRect(c.ink.copy(alpha = alpha * live), Offset(at.x - 6.5f, at.y - 6.5f), Size(13f, 13f), CornerRadius(3.5f))
            drawCircle(c.paper.copy(alpha = alpha * live), 2.2f, at)
        }
        // Çarpma anı: halkada kısa bir parlama.
        val flash = 1f - ((phase - hit) / 0.2f).coerceIn(0f, 1f)
        if (phase >= hit && flash > 0f) {
            drawArc(
                c.blue.copy(alpha = flash * live), degrees - 11f, 22f, false,
                Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
                style = Stroke(3f + 5f * flash, cap = StrokeCap.Round),
            )
        }
    }
}

// ------------------------------------------------------------------ 4: noktalı yolda kâğıt uçak

private fun DrawScope.plane(p: Float, loop: Float, c: Palette, measure: PathMeasure) {
    drawCircle(c.tint, 74f * seg(p, 0f, 0.3f), Offset(214f, 96f))

    val route = Path().apply {
        moveTo(40f, 196f)
        cubicTo(116f, 214f, 112f, 96f, 178f, 112f)
        cubicTo(232f, 126f, 244f, 84f, 284f, 52f)
    }
    measure.setPath(route, false)
    val length = measure.length
    val travel = seg(p, 0.08f, 0.9f)

    // Başlangıç: gönderen kutusu.
    val start = seg(p, 0f, 0.2f)
    drawCircle(c.paper, 13f * pop(start), Offset(40f, 196f))
    drawCircle(c.ink, 13f * pop(start), Offset(40f, 196f), style = Stroke(2.5f))
    drawCircle(c.blue, 5f * pop(start), Offset(40f, 196f))

    // Yol: uçağın geçtiği yerde noktalar belirir.
    val count = 24
    for (i in 1 until count) {
        val at = i / count.toFloat()
        if (at > travel - 0.04f) break
        val grow = ((travel - at) / 0.08f).coerceIn(0f, 1f)
        val point = measure.getPosition(length * at)
        drawCircle(if (i % 4 == 0) c.blue else c.soft, (if (i % 4 == 0) 3.6f else 2.6f) * grow, point)
    }

    // Uçak: yolun teğetine döner, vardıktan sonra hafifçe süzülür.
    val distance = length * travel
    val position = measure.getPosition(distance)
    val tangent = measure.getTangent(distance)
    val arrived = seg(p, 0.9f, 1f)
    val drift = sin(loop * 2f * PI.toFloat()) * 4f * arrived
    val angle = atan2(tangent.y, tangent.x) * 180f / PI.toFloat()
    val planeIn = seg(p, 0.05f, 0.2f)
    withTransform({
        translate(position.x, position.y + drift)
        rotate(angle + drift * 0.8f, Offset.Zero)
        scale(1.7f * pop(planeIn), 1.7f * pop(planeIn), Offset.Zero)
    }) {
        val wing = Path().apply {
            moveTo(18f, 0f)
            lineTo(-14f, -12f)
            lineTo(-7f, 0f)
            lineTo(-14f, 12f)
            close()
        }
        drawPath(wing, c.blue)
        val shade = Path().apply {
            moveTo(18f, 0f)
            lineTo(-7f, 0f)
            lineTo(-14f, 12f)
            close()
        }
        drawPath(shade, c.ink.copy(alpha = 0.22f))
        drawPath(wing, c.blue, style = Stroke(1.2f, join = StrokeJoin.Round))
    }

    // Varış: kısa kıvılcımlar.
    if (arrived > 0f) {
        val at = Offset(284f, 52f)
        val burst = (1f - arrived) * 0.5f + 0.5f
        listOf(-150f, -95f, -30f, 40f).forEach { degrees ->
            val a = degrees * PI.toFloat() / 180f
            val from = 22f + (1f - burst) * 8f
            val to = from + 9f
            drawLine(
                c.soft.copy(alpha = burst),
                Offset(at.x + cos(a) * from, at.y + sin(a) * from),
                Offset(at.x + cos(a) * to, at.y + sin(a) * to),
                3f, StrokeCap.Round,
            )
        }
    }
}

