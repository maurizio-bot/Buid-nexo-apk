package com.ultrayo.app

import android.content.Context
import android.util.Base64
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Ayuda con los proyectos de código de Maurizio en GitHub: sus repos, los últimos cambios, las
 * issues y pull requests abiertas, cómo va la compilación, leer el README o un archivo y buscar
 * en el código. Sin clave solo ve lo público (y GitHub deja pocas consultas por hora); con su
 * clave (token) ve también lo privado y puede crear issues, siempre después de su "sí".
 */
object GitHub {

    private fun prefs(context: Context) = context.getSharedPreferences("ultrayo", Context.MODE_PRIVATE)

    fun user(context: Context): String = prefs(context).getString("github_usuario", null) ?: "maurizio-bot"
    fun setUser(context: Context, user: String) = prefs(context).edit().putString("github_usuario", user.trim().removePrefix("@")).apply()
    fun token(context: Context): String = prefs(context).getString("github_clave", "").orEmpty()
    fun setToken(context: Context, token: String) = prefs(context).edit().putString("github_clave", token.trim()).apply()

    // --- Lo que dice Maurizio ---

    sealed class Pedido {
        object Repos : Pedido()
        data class Cambios(val repo: String) : Pedido()
        data class Issues(val repo: String) : Pedido()
        data class PullRequests(val repo: String) : Pedido()
        data class Compilacion(val repo: String) : Pedido()
        data class Readme(val repo: String) : Pedido()
        data class Archivo(val ruta: String, val repo: String) : Pedido()
        data class Buscar(val texto: String, val repo: String) : Pedido()
        data class NuevaIssue(val repo: String, val titulo: String) : Pedido()
    }

    private const val REPO = "(?:el |mi )?(?:repo |repositorio |proyecto )?(.+)"
    private val patterns: List<Pair<Regex, (MatchResult) -> Pedido>> = listOf(
        Regex("^(?:cu[aá]les son |dime |ens[eé][nñ]ame |l[ií]stame )?(?:mis )?(?:repos|repositorios|proyectos de github)(?: de github| en github)?$", RegexOption.IGNORE_CASE) to { _ -> Pedido.Repos },
        Regex("^(?:qu[eé] hay de nuevo|qu[eé] ha cambiado|(?:los )?[uú]ltimos (?:cambios|commits)|commits) (?:en|de|del) $REPO$", RegexOption.IGNORE_CASE) to { m -> Pedido.Cambios(m.groupValues[1]) },
        Regex("^(?:qu[eé] )?(?:issues|incidencias)(?: tengo)?(?: abiertas)? (?:en|de|del) $REPO$", RegexOption.IGNORE_CASE) to { m -> Pedido.Issues(m.groupValues[1]) },
        Regex("^(?:qu[eé] )?(?:pull requests?|prs?)(?: tengo)?(?: abiert[ao]s)? (?:en|de|del) $REPO$", RegexOption.IGNORE_CASE) to { m -> Pedido.PullRequests(m.groupValues[1]) },
        Regex("^(?:c[oó]mo (?:va|est[aá]|van|est[aá]n) (?:la |el |las |los )?(?:compilaci[oó]n|compilaciones|builds?|ci|acciones)|estado de (?:la |las |los )?(?:compilaci[oó]n|builds?|ci|acciones)) (?:de|del|en) $REPO$", RegexOption.IGNORE_CASE) to { m -> Pedido.Compilacion(m.groupValues[1]) },
        Regex("^(?:l[eé]e(?:me)?|res[uú]me(?:me)?|ens[eé][nñ]ame) el readme (?:de|del) $REPO$", RegexOption.IGNORE_CASE) to { m -> Pedido.Readme(m.groupValues[1]) },
        Regex("^(?:l[eé]e(?:me)?|ens[eé][nñ]ame|abre|expl[ií]came) el archivo (\\S+) (?:de|del) $REPO$", RegexOption.IGNORE_CASE) to { m -> Pedido.Archivo(m.groupValues[1], m.groupValues[2]) },
        Regex("^busca [\"«]?(.+?)[\"»]? en el c[oó]digo (?:de|del) $REPO$", RegexOption.IGNORE_CASE) to { m -> Pedido.Buscar(m.groupValues[1], m.groupValues[2]) },
        Regex("^(?:crea|abre|apunta|a[nñ]ade) (?:una )?(?:issue|incidencia) en $REPO[:,] (.+)$", RegexOption.IGNORE_CASE) to { m -> Pedido.NuevaIssue(m.groupValues[1].trim(), m.groupValues[2].trim()) },
    )

