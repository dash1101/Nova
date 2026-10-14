package app.novalabs.nova

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.LocalTextStyle

/**
 * Translations. The app is written in English; assets/i18n/<lang>.json maps each piece of text to the
 * chosen language ({"English": "translation", "Freed {0} MB": "{0} MB liberados", …}), built by
 * i18n/build.py from the same catalog as Nova web. Text with values in it is matched as a pattern.
 * Anything without a translation stays English, and so does text written by the server.
 *
 * Every Text(...) in this package goes through tr() — the Text below has the same signature as
 * Material's and shadows it (files import material3.* rather than material3.Text, so this one wins).
 */
object I18n {
    val LANGS = listOf("en" to "English", "es" to "Español", "de" to "Deutsch", "fr" to "Français", "pt" to "Português", "it" to "Italiano",
        "ru" to "Русский", "zh" to "简体中文", "ja" to "日本語", "ko" to "한국어")
    var lang = "en"; private set
    private var exact: Map<String, String> = emptyMap()
    private var patterns: List<Triple<Regex, String, List<Int>>> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(512, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 2000 }

    fun pick(pref: String): String {
        val want = if (pref.isNotEmpty() && pref != "auto") pref else java.util.Locale.getDefault().language
        return LANGS.firstOrNull { it.first == want.lowercase().take(2) }?.first ?: "en"
    }

    fun load(ctx: Context, pref: String) {
        lang = pick(pref); synchronized(cache) { cache.clear() }
        if (lang == "en") { exact = emptyMap(); patterns = emptyList(); return }
        runCatching {
            val o = org.json.JSONObject(ctx.assets.open("i18n/$lang.json").bufferedReader().use { it.readText() })
            val ex = HashMap<String, String>(o.length() * 2); val ps = ArrayList<Triple<Regex, String, List<Int>>>()
            for (k in o.keys()) {
                val v = o.optString(k); if (v.isEmpty()) continue
                if (Regex("\\{\\d\\}").containsMatchIn(k)) {
                    val names = ArrayList<Int>()
                    val re = Regex("\\{(\\d)\\}").split(k).map { Regex.escape(it) }.let { parts ->
                        val ns = Regex("\\{(\\d)\\}").findAll(k).map { it.groupValues[1].toInt() }.toList(); names.addAll(ns)
                        parts.foldIndexed(StringBuilder("^")) { i, sb, p -> sb.append(p); if (i < ns.size) sb.append("([\\s\\S]+?)"); sb }.append("$").toString()
                    }
                    ps.add(Triple(Regex(re), v, names))
                } else ex[k] = v
            }
            exact = ex; patterns = ps.sortedByDescending { it.first.pattern.length }
        }.onFailure { exact = emptyMap(); patterns = emptyList() }
    }

    /** One piece of English text → the chosen language (the original when there's no translation). */
    fun tr(s: String): String {
        if (exact.isEmpty() || s.isBlank()) return s
        synchronized(cache) { cache[s]?.let { return it } }
        val key = s.replace(Regex("\\s+"), " ").trim()
        val out = exact[key] ?: patterns.firstNotNullOfOrNull { (re, v, names) ->
            re.matchEntire(key)?.let { m -> Regex("\\{(\\d)\\}").replace(v) { g -> m.groupValues.getOrNull(names.indexOf(g.groupValues[1].toInt()) + 1) ?: "" } }
        } ?: s
        synchronized(cache) { cache[s] = out }
        return out
    }
}

fun tr(s: String) = I18n.tr(s)

@Composable fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current,
) {
    androidx.compose.material3.Text(text = I18n.tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
        overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style)
}
