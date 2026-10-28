package com.ultrayo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Pasa una pregunta a Claude sin API ni coste: abre la app de Claude (o claude.ai)
 * con la pregunta ya escrita, usando la cuenta normal de Maurizio.
 */
object ClaudeLink {

    private const val CLAUDE_APP = "com.anthropic.claude"

    private val command = Regex(
        "^(preg[uú]ntale|pregunta|consulta|dile|p[aá]sale)( esto)? a claude( que)?[,:]?\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    /** La pregunta, si [text] es una orden del tipo "pregúntale a Claude …". */
    fun questionIn(text: String): String? = command.find(text.trim())?.groupValues?.get(4)?.trim()

    /** Devuelve lo que la IA debe decir después de abrir Claude. */
    fun open(context: Context, question: String): String {
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, question)
            .setPackage(CLAUDE_APP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(share)
            "Te paso a la app de Claude con la pregunta ya escrita."
        } catch (e: ActivityNotFoundException) {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new?q=" + Uri.encode(question)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(web)
                "No tienes la app de Claude, así que te abro claude.ai con la pregunta."
            } catch (e: ActivityNotFoundException) {
                "No encontré ni la app de Claude ni un navegador."
            }
        }
    }
}