    fun entender(said: String): Pedido? {
        val t = QuickActions.clean(said)
        for ((regex, make) in patterns) regex.find(t)?.let { return make(it) }
        return null
    }

    /** Qué hacer con el resultado. */
    sealed class Resultado {
        data class Texto(val texto: String) : Resultado()

        /** Datos para que el modelo los explique (README, un archivo). */
        data class ParaModelo(val prompt: String) : Resultado()

        /** Algo que cambia GitHub: espera el "sí". */
        data class Confirmar(val plan: String, val hacer: () -> String) : Resultado()
    }

    /** Hace la consulta (con red: fuera del hilo principal). */
    fun hacer(context: Context, p: Pedido, said: String): Resultado {
        val c = context.applicationContext
        if (p is Pedido.Repos) return Resultado.Texto(reposText(c))
        val repoSaid = when (p) {
            is Pedido.Cambios -> p.repo
            is Pedido.Issues -> p.repo
            is Pedido.PullRequests -> p.repo
            is Pedido.Compilacion -> p.repo
            is Pedido.Readme -> p.repo
            is Pedido.Archivo -> p.repo
            is Pedido.Buscar -> p.repo
            is Pedido.NuevaIssue -> p.repo
            Pedido.Repos -> ""
        }
        val repo = findRepo(c, repoSaid) ?: return Resultado.Texto("No encuentro el repo «$repoSaid» entre los de ${user(c)}. Di «mis repos» para ver cuáles hay.")
        return when (p) {
            is Pedido.Cambios -> Resultado.Texto(commits(c, repo))
            is Pedido.Issues -> Resultado.Texto(issues(c, repo, pulls = false))
            is Pedido.PullRequests -> Resultado.Texto(issues(c, repo, pulls = true))
            is Pedido.Compilacion -> Resultado.Texto(builds(c, repo))
            is Pedido.Readme -> Resultado.ParaModelo(
                "README del repo $repo:\n${file(c, repo, null).take(3500)}\n\nPetición de Maurizio: $said\n\nResúmelo en pocas frases, sin inventar.",
            )
            is Pedido.Archivo -> Resultado.ParaModelo(
                "Archivo ${p.ruta} del repo $repo:\n${file(c, repo, p.ruta).take(3500)}\n\nPetición de Maurizio: $said\n\nExplícalo breve y concreto, sin inventar.",
            )
            is Pedido.Buscar -> Resultado.Texto(search(c, repo, p.texto))
            is Pedido.NuevaIssue -> {
                if (token(c).isEmpty()) {
                    Resultado.Texto("Para crear issues necesito tu clave de GitHub: ponla en Ajustes > GitHub.")
                } else {
                    Resultado.Confirmar("Voy a crear en $repo la issue «${p.titulo}»") {
                        // Con red: fuera del hilo principal, y avisa al terminar.
                        Thread {
                            Assistant.notify(runCatching { createIssue(c, repo, p.titulo) }.getOrElse { "No pude crearla: ${it.message}" })
                        }.start()
                        "Creándola…"
                    }
                }
            }
            Pedido.Repos -> Resultado.Texto(reposText(c))
        }
    }

    // --- Consultas ---

