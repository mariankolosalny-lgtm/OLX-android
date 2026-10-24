package pl.wystaw.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import pl.wystaw.app.MainViewModel
import pl.wystaw.app.Screen

@Composable
fun WystawApp(vm: MainViewModel, onExit: () -> Unit) {
    BackHandler { if (!vm.serviceWorking || !vm.back()) onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (vm.screen == Screen.ANALYZING && vm.serviceWorking) Ink else Ground)
            .safeDrawingPadding()
    ) {
        // Bez działającej usługi ułatwień dostępu Wystaw nie wypełni OLX – dopóki nie działa, pokazujemy tylko instrukcję.
        if (!vm.serviceWorking) {
            ServiceRequiredScreen(stopped = vm.serviceEnabled)
            return@Box
        }
        when (vm.screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.PHOTOS -> PhotosScreen(vm)
            Screen.ANALYZING -> AnalyzingScreen(vm)
            Screen.REVIEW -> ReviewScreen(vm)
            Screen.HANDOFF -> HandoffScreen(vm)
            Screen.SETTINGS -> SettingsScreen(vm)
        }
    }
}

// ---------- Wspólne elementy ----------

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, action: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Wstecz" }) {
                Text("‹", fontSize = 30.sp, color = Ink)
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoCard(
    background: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun BottomBar(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ground)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

// ---------- 1. Start ----------

@Composable
private fun HomeScreen(vm: MainViewModel) {
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_PHOTOS)
    ) { uris -> vm.addPhotos(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        vm.onCameraResult(ok)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ink),
                contentAlignment = Alignment.Center,
            ) { Text("↑", color = Lime, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
            Spacer(Modifier.width(10.dp))
            Text("Wystaw", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.go(Screen.SETTINGS) }) { Text("Ustawienia", color = Ink) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Zrób zdjęcia.\nOgłoszenie napiszemy my.",
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "AI rozpozna przedmiot, napisze tytuł i opis, dobierze kategorię i podpowie cenę — a potem wpisze wszystko w formularz OLX.",
                fontSize = 15.sp, lineHeight = 22.sp, color = Muted,
            )
        }

        if (!vm.hasApiKey) {
            InfoCard(AmberBg) {
                Text("Brak klucza API", fontWeight = FontWeight.Bold, color = AmberText)
                Text("Aplikacja potrzebuje klucza do modelu AI. Wpisz go raz w Ustawieniach.", color = AmberText, fontSize = 14.sp)
                TextButton(onClick = { vm.go(Screen.SETTINGS) }) { Text("Przejdź do Ustawień", color = AmberText, fontWeight = FontWeight.Bold) }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Ink)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { camera.launch(vm.newCameraUri()) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                ) { Text("Aparat", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = InkSoft, contentColor = Color.White),
                ) { Text("Galeria", fontWeight = FontWeight.Bold) }
            }
            Text(
                "Możesz też udostępnić zdjęcia z dowolnej aplikacji → „Wystaw”.",
                color = Color(0xFFAEB5BE), fontSize = 12.sp,
            )
        }

        if (vm.photos.isNotEmpty()) {
            OutlinedButton(onClick = { vm.go(Screen.PHOTOS) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Wróć do wybranych zdjęć (${vm.photos.size})", color = Ink)
            }
        }

        if (vm.hasListing && vm.listing.title.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ostatnie ogłoszenie", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Line, RoundedCornerShape(16.dp))
                        .clickable { vm.go(Screen.REVIEW) }
                        .padding(14.dp),
                ) {
                    Text(vm.listing.title, fontWeight = FontWeight.Bold)
                    val price = vm.listing.priceDigits()
                    Text(
                        if (price.isNotEmpty()) "$price zł · stuknij, by edytować" else "Stuknij, by edytować",
                        color = Muted, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

// ---------- 2. Zdjęcia ----------

@Composable
private fun PhotosScreen(vm: MainViewModel) {
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_PHOTOS)
    ) { uris -> vm.addPhotos(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        vm.onCameraResult(ok)
    }
    var addMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Twoje zdjęcia", onBack = { vm.back() }) {
            Text("${vm.photos.size} / ${MainViewModel.MAX_PHOTOS}", color = Muted, modifier = Modifier.padding(end = 16.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.photos.firstOrNull()?.let { cover ->
                Box {
                    AsyncImage(
                        model = cover,
                        contentDescription = "Okładka ogłoszenia",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(20.dp)),
                    )
                    Text(
                        "Okładka",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Ink)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            val tiles = vm.photos.toList() + listOf(null)
            tiles.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { uri ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            if (uri != null) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Zdjęcie – stuknij, by ustawić jako okładkę",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { vm.makeCover(uri) },
                                )
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(36.dp)
                                        .clickable { vm.removePhoto(uri) }
                                        .semantics { contentDescription = "Usuń zdjęcie" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(Ink),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("×", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                                }
                            } else if (vm.photos.size < MainViewModel.MAX_PHOTOS) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.5.dp, Color(0xFFA7ACB3), RoundedCornerShape(12.dp))
                                        .clickable { addMenuOpen = true }
                                        .semantics { contentDescription = "Dodaj zdjęcie" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", fontSize = 26.sp, color = Muted)
                                    // Kolejne zdjęcie: z aparatu albo z galerii.
                                    DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Zrób zdjęcie") },
                                            onClick = {
                                                addMenuOpen = false
                                                camera.launch(vm.newCameraUri())
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Wybierz z galerii") },
                                            onClick = {
                                                addMenuOpen = false
                                                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text("Stuknij zdjęcie, by ustawić je jako okładkę.", fontSize = 12.sp, color = Muted)

            InfoCard(AmberBg) {
                Text("Wskazówka", fontWeight = FontWeight.Bold, color = AmberText, fontSize = 14.sp)
                Text(
                    "Dodaj zdjęcie metki lub tabliczki znamionowej – AI odczyta model i rozmiar. Pokaż też uczciwie usterki.",
                    color = AmberText, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }

            OutlinedTextField(
                value = vm.sellerHint,
                onValueChange = { vm.sellerHint = it },
                label = { Text("Coś dodać od siebie? (opcjonalnie)") },
                placeholder = { Text("np. kupiony w 2023, mało używany, odbiór Kraków") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            vm.error?.let { Text(it, color = ErrorRed, fontSize = 14.sp) }
            Spacer(Modifier.height(8.dp))
        }
        BottomBar {
            PrimaryButton("Stwórz ogłoszenie", onClick = { vm.analyze() }, enabled = vm.photos.isNotEmpty())
        }
    }
}

// ---------- 3. Analiza ----------

private val ANALYSIS_STEPS = listOf(
    "Rozpoznaję przedmiot…",
    "Odczytuję metki i napisy…",
    "Oceniam stan i widoczne usterki…",
    "Szacuję cenę…",
    "Piszę tytuł, opis i parametry…",
)

@Composable
private fun AnalyzingScreen(vm: MainViewModel) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            if (step < ANALYSIS_STEPS.lastIndex) step++
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Lime, trackColor = InkSoft, strokeWidth = 5.dp, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(32.dp))
        Text("Tworzę ogłoszenie", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(ANALYSIS_STEPS[step], color = Color(0xFFAEB5BE), fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("Zwykle trwa to 10–30 sekund.", color = Color(0xFF8A929C), fontSize = 13.sp)
        Spacer(Modifier.height(48.dp))
        OutlinedButton(onClick = { vm.back() }) { Text("Anuluj", color = Color.White) }
    }
}
