package com.ultrayo.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * El control del teléfono: con él Ultra Yo puede leer la pantalla y pulsar, escribir y desplazarse
 * en cualquier app, y usar los botones del sistema. Maurizio lo activa en Ajustes > Accesibilidad.
 * Nunca hace nada por su cuenta: solo cumple órdenes de Maurizio, y lo arriesgado pide un "sí".
 * Mientras actúa en otra app enseña un botón rojo de PARAR encima de todo.
 */
class UltraAccessibility : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var stopButton: TextView? = null
    private val hideLater = Runnable { hideStopButton() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Control.init(this)
        Control.log("Control del teléfono activado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        hideStopButton()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    // --- Botones del sistema ---

    fun systemButton(action: String): String {
        if (Control.stopped) return STOPPED
        val (id, done) = when (action) {
            "atras" -> GLOBAL_ACTION_BACK to "Atrás."
            "inicio" -> GLOBAL_ACTION_HOME to "Al inicio."
            "recientes" -> GLOBAL_ACTION_RECENTS to "Apps recientes."
            "notificaciones" -> GLOBAL_ACTION_NOTIFICATIONS to "Tus notificaciones."
            "ajustesrapidos" -> GLOBAL_ACTION_QUICK_SETTINGS to "Ajustes rápidos."
            "captura" -> GLOBAL_ACTION_TAKE_SCREENSHOT to "Captura hecha."
            "bloquear" -> GLOBAL_ACTION_LOCK_SCREEN to "Pantalla bloqueada."
            else -> return "No conozco ese botón."
        }
        // La captura no debe salir con la app de Ultra Yo tapando: espera un instante.
        if (action == "captura") main.postDelayed({ performGlobalAction(id) }, 400) else performGlobalAction(id)
        Control.log(done.trimEnd('.'))
        return done
    }

    // --- Leer la pantalla ---

    /** La app que está delante ahora mismo (su paquete), o null si no se ve. */
    fun currentPackage(): String? = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** El texto que se ve en la pantalla, con los botones y campos marcados. */
    fun readScreen(maxChars: Int = 2500): String {
        val root = rootInActiveWindow ?: return "No puedo ver la pantalla ahora mismo."
        val app = appName(root.packageName?.toString())
        val lines = mutableListOf<String>()
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val label = label(node) ?: return@walk
            lines += when {
                node.isEditable -> "[campo] $label"
                node.isClickable -> "[botón] $label"
                else -> label
            }
        }
        if (lines.isEmpty()) return "En $app no veo texto en pantalla."
        val text = lines.distinct().joinToString("\n")
        return "Pantalla de $app:\n" + if (text.length > maxChars) text.take(maxChars) + "…" else text
    }

    // --- Pulsar ---

    /** Pulsa lo que tenga ese texto; si parece enviar, pagar o borrar, primero pide el "sí". */
    fun tapSafely(label: String): String {
        if (Control.stopped) return STOPPED
        val root = rootInActiveWindow ?: return "No puedo ver la pantalla ahora mismo."
        val node = find(root, label) ?: return "No veo «$label» en la pantalla."
        val shown = label(node) ?: label
        if (Control.isRisky(shown) || Control.isRisky(label)) {
            val app = appName(root.packageName?.toString())
            return Control.ask("Voy a pulsar «$shown» en $app") { tap(label) }
        }
        return tap(label)
    }

    /** Pulsa sin preguntar (ya confirmado o inofensivo). */
    fun tap(label: String): String {
        if (Control.stopped) return STOPPED
        val root = rootInActiveWindow ?: return "No puedo ver la pantalla ahora mismo."
        val node = find(root, label) ?: return "No veo «$label» en la pantalla."
        acting()
        val shown = label(node) ?: label
        val ok = click(node)
        Control.log("Pulsó «$shown» en ${appName(root.packageName?.toString())}")
        return if (ok) "Pulsado «$shown»." else "No pude pulsar «$shown»."
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // Si no se deja pulsar como botón, un toque de dedo en el centro.
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    /**
     * Espera (hasta unos segundos) a que aparezca un botón en [pkg] y lo pulsa: por ejemplo
     * el de enviar de WhatsApp después de abrir el chat. Solo se usa tras el "sí" de Maurizio.
     */
    fun clickWhenShown(pkg: String, ids: List<String>, labels: List<String>, onResult: (Boolean) -> Unit = {}) {
        acting()
        val deadline = System.currentTimeMillis() + 6000
        fun attempt() {
            if (Control.stopped) return onResult(false)
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() == pkg) {
                val byId = ids.firstNotNullOfOrNull { id ->
                    root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }
                }
                val node = byId ?: labels.firstNotNullOfOrNull { find(root, it, exactOnly = true) }
                if (node != null && click(node)) {
                    Control.log("Pulsó «${label(node) ?: ids.first()}» en ${appName(pkg)}")
                    hideStopButtonSoon()
                    return onResult(true)
                }
            }
            if (System.currentTimeMillis() > deadline) {
                hideStopButtonSoon()
                return onResult(false)
            }
            main.postDelayed({ attempt() }, 250)
        }
        main.postDelayed({ attempt() }, 500)
    }

    // --- Escribir y desplazarse ---

    /** Escribe [text] en el campo que tenga el cursor, o en el primero que haya. */
    fun type(text: String): String {
        if (Control.stopped) return STOPPED
        val root = rootInActiveWindow ?: return "No puedo ver la pantalla ahora mismo."
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstMatch(root) { it.isEditable && it.isVisibleToUser }
            ?: return "No veo ningún sitio donde escribir."
        acting()
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Control.log("Escribió «${text.take(60)}» en ${appName(root.packageName?.toString())}")
        hideStopButtonSoon()
        return if (ok) "Escrito." else "No me deja escribir ahí."
    }

    fun scroll(forward: Boolean): String {
        if (Control.stopped) return STOPPED
        val root = rootInActiveWindow ?: return "No puedo ver la pantalla ahora mismo."
        val list = firstMatch(root) { it.isScrollable && it.isVisibleToUser }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (list != null && list.performAction(action)) return if (forward) "Bajando." else "Subiendo."
        // Sin lista que se deje mover: deslizar el dedo.
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val (from, to) = if (forward) m.heightPixels * 0.75f to m.heightPixels * 0.3f else m.heightPixels * 0.3f to m.heightPixels * 0.75f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
        return if (forward) "Bajando." else "Subiendo."
    }

    // --- Botón de parar ---

    private fun acting() {
        Control.acting = true
        main.post { showStopButton() }
    }

    private fun hideStopButtonSoon() {
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, 2500)
    }

    private fun showStopButton() {
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, 20000)
        if (stopButton != null) return
        val button = TextView(this).apply {
            text = "■  PARAR"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(44, 26, 44, 26)
            background = GradientDrawable().apply {
                cornerRadius = 80f
                setColor(Color.argb(235, 211, 47, 47))
            }
            setOnClickListener { Control.stop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 160
        }
        runCatching {
            getSystemService(WindowManager::class.java).addView(button, params)
            stopButton = button
        }
    }

    fun hideStopButton() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { hideStopButton() }
            return
        }
        Control.acting = false
        val button = stopButton ?: return
        stopButton = null
        runCatching { getSystemService(WindowManager::class.java).removeView(button) }
    }

    // --- Ayudas ---

    private fun label(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (node.isEditable && text.isEmpty()) node.hintText?.toString()?.trim().orEmpty() else ""
        return text.ifEmpty { desc }.ifEmpty { hint }.ifEmpty { null }?.replace(Regex("\\s+"), " ")?.take(120)
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        visit(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1, visit) }
    }

    private fun firstMatch(root: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { if (found == null && test(it)) found = it }
        return found
    }

    /** El elemento visible cuyo texto coincide con [wanted]: primero exacto, luego que lo contenga. */
    private fun find(root: AccessibilityNodeInfo, wanted: String, exactOnly: Boolean = false): AccessibilityNodeInfo? {
        val w = QuickActions.norm(wanted)
        if (w.isEmpty()) return null
        val candidates = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        walk(root) { node -> if (node.isVisibleToUser) label(node)?.let { candidates += QuickActions.norm(it) to node } }
        return candidates.firstOrNull { it.first == w }?.second
            ?: if (exactOnly) null else candidates.firstOrNull { it.first.startsWith(w) }?.second
                ?: candidates.firstOrNull { it.first.contains(w) }?.second
                ?: candidates.firstOrNull { w.contains(it.first) && it.first.length >= 3 }?.second
    }

    private fun appName(pkg: String?): String {
        if (pkg == null) return "la pantalla"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        @Volatile
        var instance: UltraAccessibility? = null
            private set

        private const val STOPPED = "Me has parado. Dime qué hago y sigo."

        /** Abre la pantalla de Android donde se activa el control del teléfono. */
        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
