package pl.wystaw.app.data

import android.content.Context

/**
 * [fallbackModels] – próbowane po kolei, gdy wybrany model jest przeciążony (503), ma limit (429) albo nie istnieje (404).
 * Aliasy „-latest” Google przenosi na nowe wersje, więc nie zestarzeją się.
 */
enum class AiProvider(
    val label: String,
    val defaultModel: String,
    val keyUrl: String,
    val fallbackModels: List<String>,
) {
    GEMINI(
        "Gemini (Google)", "gemini-2.5-flash", "https://aistudio.google.com/apikey",
        listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.0-flash"),
    ),
    CLAUDE(
        "Claude (Anthropic)", "claude-sonnet-4-5", "https://console.anthropic.com/settings/keys",
        listOf("claude-sonnet-5-5", "claude-sonnet-4-5", "claude-haiku-5-5", "claude-haiku-4-5"),
    ),
}

data class AiConfig(val provider: AiProvider, val apiKey: String, val model: String)

/** Ustawienia: dostawca AI, klucz API i nazwa modelu – osobno dla każdego dostawcy. */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_PROVIDER = "provider"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun provider(ctx: Context): AiProvider =
        runCatching { AiProvider.valueOf(prefs(ctx).getString(KEY_PROVIDER, null) ?: "") }
            .getOrDefault(AiProvider.GEMINI)

    fun apiKey(ctx: Context, p: AiProvider): String =
        prefs(ctx).getString("key_${p.name}", "") ?: ""

    fun model(ctx: Context, p: AiProvider): String =
        (prefs(ctx).getString("model_${p.name}", null) ?: "").ifBlank { p.defaultModel }

    fun save(ctx: Context, provider: AiProvider, apiKey: String, model: String) {
        prefs(ctx).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString("key_${provider.name}", apiKey.trim())
            .putString("model_${provider.name}", model.trim())
            .apply()
    }

    fun current(ctx: Context): AiConfig {
        val p = provider(ctx)
        return AiConfig(p, apiKey(ctx, p), model(ctx, p))
    }

    /**
     * Kolejność prób: wybrany model, zapasowe modele tego dostawcy, potem drugi dostawca –
     * tylko jeśli ma zapisany klucz (jego wybrany model, a po nim zapasowe).
     */
    fun fallbackChain(ctx: Context): List<AiConfig> {
        val first = provider(ctx)
        return (listOf(first) + AiProvider.entries.filter { it != first })
            .filter { apiKey(ctx, it).isNotBlank() }
            .flatMap { p ->
                (listOf(model(ctx, p)) + p.fallbackModels).distinct().map { AiConfig(p, apiKey(ctx, p), it) }
            }
    }
}
