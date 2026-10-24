package pl.wystaw.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import pl.wystaw.app.data.Listing
import pl.wystaw.app.data.ListingStore

/**
 * Usługa ułatwień dostępu, która:
 *  1. gdy w aplikacji OLX pojawią się pola „Tytuł”, „Opis”, „Cena” – wpisuje w nie przygotowane ogłoszenie,
 *  2. pokazuje nad OLX pasek z polami (Tytuł, Opis, Cena, Kategoria, Stan, Parametry) – stuknięcie
 *     wstawia wartość do aktywnego pola albo kopiuje ją do schowka.
 *
 * Działa tylko w pakietach OLX i tylko gdy użytkownik kliknął w Wystaw „Otwórz OLX” (ListingStore.arm).
 * Pola rozpoznawane są heurystycznie po podpowiedziach i etykietach, bo OLX nie publikuje identyfikatorów widoków.
 */
class OlxFillService : AccessibilityService() {

    companion object {
        val OLX_PACKAGES = setOf("pl.tablica", "com.olx.pl")
        private const val FILL_DELAY_MS = 350L

        private val TITLE_WORDS = listOf("tytuł", "tytul", "title")
        private val DESCRIPTION_WORDS = listOf("opis", "description")
        private val PRICE_WORDS = listOf("cena", "price", "zł")

        // Teksty formularza OLX 5.183 (Stan: kafelki „Używane”/„Nowe”; Przesyłka OLX: rozwijane rozmiary S/M/L/XL
        // z polami wyboru przewoźników).
        private const val CONDITION_SECTION = "Stan"
        private const val DELIVERY_SECTION = "Metoda dostawy"
        private const val CONDITION_USED_LABEL = "Używane"
        private const val CONDITION_NEW_LABEL = "Nowe"
        private const val PARCEL_SIZE = "M"
        private const val PARCEL_LOCKER_PREFIX = "InPost Paczkomat"
        // Okno „Masz niedokończone ogłoszenie. Chcesz je dokończyć?” – zawsze zaczynamy od nowa (ustalenie z użytkownikiem).
        private const val DRAFT_START_OVER = "Nie, zaczynam od nowa"

        // Zdjęcia: „Dodaj zdjęcia” → arkusz „Zrób zdjęcie / Wybierz zdjęcie” → systemowy wybór zdjęć (Android 14+,
        // teksty po polsku). Zdjęcia ogłoszenia są na samej górze siatki, bo PhotoSaver daje im najnowszą datę.
        private const val PHOTOS_ADD = "Dodaj zdjęcia"
        private const val PHOTOS_FROM_GALLERY = "Wybierz zdjęcie"
        private const val PICKER_GRID = "Siatka multimediów"
        private const val PICKER_SELECTED_PREFIX = "Wybrano"
        private const val PICKER_DONE = "Gotowe"

        // Publikacja („Dodaj ogłoszenie” na dole formularza) – dopiero gdy wszystko wypełnione, a zdjęcia
        // miały czas się wgrać.
        private const val PUBLISH_BUTTON_ID = "postAdBtn"
        private const val PUBLISH_DELAY_AFTER_PHOTOS_MS = 6000L

        // Po publikacji OLX pokazuje „Promuj ogłoszenie” (płatne „Dodaj promowanie” obok darmowego „Nie promuj”).
        // Użytkownik chce zawsze „Nie promuj” – klikamy po ID, żeby nigdy nie trafić w płatny przycisk.
        private const val DONT_PROMOTE_ID = "monetization_vas_dont_promote_cta"
        private const val PROMOTE_SKIP_WINDOW_MS = 2 * 60 * 1000L
    }

    private enum class Field { TITLE, DESCRIPTION, PRICE }

    private val handler = Handler(Looper.getMainLooper())
    private var fillScheduled = false
    private val filled = mutableSetOf<Field>()
    private var armedStamp = 0L

    // Wybory z list klikamy raz na ogłoszenie – potem użytkownik może je zmienić, a my ich nie ruszamy.
    private var conditionDone = false
    private var parcelDone = false
    private var parcelExpandClicked = false

    private var photosWanted = 0
    private var photoAddClicked = false
    private var photoSourceClicked = false
    private val pickerClicked = mutableSetOf<Int>()
    private var photosDoneAt = 0L
    private var publishClicked = false
    private var skipPromoteUntil = 0L

