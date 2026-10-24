package pl.wystaw.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.wystaw.app.MainViewModel
import pl.wystaw.app.data.AiProvider
import pl.wystaw.app.data.AppSettings
import pl.wystaw.app.data.Listing
import pl.wystaw.app.data.Param

// ---------- 4. Gotowe ogłoszenie ----------

@Composable
fun ReviewScreen(vm: MainViewModel) {
    val l = vm.listing
    fun update(block: (Listing) -> Listing) = vm.updateListing(block(vm.listing))

    Column(Modifier.fillMaxSize()) {
        TopBar("Ogłoszenie gotowe", onBack = { vm.back() }) {
            if (vm.photos.isNotEmpty()) {
                TextButton(onClick = { vm.analyze() }) { Text("Od nowa", color = Ink) }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (vm.photos.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    vm.photos.forEach { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = l.title,
                onValueChange = { v -> update { it.copy(title = v.take(Listing.TITLE_MAX)) } },
                label = { Text("Tytuł") },
                supportingText = { Text("${l.title.length} / ${Listing.TITLE_MAX}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = l.category,
                onValueChange = { v -> update { it.copy(category = v) } },
                label = { Text("Kategoria (wybierzesz ją w OLX)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = l.price,
                    onValueChange = { v -> update { it.copy(price = v.filter { c -> c.isDigit() }) } },
                    label = { Text("Cena") },
                    suffix = { Text("zł") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (l.priceMin != null && l.priceMax != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Ink)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Szacunek AI dla rynku wtórnego", color = Color(0xFFAEB5BE), fontSize = 12.sp)
                        Text("${l.priceMin}–${l.priceMax} zł", color = Lime, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text("To orientacyjna wycena – porównaj z podobnymi ofertami w OLX.", color = Color(0xFFAEB5BE), fontSize = 12.sp)
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Stan", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Listing.CONDITIONS.forEach { c ->
                        FilterChip(
                            selected = l.condition == c,
                            onClick = { update { it.copy(condition = c) } },
                            label = { Text(c.replaceFirstChar { ch -> ch.uppercase() }) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Ink,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = l.description,
                onValueChange = { v -> update { it.copy(description = v) } },
                label = { Text("Opis") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            if (l.params.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Parametry", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    l.params.forEachIndexed { index, p ->
                        OutlinedTextField(
                            value = p.value,
                            onValueChange = { v ->
                                update { cur ->
                                    cur.copy(params = cur.params.mapIndexed { i, old -> if (i == index) Param(old.name, v) else old })
                                }
                            },
                            label = { Text(p.name) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (l.notes.isNotEmpty()) {
                InfoCard(AmberBg) {
                    Text("Sprawdź przed publikacją", fontWeight = FontWeight.Bold, color = AmberText, fontSize = 14.sp)
                    l.notes.forEach { Text("• $it", color = AmberText, fontSize = 13.sp, lineHeight = 19.sp) }
                }
            }
            vm.error?.let { Text(it, color = ErrorRed, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
        }
        BottomBar {
            PrimaryButton(
                "Przenieś do OLX  →",
                onClick = { vm.goToHandoff() },
                enabled = l.title.isNotBlank() && l.description.isNotBlank(),
            )
        }
    }
}

// ---------- 0. Wymagana usługa ułatwień dostępu ----------

/**
 * [stopped] = usługa jest włączona w ustawieniach, ale nie działa – system zabił proces (np. zamknięcie Wystaw
 * z listy ostatnich aplikacji na Xiaomi) i nie podłączył usługi ponownie. Pomaga wyłączenie i włączenie jej.
 */
@Composable
fun ServiceRequiredScreen(stopped: Boolean) {
    val context = LocalContext.current
    val openAppInfo = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }
    val openAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (stopped) "Usługa Wystaw nie działa" else "Włącz usługę Wystaw", onBack = null)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (stopped) {
                Text(
                    "Usługa jest włączona, ale system ją zatrzymał – zwykle po zamknięciu Wystaw z listy ostatnich " +
                        "aplikacji. Bez niej formularz OLX zostanie pusty.",
                    color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
                )
                InfoCard(AmberBg) {
                    Text("1. Wyłącz i włącz usługę", fontWeight = FontWeight.Bold, color = AmberText)
                    Text(
                        "Ułatwienia dostępu → Pobrane aplikacje → Wystaw → wyłącz, a potem włącz ponownie.",
                        color = AmberText, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    TextButton(onClick = openAccessibility) {
                        Text("Ułatwienia dostępu", color = AmberText, fontWeight = FontWeight.Bold)
                    }
                }
                KeepAliveCard(number = 2, openAppInfo = openAppInfo)
                Spacer(Modifier.height(8.dp))
                return@Column
            }
            Text(
                "Wystaw wpisuje tytuł, opis i cenę w formularz OLX przez usługę ułatwień dostępu. " +
                    "Bez niej aplikacja nie zadziała – włącz ją raz, a ten ekran zniknie.",
                color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
            )
            InfoCard(AmberBg) {
                Text("1. Zezwól na ustawienia z ograniczonym dostępem", fontWeight = FontWeight.Bold, color = AmberText)
                Text(
                    "Informacje o aplikacji → menu ⋮ w prawym górnym rogu → „Zezwól na ustawienia z ograniczonym dostępem”. " +
                        "Jeśli tej opcji nie ma, najpierw spróbuj włączyć usługę (krok 2) i wróć tutaj.",
                    color = AmberText, fontSize = 14.sp, lineHeight = 20.sp,
                )
                TextButton(onClick = openAppInfo) {
                    Text("Informacje o aplikacji", color = AmberText, fontWeight = FontWeight.Bold)
                }
            }
            InfoCard(AmberBg) {
                Text("2. Włącz usługę", fontWeight = FontWeight.Bold, color = AmberText)
                Text(
                    "Ułatwienia dostępu → Pobrane aplikacje (Zainstalowane aplikacje) → Wystaw → włącz. " +
                        "Na Xiaomi poczekaj, aż odliczanie w ostrzeżeniu się skończy, i zatwierdź.",
                    color = AmberText, fontSize = 14.sp, lineHeight = 20.sp,
                )
                TextButton(onClick = openAccessibility) {
                    Text("Ułatwienia dostępu", color = AmberText, fontWeight = FontWeight.Bold)
                }
            }
            KeepAliveCard(number = 3, openAppInfo = openAppInfo)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Jak nie dać MIUI/HyperOS zabić Wystaw (a z nim usługi). */
@Composable
private fun KeepAliveCard(number: Int, openAppInfo: () -> Unit) {
    InfoCard(Color.White) {
        Text("$number. Żeby system jej nie zatrzymywał", fontWeight = FontWeight.Bold)
        Text(
            "• Nie zamykaj Wystaw przesunięciem z listy ostatnich aplikacji – albo przytrzymaj tam kafelek Wystaw " +
                "i stuknij kłódkę, żeby go zablokować.\n" +
                "• Informacje o aplikacji → Oszczędzanie baterii → Bez ograniczeń, i włącz Autostart.",
            color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
        )
        TextButton(onClick = openAppInfo) { Text("Informacje o aplikacji", color = Ink, fontWeight = FontWeight.Bold) }
    }
}

// ---------- 5. Przekazanie do OLX ----------

@Composable
fun HandoffScreen(vm: MainViewModel) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopBar("Przenieś do OLX", onBack = { vm.back() })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (vm.serviceWorking) {
                InfoCard(GreenBg) {
                    Text("Usługa wypełniania włączona", fontWeight = FontWeight.Bold, color = GreenText)
                    Text("Wystaw sam wpisze tytuł, opis i cenę, gdy otworzysz formularz w OLX.", color = GreenText, fontSize = 14.sp)
                }
            } else {
                InfoCard(AmberBg) {
                    Text("Włącz usługę „Wystaw – wypełnianie OLX”", fontWeight = FontWeight.Bold, color = AmberText)
                    Text(
                        "Ustawienia → Ułatwienia dostępu → Zainstalowane aplikacje → Wystaw → włącz.",
                        color = AmberText, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    Text(
                        "Jeśli przełącznik jest szary (aplikacja z pliku APK): otwórz Informacje o aplikacji → menu ⋮ w prawym górnym rogu → „Zezwól na ustawienia z ograniczonym dostępem”, potem wróć tutaj.",
                        color = AmberText, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("Ułatwienia dostępu", color = AmberText, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        }) { Text("Info o aplikacji", color = AmberText, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            InfoCard(Color.White) {
                Text("Zdjęcia", fontWeight = FontWeight.Bold)
                val saved = vm.savedPhotosCount
                Text(
                    when {
                        vm.photos.isEmpty() -> "Wybierzesz je w OLX z galerii telefonu."
                        saved == null -> "Zapisuję zdjęcia w albumie „Wystaw”…"
                        else -> "Zapisano $saved zdj. w albumie „Wystaw” – w galerii OLX są na samej górze, okładka pierwsza."
                    },
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }

            InfoCard(Color.White) {
                Text("Co dalej", fontWeight = FontWeight.Bold)
                listOf(
                    "1. Stuknij „Otwórz OLX” – otworzy się od razu formularz „Dodaj ogłoszenie”.",
                    "2. Tytuł i opis wpiszą się same.",
                    "3. Kategorię, stan i parametry wybierz z list – podpowiedzi masz na pasku Wystaw u góry ekranu. Pole ceny OLX pokazuje dopiero po wybraniu kategorii – cena wpisze się wtedy sama.",
                    "4. Dodaj zdjęcia z albumu „Wystaw” i opublikuj.",
                ).forEach { Text(it, color = Muted, fontSize = 14.sp, lineHeight = 20.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }
        BottomBar {
            PrimaryButton("Otwórz OLX", onClick = { context.startActivity(vm.olxIntent()) })
            TextButton(onClick = { vm.startNew() }, modifier = Modifier.fillMaxWidth()) {
                Text("Zakończ i zacznij nowe ogłoszenie", color = Ink)
            }
        }
    }
}

// ---------- Ustawienia ----------

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var provider by remember { mutableStateOf(AppSettings.provider(context)) }
    var apiKey by remember { mutableStateOf(AppSettings.apiKey(context, provider)) }
    var model by remember { mutableStateOf(AppSettings.model(context, provider)) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Ustawienia", onBack = { vm.back() })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Model AI", fontWeight = FontWeight.Bold)
            AiProvider.entries.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(1.dp, if (p == provider) Ink else Line, RoundedCornerShape(14.dp))
                        .clickable {
                            provider = p
                            apiKey = AppSettings.apiKey(context, p)
                            model = AppSettings.model(context, p)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = p == provider, onClick = null)
                    Text(p.label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Klucz API") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { uriHandler.openUri(provider.keyUrl) }) {
                Text("Skąd wziąć klucz? Otwórz ${provider.keyUrl.removePrefix("https://")}", color = GreenText)
            }

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Nazwa modelu") },
                supportingText = { Text("Domyślnie ${provider.defaultModel}. Zmień, jeśli dostawca wyda nowszy model.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            InfoCard(Color.White) {
                Text("Prywatność", fontWeight = FontWeight.Bold)
                Text(
                    "Zdjęcia są wysyłane tylko do wybranego dostawcy AI w celu przygotowania ogłoszenia. Klucz jest zapisany wyłącznie na tym telefonie.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }
        BottomBar {
            PrimaryButton("Zapisz", onClick = {
                AppSettings.save(context, provider, apiKey, model)
                vm.onSettingsSaved()
            })
        }
    }
}
