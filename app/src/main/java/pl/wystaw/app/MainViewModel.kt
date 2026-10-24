package pl.wystaw.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.wystaw.app.ai.AiException
import pl.wystaw.app.ai.ListingGenerator
import pl.wystaw.app.data.AppSettings
import pl.wystaw.app.data.Listing
import pl.wystaw.app.data.ListingStore
import pl.wystaw.app.data.PhotoSaver
import pl.wystaw.app.service.OlxFillService
import java.io.File

enum class Screen { HOME, PHOTOS, ANALYZING, REVIEW, HANDOFF, SETTINGS }

/** Formularz dodawania ogłoszenia – w aplikacji OLX (pl.tablica 5.183) otwiera PostAdActivity. */
private const val OLX_POST_AD_URL = "https://www.olx.pl/d/nowe-ogloszenie/"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_PHOTOS = 8
    }

    private val ctx: Context get() = getApplication()

    var screen by mutableStateOf(Screen.HOME)
        private set
    private var screenBeforeSettings = Screen.HOME

    val photos = mutableStateListOf<Uri>()
    var sellerHint by mutableStateOf("")
    var listing by mutableStateOf(ListingStore.load(app) ?: Listing())
        private set
    var hasListing by mutableStateOf(ListingStore.load(app) != null)
        private set
    var error by mutableStateOf<String?>(null)
    var serviceEnabled by mutableStateOf(false)
        private set
    var hasApiKey by mutableStateOf(AppSettings.current(app).apiKey.isNotBlank())
        private set
    var savedPhotosCount by mutableStateOf<Int?>(null)
        private set

    private var analyzeJob: Job? = null
    private var savedForPhotos: List<Uri> = emptyList()

    /** Uri dla zdjęcia z aparatu – tworzony przed uruchomieniem aparatu. */
    var pendingCameraUri: Uri? = null
        private set

    init {
        // Od razu, żeby ekran „Włącz usługę” nie mignął przy starcie, gdy usługa już działa.
        refreshServiceState()
    }

    fun go(target: Screen) {
        if (target == Screen.SETTINGS && screen != Screen.SETTINGS) screenBeforeSettings = screen
        error = null
        screen = target
    }

    /** Obsługa przycisku Wstecz. Zwraca false, gdy aplikacja ma się zamknąć. */
    fun back(): Boolean {
        when (screen) {
            Screen.HOME -> return false
            Screen.PHOTOS -> go(Screen.HOME)
            Screen.ANALYZING -> cancelAnalysis()
            Screen.REVIEW -> go(Screen.PHOTOS)
            Screen.HANDOFF -> go(Screen.REVIEW)
            Screen.SETTINGS -> go(screenBeforeSettings)
        }
        return true
    }

    // ---------- Zdjęcia ----------

    fun addPhotos(uris: List<Uri>) {
        val fresh = uris.filter { it !in photos }.take(MAX_PHOTOS - photos.size)
        photos.addAll(fresh)
        if (photos.isNotEmpty() && screen == Screen.HOME) go(Screen.PHOTOS)
    }

    fun removePhoto(uri: Uri) {
        photos.remove(uri)
        if (photos.isEmpty()) go(Screen.HOME)
    }

    fun makeCover(uri: Uri) {
        if (photos.remove(uri)) photos.add(0, uri)
    }

    fun newCameraUri(): Uri {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "aparat_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        pendingCameraUri = uri
        return uri
    }

    fun onCameraResult(success: Boolean) {
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) addPhotos(listOf(uri))
    }

    fun startNew() {
        photos.clear()
        sellerHint = ""
        savedPhotosCount = null
        savedForPhotos = emptyList()
        ListingStore.disarm(ctx)
        go(Screen.HOME)
    }

    // ---------- AI ----------

    fun analyze() {
        val configs = AppSettings.fallbackChain(ctx)
        if (AppSettings.current(ctx).apiKey.isBlank()) {
            error = "Najpierw wpisz klucz API w Ustawieniach."
            return
        }
        go(Screen.ANALYZING)
        val photosSnapshot = photos.toList()
        val hint = sellerHint
        analyzeJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ListingGenerator.generate(ctx, configs, photosSnapshot, hint)
                }
                updateListing(result)
                // Nowe ogłoszenie – zdjęcia trafią do albumu dopiero przy przejściu do OLX.
                ListingStore.setPhotoCount(ctx, 0)
                go(Screen.REVIEW)
            } catch (e: AiException) {
                go(Screen.PHOTOS)
                error = e.message
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                go(Screen.PHOTOS)
                error = "Nieoczekiwany błąd: ${e.message}"
            }
        }
    }

    private fun cancelAnalysis() {
        analyzeJob?.cancel()
        go(Screen.PHOTOS)
    }

    fun updateListing(l: Listing) {
        listing = l
        hasListing = true
        ListingStore.save(ctx, l)
    }

    // ---------- Przekazanie do OLX ----------

    fun goToHandoff() {
        go(Screen.HANDOFF)
        refreshServiceState()
        if (photos.isNotEmpty() && photos.toList() != savedForPhotos) {
            val snapshot = photos.toList()
            viewModelScope.launch {
                val count = withContext(Dispatchers.IO) { PhotoSaver.saveToAlbum(ctx, snapshot) }
                savedForPhotos = snapshot
                savedPhotosCount = count
                ListingStore.setPhotoCount(ctx, count)
            }
        }
    }

    fun refreshServiceState() {
        val expected = ComponentName(ctx, OlxFillService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == expected }
        serviceEnabled = enabled
        hasApiKey = AppSettings.current(ctx).apiKey.isNotBlank()
    }

    /**
     * Uzbraja usługę i zwraca intencję otwierającą od razu formularz „Dodaj ogłoszenie” w aplikacji OLX
     * (deep link obsługuje DeepLinkingActivity → PostAdActivity), a bez aplikacji – ten sam adres w przeglądarce.
     */
    fun olxIntent(): Intent {
        ListingStore.save(ctx, listing)
        ListingStore.arm(ctx)
        val pm = ctx.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(OLX_POST_AD_URL))
        val app = OlxFillService.OLX_PACKAGES
            .map { Intent(view).setPackage(it) }
            .firstOrNull { it.resolveActivity(pm) != null }
        // CLEAR_TASK: bez tego OLX wraca do otwartego wcześniej formularza ze starymi danymi zamiast otworzyć nowy.
        return if (app != null) app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        else view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun onSettingsSaved() {
        hasApiKey = AppSettings.current(ctx).apiKey.isNotBlank()
        go(screenBeforeSettings)
    }
}