    private var overlay: View? = null
    private var overlayAtTop = true
    private var overlayDismissed = false

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (pkg in OLX_PACKAGES && System.currentTimeMillis() < skipPromoteUntil && ListingStore.armedAt(this) == 0L) {
            // Po naszej publikacji usługa jest rozbrojona – zostaje tylko pominięcie promowania.
            rootInActiveWindow?.let { root ->
                findAll(root) { it.viewIdResourceName?.endsWith("/$DONT_PROMOTE_ID") == true }.firstOrNull()
                    ?.takeIf { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    ?.let { skipPromoteUntil = 0L }
            }
            return
        }
        if (pkg in OLX_PACKAGES) {
            val stamp = ListingStore.armedAt(this)
            if (stamp == 0L) {
                hideOverlay()
                return
            }
            if (stamp != armedStamp) {
                // Nowe ogłoszenie – zaczynamy od zera.
                armedStamp = stamp
                resetProgress()
                overlayDismissed = false
            }
            if (!overlayDismissed) showOverlay()
            scheduleFill()
        } else if (isPhotoPicker(pkg) && photoSourceClicked && photosDoneAt == 0L && ListingStore.armedAt(this) != 0L) {
            scheduleFill()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isTransientPackage(pkg)) {
            // Użytkownik wyszedł z OLX – chowamy pasek.
            hideOverlay()
            overlayDismissed = false
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun resetProgress() {
        filled.clear()
        conditionDone = false
        parcelDone = false
        parcelExpandClicked = false
        photosWanted = ListingStore.photoCount(this)
        photoAddClicked = false
        photoSourceClicked = false
        pickerClicked.clear()
        photosDoneAt = 0L
        publishClicked = false
    }

    private fun isPhotoPicker(pkg: String?): Boolean =
        pkg != null && (pkg.contains("photopicker") || pkg.endsWith("providers.media.module"))

    /** Klawiatura, systemowe okna i galeria zdjęć (wybór zdjęć z OLX) nie powinny chować paska. */
    private fun isTransientPackage(pkg: String): Boolean =
        pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("photopicker") ||
            pkg.contains("documentsui") ||
            pkg.contains("permissioncontroller")

    // ---------- Automatyczne wypełnianie ----------

    private fun scheduleFill() {
        if (fillScheduled) return
        fillScheduled = true
        handler.postDelayed({
            fillScheduled = false
            runCatching { tryFill() }
        }, FILL_DELAY_MS)
    }

    private fun tryFill() {
        val listing = ListingStore.armedListing(this) ?: return
        if (publishClicked) return
        val root = rootInActiveWindow ?: return
        if (isPhotoPicker(root.packageName?.toString())) {
            pickPhotos(root)
            return
        }
        if (root.packageName?.toString() !in OLX_PACKAGES) return

        val editables = mutableListOf<AccessibilityNodeInfo>()
        collectEditables(root, editables, depth = 0)

        // Stary szkic: odrzucamy go, zanim cokolwiek wpiszemy. Pod oknem leży formularz ze starymi danymi, które
        // mogliśmy już uznać za „wpisane przez użytkownika” – po odrzuceniu zaczynamy liczyć od zera.
        findAll(root) { it.text?.toString() == DRAFT_START_OVER }
            .firstNotNullOfOrNull { clickableAncestor(it) }
            ?.let { button ->
                if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) resetProgress()
                return
            }

        // Arkusz po „Dodaj zdjęcia”: wybieramy galerię.
        if (photoAddClicked && !photoSourceClicked) {
            findAll(root) { it.text?.toString() == PHOTOS_FROM_GALLERY }
                .firstNotNullOfOrNull { clickableAncestor(it) }
                ?.let { photoSourceClicked = it.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
        }

        val newlyFilled = mutableListOf<String>()
        if (!conditionDone && selectCondition(root, listing)) {
            conditionDone = true
            newlyFilled += "stan"
        }
        if (!parcelDone && selectParcelLocker(root)) {
            parcelDone = true
            newlyFilled += "Paczkomat $PARCEL_SIZE"
        }
        for (node in editables) {
            val field = classify(node) ?: continue
            if (field in filled) continue

            if (!isEmpty(node)) {
                // Użytkownik już coś wpisał – nie nadpisujemy.
                filled += field
                continue
            }
            val value = valueFor(field, listing)
            if (value.isBlank()) {
                filled += field
                continue
            }
            if (setText(node, value)) {
                filled += field
                newlyFilled += labelFor(field)
            }
        }
        if (newlyFilled.isNotEmpty()) {
            toast("Wystaw wpisał: ${newlyFilled.joinToString(", ")}")
        }

        // Zdjęcia dodajemy, gdy formularz jest już wypełniony tekstem (tytuł wpisany = formularz załadowany).
        if (photosWanted > 0 && !photoAddClicked && Field.TITLE in filled) {
            findAll(root) { it.text?.toString() == PHOTOS_ADD }
                .firstNotNullOfOrNull { clickableAncestor(it) }
                ?.let { photoAddClicked = it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            return
        }

        tryPublish(root)
    }

    /** Systemowy wybór zdjęć: zaznacza [photosWanted] pierwszych zdjęć (każde klikamy raz), potem „Gotowe”. */
    private fun pickPhotos(root: AccessibilityNodeInfo) {
        if (!photoSourceClicked || photosDoneAt != 0L) return
        val grid = findAll(root) { it.contentDescription?.toString() == PICKER_GRID }.firstOrNull() ?: return
        val items = findAll(grid) { it.isClickable }.take(photosWanted)
        if (items.size < photosWanted) return

        val selected = items.count { item ->
            findAll(item) { it.contentDescription?.startsWith(PICKER_SELECTED_PREFIX) == true }.isNotEmpty()
        }
        if (selected < photosWanted) {
            items.forEachIndexed { i, item ->
                if (i !in pickerClicked && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) pickerClicked += i
            }
            return
        }
        val done = findAll(root) { it.text?.toString() == PICKER_DONE }
            .firstNotNullOfOrNull { clickableAncestor(it) } ?: return
        if (done.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            photosDoneAt = System.currentTimeMillis()
            // Po powrocie do OLX może nie być żadnego zdarzenia – sami sprawdzamy, czy można publikować.
            handler.postDelayed({ runCatching { tryFill() } }, PUBLISH_DELAY_AFTER_PHOTOS_MS + 500)
        }
    }

    /**
     * Klika „Dodaj ogłoszenie” (prośba użytkownika), ale tylko gdy wszystko jest gotowe: tytuł, opis, cena, stan,
     * Paczkomat i zdjęcia wgrywane od co najmniej [PUBLISH_DELAY_AFTER_PHOTOS_MS]. Bez zdjęć nie publikujemy.
     */
    private fun tryPublish(root: AccessibilityNodeInfo) {
        val ready = filled.size == Field.entries.size && conditionDone && parcelDone &&
            photosDoneAt != 0L && System.currentTimeMillis() - photosDoneAt >= PUBLISH_DELAY_AFTER_PHOTOS_MS
        if (!ready) return
        val button = findAll(root) { it.viewIdResourceName?.endsWith("/$PUBLISH_BUTTON_ID") == true }.firstOrNull()
            ?: return
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            publishClicked = true
            skipPromoteUntil = System.currentTimeMillis() + PROMOTE_SKIP_WINDOW_MS
            ListingStore.disarm(this)
            hideOverlay()
            toast("Wystaw kliknął „Dodaj ogłoszenie”")
        }
    }

    /** Stan: „Nowe” tylko gdy w Wystaw wybrano „nowy”, w pozostałych przypadkach „Używane”. */
    private fun selectCondition(root: AccessibilityNodeInfo, listing: Listing): Boolean {
        val label = if (listing.condition == Listing.CONDITION_NEW) CONDITION_NEW_LABEL else CONDITION_USED_LABEL
        val section = sectionOf(root, CONDITION_SECTION) ?: return false
        val chip = findAll(section) { it.text?.toString() == label }
            .firstNotNullOfOrNull { clickableAncestor(it) } ?: return false
        return chip.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Przesyłka OLX: rozwija rozmiar [PARCEL_SIZE] i zaznacza w nim InPost Paczkomat (jeśli nie jest zaznaczony).
     * Zwraca true, gdy Paczkomat jest zaznaczony; false, gdy sekcji jeszcze nie ma albo czekamy na rozwinięcie.
     */
    private fun selectParcelLocker(root: AccessibilityNodeInfo): Boolean {
        // Szukamy tylko w sekcji dostawy – samotne „M” bywa też np. rozmiarem ubrania w parametrach.
        val section = sectionOf(root, DELIVERY_SECTION) ?: return false
        val sizeRow = findAll(section) { it.text?.toString() == PARCEL_SIZE }
            .firstNotNullOfOrNull { clickableAncestor(it) } ?: return false

        val locker = findAll(sizeRow) { it.text?.toString()?.startsWith(PARCEL_LOCKER_PREFIX) == true }.firstOrNull()
        if (locker == null) {
            // Rozmiar zwinięty – rozwijamy raz; kolejne zdarzenie z OLX pokaże przewoźników.
            if (!parcelExpandClicked) {
                parcelExpandClicked = sizeRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return false
        }
        val row = locker.parent ?: return false
        val checkBox = (0 until row.childCount)
            .mapNotNull { row.getChild(it) }
            .firstOrNull { it.className?.toString()?.endsWith("CheckBox") == true }
            ?: return false
        return checkBox.isChecked || checkBox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Kontener sekcji = rodzic jej nagłówka (nagłówek i zawartość są w OLX rodzeństwem). */
    private fun sectionOf(root: AccessibilityNodeInfo, header: String): AccessibilityNodeInfo? =
        findAll(root) { it.text?.toString()?.removeSuffix("*") == header }.firstOrNull()?.parent

    private fun findAll(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (match(n)) out += n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return out
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(4) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
        return null
    }

    private fun collectEditables(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 40) return
        if (node.isEditable) out += node
        for (i in 0 until node.childCount) collectEditables(node.getChild(i), out, depth + 1)
    }

    private fun isEmpty(node: AccessibilityNodeInfo): Boolean {
        if (node.isShowingHintText) return true
        val text = node.text?.toString().orEmpty()
        return text.isBlank() || text == node.hintText?.toString()
    }

    /** Ustala, które to pole, po podpowiedzi, opisie, identyfikatorze i tekstach obok pola. */
    private fun classify(node: AccessibilityNodeInfo): Field? {
        val label = buildString {
            node.hintText?.let { append(it).append(' ') }
            if (node.isShowingHintText) node.text?.let { append(it).append(' ') }
            node.contentDescription?.let { append(it).append(' ') }
            node.viewIdResourceName?.let { append(it.substringAfter('/')).append(' ') }
            node.labeledBy?.text?.let { append(it).append(' ') }
            // Najbliższy tekst przed polem (np. etykieta „Tytuł ogłoszenia*” nad polem).
            // Tylko jeden – inaczej pole „Opis” złapałoby też etykietę „Tytuł” z góry formularza.
            val parent = node.parent
            if (parent != null) {
                var nearest: CharSequence? = null
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i) ?: continue
                    if (child == node) break
                    if (child.isEditable) nearest = null
                    else if (!child.text.isNullOrBlank()) nearest = child.text
                }
                nearest?.let { append(it).append(' ') }
                parent.contentDescription?.let { append(it) }
            }
        }.lowercase()

        return when {
            TITLE_WORDS.any { it in label } -> Field.TITLE
            DESCRIPTION_WORDS.any { it in label } -> Field.DESCRIPTION
            PRICE_WORDS.any { it in label } -> Field.PRICE
            else -> null
        }
    }

    private fun valueFor(field: Field, l: Listing): String = when (field) {
        Field.TITLE -> l.title
        Field.DESCRIPTION -> l.description
        Field.PRICE -> l.priceDigits()
    }

    private fun labelFor(field: Field): String = when (field) {
        Field.TITLE -> "tytuł"
        Field.DESCRIPTION -> "opis"
        Field.PRICE -> "cenę"
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------- Pasek nad OLX ----------

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        row.addView(chip("Tytuł", primary = true) { insertOrCopy("Tytuł") { it.title } })
        row.addView(chip("Opis") { insertOrCopy("Opis") { it.description } })
        row.addView(chip("Cena") { insertOrCopy("Cena") { it.priceDigits() } })
        row.addView(chip("Kategoria") { showValue("Kategoria") { it.category } })
        row.addView(chip("Stan") { showValue("Stan") { it.condition } })
        row.addView(chip("Parametry") { insertOrCopy("Parametry") { it.paramsText() } })
        row.addView(chip("Przesuń") { moveOverlay() })
        row.addView(chip("Zamknij") {
            overlayDismissed = true
            hideOverlay()
        })

        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(0xF215191E.toInt())
                cornerRadius = dp(18).toFloat()
            }
            addView(row)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (overlayAtTop) Gravity.TOP else Gravity.BOTTOM
            y = dp(if (overlayAtTop) 36 else 96)
            horizontalMargin = 0.02f
        }

        runCatching { wm.addView(bar, params) }.onSuccess { overlay = bar }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
    }

