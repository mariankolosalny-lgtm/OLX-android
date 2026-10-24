package pl.wystaw.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Jeden parametr ogłoszenia, np. „Marka: Kross”. */
data class Param(val name: String, val value: String)

/** Ogłoszenie przygotowane przez AI i poprawione przez użytkownika. */
data class Listing(
    val title: String = "",
    val category: String = "",
    val description: String = "",
    val condition: String = CONDITION_USED,
    val price: String = "",
    val priceMin: Int? = null,
    val priceMax: Int? = null,
    val params: List<Param> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    /** Tekst parametrów do skopiowania, jeden w linii. */
    fun paramsText(): String = params.joinToString("\n") { "${it.name}: ${it.value}" }

    /** Sama liczba – do pola ceny w OLX. */
    fun priceDigits(): String = price.filter { it.isDigit() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("category", category)
        put("description", description)
        put("condition", condition)
        put("price", price)
        priceMin?.let { put("price_min", it) }
        priceMax?.let { put("price_max", it) }
        put("params", JSONArray().apply {
            params.forEach { put(JSONObject().put("name", it.name).put("value", it.value)) }
        })
        put("notes", JSONArray().apply { notes.forEach { put(it) } })
    }

    companion object {
        const val TITLE_MAX = 70
        const val CONDITION_NEW = "nowy"
        const val CONDITION_USED = "używany"
        const val CONDITION_DAMAGED = "uszkodzony"
        val CONDITIONS = listOf(CONDITION_NEW, CONDITION_USED, CONDITION_DAMAGED)

        /** Czyta zarówno odpowiedź modelu AI, jak i zapis z [toJson]. */
        fun fromJson(o: JSONObject): Listing {
            val params = mutableListOf<Param>()
            o.optJSONArray("params")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val name = p.optString("name").trim()
                    val value = p.optString("value").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) params += Param(name, value)
                }
            }
            val notes = mutableListOf<String>()
            o.optJSONArray("notes")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { notes += it }
            }
            val min = o.optIntOrNull("price_min")
            val max = o.optIntOrNull("price_max")
            val suggested = o.optIntOrNull("price_suggested")
            val price = o.optString("price").ifBlank { suggested?.toString() ?: "" }
            val condition = o.optString("condition").lowercase().let { c ->
                when {
                    c.startsWith("now") -> CONDITION_NEW
                    c.startsWith("usz") -> CONDITION_DAMAGED
                    else -> CONDITION_USED
                }
            }
            return Listing(
                title = o.optString("title").trim().take(TITLE_MAX),
                category = o.optString("category").trim(),
                description = o.optString("description").trim(),
                condition = condition,
                price = price,
                priceMin = min,
                priceMax = max,
                params = params,
                notes = notes,
            )
        }

        private fun JSONObject.optIntOrNull(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            val v = opt(key)
            return when (v) {
                is Number -> v.toInt()
                is String -> v.filter { it.isDigit() }.toIntOrNull()
                else -> null
            }
        }
    }
}
