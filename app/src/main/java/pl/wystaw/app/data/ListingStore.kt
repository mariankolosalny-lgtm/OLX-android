package pl.wystaw.app.data

import android.content.Context
import org.json.JSONObject

/**
 * Bieżące ogłoszenie zapisane w SharedPreferences, żeby usługa ułatwień dostępu
 * (działająca w osobnym komponencie) mogła je odczytać.
 *
 * „Uzbrojenie” oznacza: użytkownik kliknął „Otwórz OLX”, więc usługa ma wypełniać
 * formularz. Wygasa po [ARM_TTL_MS], żeby nie wpisywać starego ogłoszenia przypadkiem.
 */
object ListingStore {
    private const val PREFS = "listing_store"
    private const val KEY_LISTING = "listing"
    private const val KEY_ARMED_AT = "armed_at"
    private const val KEY_PHOTO_COUNT = "photo_count"
    private const val ARM_TTL_MS = 2 * 60 * 60 * 1000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, listing: Listing) {
        prefs(ctx).edit().putString(KEY_LISTING, listing.toJson().toString()).apply()
    }

    fun load(ctx: Context): Listing? {
        val raw = prefs(ctx).getString(KEY_LISTING, null) ?: return null
        return runCatching { Listing.fromJson(JSONObject(raw)) }.getOrNull()
    }

    /**
     * Ile zdjęć tego ogłoszenia leży na górze albumu Wystaw (PhotoSaver daje im najnowszą datę) –
     * usługa zaznacza tyle pierwszych zdjęć w systemowym wyborze zdjęć. 0 = brak zdjęć do dodania.
     */
    fun setPhotoCount(ctx: Context, count: Int) {
        prefs(ctx).edit().putInt(KEY_PHOTO_COUNT, count).apply()
    }

    fun photoCount(ctx: Context): Int = prefs(ctx).getInt(KEY_PHOTO_COUNT, 0)

    fun arm(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_ARMED_AT, System.currentTimeMillis()).apply()
    }

    fun disarm(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ARMED_AT).apply()
    }

    /** Znacznik czasu uzbrojenia albo 0, jeśli nieaktywne lub wygasło. */
    fun armedAt(ctx: Context): Long {
        val at = prefs(ctx).getLong(KEY_ARMED_AT, 0L)
        return if (at > 0 && System.currentTimeMillis() - at < ARM_TTL_MS) at else 0L
    }

    /** Ogłoszenie do wpisania – tylko gdy usługa jest uzbrojona. */
    fun armedListing(ctx: Context): Listing? = if (armedAt(ctx) > 0) load(ctx) else null
}
