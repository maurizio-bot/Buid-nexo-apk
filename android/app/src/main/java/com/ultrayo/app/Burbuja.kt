package com.ultrayo.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * La burbuja flotante: un botón redondo encima de cualquier app para hablar con Ultra sin salir de lo
 * que estás haciendo. Tócala para hablar (o para callarla), mantenla pulsada para abrir Ultra, arrástrala
 * donde quieras y suéltala abajo en el centro para quitarla. Al lado enseña lo que oye y lo que contesta.
 * Se dibuja con el control del teléfono (accesibilidad), así que no necesita otro permiso.
 */
class Burbuja(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var caption: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var captionParams: WindowManager.LayoutParams

    /** Hasta cuándo se enseña la última respuesta al lado de la burbuja. */
    private var showReplyUntil = 0L
    private var lastReply = ""
    private var hiddenForUltra = false

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
    private val screenW get() = service.resources.displayMetrics.widthPixels
    private val screenH get() = service.resources.displayMetrics.heightPixels

    val shown: Boolean get() = bubble != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubble != null) return
        val size = dp(56)
        val prefs = prefs(service)
        val b = TextView(service).apply {
            text = "U"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            contentDescription = "Hablar con Ultra"
            background = circle(IDLE)
        }
        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("burbuja_x", screenW - size - dp(8)).coerceIn(0, maxOf(0, screenW - size))
            y = prefs.getInt("burbuja_y", (screenH * 0.55f).toInt()).coerceIn(0, maxOf(0, screenH - size))
        }
        val c = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 4
            maxWidth = dp(250)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(225, 12, 18, 32))
                setStroke(dp(1), Color.argb(160, 80, 170, 255))
            }
            visibility = View.GONE
        }
        captionParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        b.setOnTouchListener(Drag())
        try {
            wm.addView(c, captionParams)
            wm.addView(b, bubbleParams)
            bubble = b
            caption = c
        } catch (e: Exception) {
            ErrorLog.add("No pude poner la burbuja", e)
            runCatching { wm.removeView(c) }
            return
        }
        main.post(tick)
    }

    fun hide() {
        main.removeCallbacks(tick)
        bubble?.let { runCatching { wm.removeView(it) } }
        caption?.let { runCatching { wm.removeView(it) } }
        bubble = null
        caption = null
    }

    /** Con la propia Ultra delante no hace falta la burbuja: se esconde y vuelve al salir. */
    fun ultraInFront(inFront: Boolean) {
        if (hiddenForUltra == inFront) return
        hiddenForUltra = inFront
        bubble?.visibility = if (inFront) View.GONE else View.VISIBLE
        if (inFront) caption?.visibility = View.GONE
    }

    // --- Tocar, arrastrar y quitar ---

    private inner class Drag : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) moved = true
                    if (moved) {
                        bubbleParams.x = (startX + dx).toInt()
                        bubbleParams.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, bubbleParams) }
                        bubble?.background = circle(if (nearTrash(e.rawX, e.rawY)) TRASH else IDLE)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        if (nearTrash(e.rawX, e.rawY)) {
                            setEnabled(service, false)
                            hide()
                            Assistant.init(service)
                            Assistant.notify("Burbuja quitada. Vuelve a ponerla en Ajustes o di «pon la burbuja».")
                        } else {
                            // Se pega al borde más cercano para no tapar el centro.
                            bubbleParams.x = if (bubbleParams.x + v.width / 2 < screenW / 2) dp(4) else screenW - v.width - dp(4)
                            runCatching { wm.updateViewLayout(v, bubbleParams) }
                            prefs(service).edit().putInt("burbuja_x", bubbleParams.x).putInt("burbuja_y", bubbleParams.y).apply()
                        }
                    } else if (SystemClock.uptimeMillis() - downAt > 550) {
                        openUltra(talk = false)
                    } else {
                        tap()
                    }
                }
            }
            return true
        }
    }

    private fun nearTrash(rawX: Float, rawY: Float) =
        rawY > screenH - dp(110) && abs(rawX - screenW / 2f) < dp(90)

    /** Tocar: si está hablando, escuchando o pensando, la para; si no, empieza a escucharte. */
    private fun tap() {
        if (Assistant.speaking || Assistant.listening || Assistant.thinking) {
            Assistant.stopSpeaking()
            Assistant.stopListening()
            return
        }
        Assistant.init(service)
        val ultra = UltraService.instance
        if (ultra != null) {
            ultra.wake()
        } else {
            // Sin el oído de "Ultra" encendido, Android no deja usar el micrófono desde aquí: se abre Ultra.
            openUltra(talk = true)
        }
    }

    private fun openUltra(talk: Boolean) {
        val intent = Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (talk) intent.action = MainActivity.ACTION_TALK
        runCatching { service.startActivity(intent) }
    }

    // --- Lo que se ve al lado ---

    private val tick = object : Runnable {
        override fun run() {
            runCatching { refresh() }
            main.postDelayed(this, 300)
        }
    }

    private fun refresh() {
        val b = bubble ?: return
        val c = caption ?: return
        if (hiddenForUltra) return
        val reply = Assistant.turns.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        if (reply != lastReply) {
            lastReply = reply
            showReplyUntil = SystemClock.uptimeMillis() + 7000
        }
        val (color, text) = when {
            Assistant.listening -> LISTEN to Assistant.status.ifBlank { "Te escucho…" }
            Assistant.thinking -> THINK to Assistant.status.ifBlank { "Pensando…" }
            Assistant.speaking -> SPEAK to reply
            Assistant.status.startsWith("Paso ") -> THINK to Assistant.status
            SystemClock.uptimeMillis() < showReplyUntil && reply.isNotBlank() -> IDLE to reply
            else -> IDLE to ""
        }
        b.background = circle(color)
        if (text.isBlank()) {
            c.visibility = View.GONE
            return
        }
        val shownText = text.replace(Regex("\\s+"), " ").let { if (it.length > 220) it.take(220) + "…" else it }
        if (c.text.toString() != shownText) c.text = shownText
        c.visibility = View.VISIBLE
        // Al lado de la burbuja, hacia el centro de la pantalla.
        val onRight = bubbleParams.x > screenW / 2
        c.measure(View.MeasureSpec.makeMeasureSpec(dp(250), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val x = if (onRight) bubbleParams.x - c.measuredWidth - dp(8) else bubbleParams.x + b.width + dp(8)
        val y = bubbleParams.y + b.height / 2 - c.measuredHeight / 2
        if (captionParams.x != x || captionParams.y != y) {
            captionParams.x = x.coerceAtLeast(0)
            captionParams.y = y.coerceAtLeast(0)
            runCatching { wm.updateViewLayout(c, captionParams) }
        }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.argb(200, 160, 220, 255))
    }

    companion object {
        private val IDLE = Color.argb(230, 25, 110, 230)
        private val LISTEN = Color.argb(240, 220, 50, 70)
        private val THINK = Color.argb(240, 230, 150, 30)
        private val SPEAK = Color.argb(240, 30, 170, 110)
        private val TRASH = Color.argb(240, 120, 120, 120)

        private fun prefs(context: Context) = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

        fun enabled(context: Context) = prefs(context).getBoolean("burbuja", false)

        fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("burbuja", on).apply()

        /** Pone o quita la burbuja ahora y lo recuerda. Devuelve qué decir. */
        fun switch(context: Context, on: Boolean): String {
            setEnabled(context, on)
            val robot = UltraAccessibility.instance
                ?: return if (on) "Para la burbuja necesito el control del teléfono: actívalo en Ajustes > Control del teléfono y saldrá sola." else "Burbuja quitada."
            robot.bubble(on)
            return if (on) "Burbuja puesta. Tócala para hablarme desde cualquier app; mantenla pulsada para abrirme." else "Burbuja quitada."
        }

        private val command = Regex(
            "^(?:(pon|ponme|activa|ense[nñ]a|saca|muestra|quita|qu[ií]tame|desactiva|oculta|esconde|apaga|enciende)(?: la)? burbuja(?: flotante)?)$",
            RegexOption.IGNORE_CASE,
        )

        /** "pon la burbuja" → true, "quita la burbuja" → false, otra cosa → null. */
        fun order(said: String): Boolean? {
            val m = command.find(QuickActions.clean(said)) ?: return null
            return !Regex("^(?:quita|qu[ií]tame|desactiva|oculta|esconde|apaga)", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[1])
        }
    }
}
