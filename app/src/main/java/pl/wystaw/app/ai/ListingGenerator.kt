package pl.wystaw.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.wystaw.app.data.AiConfig
import pl.wystaw.app.data.AiProvider
import pl.wystaw.app.data.Listing
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [retryable] – błąd po stronie modelu (przeciążenie, limit, brak modelu, sieć): warto spróbować innego modelu. */
class AiException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * Wysyła zdjęcia do wybranego modelu AI i zamienia odpowiedź na [Listing].
 * Wywoływać poza wątkiem głównym.
 */
object ListingGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Próbuje modeli z [configs] po kolei (patrz AppSettings.fallbackChain); przechodzi do następnego tylko przy błędach,
     * które zależą od modelu. Błąd klucza albo zdjęć zatrzymuje od razu.
     */
    fun generate(ctx: Context, configs: List<AiConfig>, photos: List<Uri>, sellerHint: String): Listing {
        if (configs.isEmpty()) throw AiException("Brak klucza API – uzupełnij go w Ustawieniach.")
        if (photos.isEmpty()) throw AiException("Dodaj co najmniej jedno zdjęcie.")

        val images = photos.take(MAX_PHOTOS_FOR_AI).map { encodeJpeg(ctx, it) }
        val prompt = buildPrompt(sellerHint)
        val failures = mutableListOf<String>()
        for (config in configs) {
            try {
                return generateWith(config, images, prompt)
            } catch (e: AiException) {
                if (!e.retryable) throw e
                failures += "${config.model}: ${e.message?.take(80)}"
            }
        }
        throw AiException(
            "Żaden model AI nie odpowiedział – spróbuj za chwilę. Próbowano:\n" + failures.joinToString("\n")
        )
    }

    private fun generateWith(config: AiConfig, images: List<String>, prompt: String): Listing {
        val text = when (config.provider) {
            AiProvider.GEMINI -> callGemini(config, images, prompt)
            AiProvider.CLAUDE -> callClaude(config, images, prompt)
        }
        val json = extractJson(text)
            ?: throw AiException("Model nie zwrócił poprawnego ogłoszenia. Spróbuj ponownie.", retryable = true)
        val listing = Listing.fromJson(json)
        if (listing.title.isBlank() || listing.description.isBlank()) {
            throw AiException("Nie udało się rozpoznać przedmiotu. Dodaj wyraźniejsze zdjęcia lub opis od siebie.")
        }
        return listing
    }

    private const val MAX_PHOTOS_FOR_AI = 6
    private const val MAX_SIDE = 1280

    private fun buildPrompt(sellerHint: String): String = buildString {
        appendLine("Jesteś doświadczonym sprzedawcą na OLX.pl. Na podstawie zdjęć przygotuj ogłoszenie sprzedaży po polsku.")
        appendLine("Zwróć WYŁĄCZNIE obiekt JSON (bez markdown, bez komentarzy) o polach:")
        appendLine("""{"title": string, "category": string, "description": string, "condition": "nowy"|"używany"|"uszkodzony", "price_min": int, "price_max": int, "price_suggested": int, "params": [{"name": string, "value": string}], "notes": [string]}""")
        appendLine("Zasady:")
        appendLine("- title: maks. 70 znaków; co to jest, marka i model (jeśli widoczne), najważniejsza cecha. Bez słów typu „okazja”, bez wykrzykników i WIELKICH LITER.")
        appendLine("- category: ścieżka kategorii OLX.pl, np. „Sport i Hobby > Rowery > Górskie”.")
        appendLine("- description: 3–6 krótkich akapitów: co to jest, najważniejsze parametry, stan (uczciwie opisz widoczne usterki), co jest w zestawie, odbiór/wysyłka. Bez emoji.")
        appendLine("- price_*: szacunek ceny na polskim rynku wtórnym w PLN; price_suggested to cena pozwalająca sprzedać w rozsądnym czasie.")
        appendLine("- params: parametry, które OLX zwykle wymaga w tej kategorii (np. Marka, Model, Rozmiar, Kolor) – tylko te, które da się ustalić.")
        appendLine("- NIE wymyślaj faktów, których nie widać na zdjęciach ani nie podał sprzedający (marki, modelu, rozmiaru, roku). Jeśli coś jest niepewne, pomiń to i dopisz do notes, co sprzedający powinien sprawdzić.")
        appendLine("- Odczytaj metki, tabliczki znamionowe i napisy na zdjęciach, jeśli są.")
        if (sellerHint.isNotBlank()) {
            appendLine()
            appendLine("Informacje od sprzedającego (traktuj jako prawdziwe): ${sellerHint.trim()}")
        }
    }

    /** Zmniejsza zdjęcie do [MAX_SIDE] px (z uwzględnieniem obrotu EXIF) i koduje jako JPEG base64. */
    private fun encodeJpeg(ctx: Context, uri: Uri): String {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        } catch (e: IOException) {
            throw AiException("Nie można odczytać zdjęcia: ${e.message}")
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun callGemini(config: AiConfig, images: List<String>, prompt: String): String {
        val parts = JSONArray()
        images.forEach { data ->
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", data)))
        }
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent")
            .header("x-goog-api-key", config.apiKey)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        val response = execute(request)
        val candidates = response.optJSONArray("candidates")
            ?: throw AiException("Gemini nie zwrócił odpowiedzi: ${response.toString().take(300)}")
        val sb = StringBuilder()
        val partsOut = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        for (i in 0 until partsOut.length()) sb.append(partsOut.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    private fun callClaude(config: AiConfig, images: List<String>, prompt: String): String {
        val content = JSONArray()
        images.forEach { data ->
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", data),
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 2048)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        val response = execute(request)
        val blocks = response.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString()
    }

    private fun execute(request: Request): JSONObject {
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw AiException("Brak połączenia z serwerem AI: ${e.message}", retryable = true)
        }
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            val hint = when (code) {
                400, 401, 403 -> " Sprawdź klucz API i nazwę modelu w Ustawieniach."
                404 -> " Model o tej nazwie nie istnieje – zmień go w Ustawieniach."
                429 -> " Przekroczono limit zapytań – spróbuj za chwilę."
                else -> ""
            }
            // 529 = przeciążenie u Anthropic, 503 = u Google.
            val retryable = code == 404 || code == 408 || code == 429 || code >= 500
            throw AiException("Błąd AI ($code): ${msg ?: text.take(200)}.$hint", retryable)
        }
        return runCatching { JSONObject(text) }.getOrElse { throw AiException("Nieczytelna odpowiedź serwera AI.") }
    }

    /** Wyciąga pierwszy obiekt JSON z odpowiedzi (model czasem dokleja ```json ... ```). */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}