    private fun call(context: Context, path: String, method: String = "GET", body: JSONObject? = null): String {
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "UltraYo")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            token(context).takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            if (code >= 400) {
                throw IllegalStateException(
                    when (code) {
                        401 -> "GitHub no acepta la clave (¿caducada?). Cámbiala en Ajustes > GitHub"
                        403, 429 -> "GitHub me ha limitado las consultas por ahora" + if (token(context).isEmpty()) "; con tu clave en Ajustes > GitHub tendrías muchas más" else ""
                        404 -> "GitHub dice que no existe (o es privado y no tengo tu clave)"
                        422 -> "GitHub no ha aceptado la búsqueda"
                        else -> "GitHub respondió con el error $code"
                    },
                )
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    @Volatile
    private var reposCache: Pair<Long, List<JSONObject>>? = null

    private fun repos(context: Context): List<JSONObject> {
        val now = System.currentTimeMillis()
        reposCache?.let { (at, list) -> if (now - at < 10 * 60_000) return list }
        val path = if (token(context).isNotEmpty()) "/user/repos?per_page=100&sort=updated" else "/users/${user(context)}/repos?per_page=100&sort=updated"
        val array = JSONArray(call(context, path))
        val list = List(array.length()) { array.getJSONObject(it) }
        reposCache = now to list
        return list
    }

    /** El repo que nombra (aunque lo diga sin guiones o a medias): "buid nexo apk" → maurizio-bot/Buid-nexo-apk. */
    fun findRepo(context: Context, said: String): String? {
        val s = said.trim().trimEnd('.', '?', '!')
        if (Regex("^[\\w.-]+/[\\w.-]+$").matches(s)) return s
        val wanted = QuickActions.norm(s.replace('-', ' ').replace('_', ' '))
        if (wanted.isEmpty()) return null
        val all = repos(context).map { it.getString("full_name") }
        fun key(full: String) = QuickActions.norm(full.substringAfter('/').replace('-', ' ').replace('_', ' '))
        return all.firstOrNull { key(it) == wanted }
            ?: all.firstOrNull { key(it).replace(" ", "") == wanted.replace(" ", "") }
            ?: all.firstOrNull { key(it).contains(wanted) }
            ?: all.firstOrNull { wanted.contains(key(it)) && key(it).length >= 3 }
    }

    fun reposText(context: Context): String {
        val list = repos(context)
        if (list.isEmpty()) return "${user(context)} no tiene repos públicos" + if (token(context).isEmpty()) " (sin tu clave no veo los privados)." else "."
        val lines = list.take(10).map { r ->
            val issues = r.optInt("open_issues_count")
            "${r.getString("full_name")} (cambiado el ${r.optString("pushed_at").take(10)}" + (if (issues > 0) ", $issues abiertas" else "") + ")"
        }
        return "Tus repos, los más recientes primero:\n" + lines.joinToString("\n")
    }

    fun commits(context: Context, repo: String): String {
        val array = JSONArray(call(context, "/repos/$repo/commits?per_page=5"))
        if (array.length() == 0) return "No hay cambios en $repo."
        val lines = List(array.length()) { i ->
            val c = array.getJSONObject(i).getJSONObject("commit")
            val date = c.getJSONObject("author").optString("date").take(10)
            "$date: ${c.getString("message").lineSequence().first().take(120)}"
        }
        return "Últimos cambios en $repo:\n" + lines.joinToString("\n")
    }

    fun issues(context: Context, repo: String, pulls: Boolean): String {
        val path = if (pulls) "/repos/$repo/pulls?state=open&per_page=10" else "/repos/$repo/issues?state=open&per_page=20"
        val array = JSONArray(call(context, path))
        val items = List(array.length()) { array.getJSONObject(it) }.filter { pulls || !it.has("pull_request") }.take(10)
        val what = if (pulls) "pull requests" else "issues"
        if (items.isEmpty()) return "No hay $what abiertas en $repo."
        return "${items.size} $what abiertas en $repo:\n" + items.joinToString("\n") { "#${it.getInt("number")} ${it.getString("title").take(110)}" }
    }

    fun builds(context: Context, repo: String): String {
        val runs = JSONObject(call(context, "/repos/$repo/actions/runs?per_page=3")).optJSONArray("workflow_runs") ?: JSONArray()
        if (runs.length() == 0) return "$repo no tiene compilaciones en GitHub Actions."
        val lines = List(runs.length()) { i ->
            val r = runs.getJSONObject(i)
            val state = when (r.optString("conclusion")) {
                "success" -> "bien"
                "failure" -> "FALLÓ"
                "cancelled" -> "cancelada"
                "" , "null" -> "en marcha"
                else -> r.optString("conclusion")
            }
            "${r.optString("head_branch")} (${r.optString("created_at").take(16).replace('T', ' ')}): $state"
        }
        return "Últimas compilaciones de $repo:\n" + lines.joinToString("\n")
    }

    /** Un archivo del repo ([path] null = el README), como texto. */
    fun file(context: Context, repo: String, path: String?): String {
        val api = if (path == null) "/repos/$repo/readme" else "/repos/$repo/contents/" + path.trimStart('/').split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val o = JSONObject(call(context, api))
        val content = o.optString("content")
        if (content.isEmpty()) return "(Es una carpeta o un archivo demasiado grande para leerlo por aquí.)"
        return String(Base64.decode(content, Base64.DEFAULT))
    }

    fun search(context: Context, repo: String, text: String): String {
        if (token(context).isEmpty()) return "Para buscar dentro del código GitHub pide tu clave: ponla en Ajustes > GitHub."
        val q = URLEncoder.encode("$text repo:$repo", "UTF-8")
        val items = JSONObject(call(context, "/search/code?per_page=10&q=$q")).optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return "No encuentro «$text» en el código de $repo."
        return "«$text» sale en ${items.length()} archivos de $repo:\n" + List(items.length()) { items.getJSONObject(it).getString("path") }.joinToString("\n")
    }

    fun createIssue(context: Context, repo: String, title: String): String {
        val body = JSONObject().put("title", title).put("body", "Creada desde Ultra Yo a petición de Maurizio.")
        val o = JSONObject(call(context, "/repos/$repo/issues", "POST", body))
        Control.log("Creó la issue #${o.optInt("number")} en $repo")
        return "Creada la issue #${o.optInt("number")} en $repo."
    }
}

/** Para que el modelo pueda mirar los proyectos de GitHub de Maurizio cuando le pregunta por ellos. */
class GitHubTools(context: Context) : ToolSet {

    private val context = context.applicationContext

    @Tool(description = "Consulta los proyectos de código de Maurizio en GitHub (solo lee). Úsala cuando pregunte por sus repos o su código. Ejemplos de orden: \"mis repos\", \"últimos cambios de nexo\", \"issues de nexo\", \"pull requests de nexo\", \"cómo va la compilación de nexo\", \"lee el archivo README.md de nexo\", \"busca login en el código de nexo\".")
    fun consultaGithub(
        @ToolParam(description = "La consulta en español, como en los ejemplos") orden: String,
    ): String {
        val p = GitHub.entender(orden) ?: return "No entendí la consulta. Usa una de las formas de los ejemplos."
        if (p is GitHub.Pedido.NuevaIssue) return "Crear issues solo lo hace Maurizio diciéndomelo directamente."
        return when (val r = runCatching { GitHub.hacer(context, p, orden) }.getOrElse { return "No pude consultar GitHub: ${it.message}" }) {
            is GitHub.Resultado.Texto -> r.texto
            is GitHub.Resultado.ParaModelo -> r.prompt
            is GitHub.Resultado.Confirmar -> r.plan
        }
    }
}