    private fun moveOverlay() {
        overlayAtTop = !overlayAtTop
        hideOverlay()
        showOverlay()
    }

    private fun chip(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) 0xFF15191E.toInt() else 0xFFFFFFFF.toInt())
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(if (primary) 0xFFC8F169.toInt() else 0xFF2A3038.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44),
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    /** Wstawia wartość do pola, w którym jest kursor; jeśli takiego nie ma – kopiuje do schowka. */
    private fun insertOrCopy(label: String, pick: (Listing) -> String) {
        val listing = ListingStore.load(this) ?: return toast("Brak przygotowanego ogłoszenia")
        val value = pick(listing)
        if (value.isBlank()) return toast("$label: brak danych")

        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && setText(focused, value)) {
            toast("Wstawiono: $label")
        } else {
            copy(label, value)
            toast("Skopiowano: $label – przytrzymaj pole w OLX i wybierz „Wklej”")
        }
    }

    /** Kategoria i stan to w OLX listy wyboru – pokazujemy wartość i kopiujemy ją do wyszukiwarki kategorii. */
    private fun showValue(label: String, pick: (Listing) -> String) {
        val listing = ListingStore.load(this) ?: return toast("Brak przygotowanego ogłoszenia")
        val value = pick(listing)
        if (value.isBlank()) return toast("$label: brak danych")
        copy(label, value)
        Toast.makeText(this, "$label: $value", Toast.LENGTH_LONG).show()
    }

    private fun copy(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
