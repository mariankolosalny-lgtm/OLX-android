# Wystaw – kontekst projektu

Aplikacja na Androida: użytkownik wybiera zdjęcia → AI (Gemini domyślnie, Claude opcjonalnie) tworzy ogłoszenie
(tytuł, kategoria, opis, stan, cena, parametry) → aplikacja otwiera OLX, a usługa ułatwień dostępu wpisuje
tytuł/opis/cenę w formularz „Dodaj ogłoszenie”. Rozmawiamy po polsku.

## Ustalenia (nie zmieniać bez pytania)
- Tylko Android, dystrybucja jako APK (poza Google Play – Play nie przepuści AccessibilityService do tego celu).
- Bez API OLX. Automat = `service/OlxFillService.kt` (heurystyka po etykietach „Tytuł/Opis/Cena”, tylko puste pola),
  plan B = pasek-nakładka (TYPE_ACCESSIBILITY_OVERLAY) z kopiowaniem/wstawianiem pól.
- Pakiet OLX: `pl.tablica` (OLX_PACKAGES + `<queries>` w manifeście).
- „Otwórz OLX” = deep link `https://www.olx.pl/d/nowe-ogloszenie/` z setPackage → od razu formularz „Dodaj ogłoszenie”
  (PostAdActivity nie jest eksportowana, więc tylko przez link).
- Kotlin + Jetpack Compose, minSdk 29, compileSdk/targetSdk 35, AGP 8.9.1, Kotlin 2.1.20, Gradle 8.14.3 (wrapper).
- Zdjęcia kopiowane do `Pictures/Wystaw` (okładka jako najnowsza).
- Prototyp UI: artefakt Design „Fotka→OLX – projekt aplikacji” na claude.ai.

## Stan (2026-10-24)
- v0.1 skompilowana bez poprawek, zainstalowana i uruchomiona na telefonie – ekran startowy działa, bez crasha.
- Formularz OLX 5.183.2 (zrzut uiautomator): w kontenerze `titleView`/`descriptionView` jest TextView „Tytuł ogłoszenia*”/„Opis*”,
  a za nim EditText bez hintu i ID – heurystyka classify() pasuje. Pola ceny nie ma, dopóki nie wybierze się kategorii.
  Niżej są wypełnione pola kontaktu (Osoba kontaktowa, E-mail, Telefon) – nie ruszać.
- Pierwszy test wypełniania nie zadziałał, bo usługa ułatwień dostępu nie była włączona (enabled_accessibility_services = null).
  Dlatego aplikacja wymaga usługi przy starcie: dopóki jest wyłączona, WystawApp pokazuje tylko ServiceRequiredScreen
  (instrukcja + przyciski do Informacji o aplikacji i Ułatwień dostępu); stan odświeżany w onResume.
  Usługa jest już włączona i przetrwała `adb install -r`.
- Zapasowe modele AI: `AppSettings.fallbackChain()` – wybrany model → `AiProvider.fallbackModels` → drugi dostawca
  (jeśli ma klucz). `ListingGenerator.generate()` przechodzi dalej tylko przy błędach `retryable`
  (404/408/429/5xx, sieć, zły JSON); 400/401/403 zatrzymują od razu. Powód: Gemini 2.5 Flash zwracał 503 (przeciążenie).
- Test 2026-10-24: tytuł i cena wpisały się same; opis niepotwierdzony (uiautomator nie zrzuca pól poza ekranem).
- Domyślne wybory w OLX (OlxFillService, raz na ogłoszenie): Stan = „Używane” (Nowe tylko gdy listing.condition = nowy);
  Przesyłka OLX: rozwija rozmiar „M” w sekcji „Metoda dostawy” i zaznacza CheckBox „InPost Paczkomat® 24/7”.
  Sekcje szukane po nagłówkach „Stan*”/„Metoda dostawy” (rodzic nagłówka). OLX sam zaznacza też „InPost Kurier” – nie ruszamy.
  Sekcja dostawy pojawia się dopiero po wybraniu kategorii.
- Okno OLX „Masz niedokończone ogłoszenie” – usługa zawsze klika „Nie, zaczynam od nowa” (decyzja użytkownika)
  i zeruje swój stan (pod oknem leży stary szkic). Intencja „Otwórz OLX” ma FLAG_ACTIVITY_CLEAR_TASK – inaczej OLX
  wracał do otwartego starego formularza.
- Test end-to-end 2026-10-24 17:26 – OK: szkic odrzucony, tytuł, opis, cena, Stan „Używane”, M rozwinięte + Paczkomat
  zaznaczony; OLX sam zaproponował kategorię z tytułu. Zdjęcia nadal ręcznie.
- Zdjęcia (prośba użytkownika): usługa klika „Dodaj zdjęcia” → „Wybierz zdjęcie” → w systemowym wyborze
  (com.google.android.photopicker, teksty PL: „Siatka multimediów”, „Wybrano…”, „Gotowe”) zaznacza N pierwszych
  zdjęć. N = `ListingStore.photoCount` (ustawiane po zapisie do albumu w goToHandoff, zerowane po nowej analizie).
- Auto-publikacja (prośba użytkownika „klikaj dodaj ogłoszenie”): `tryPublish()` klika przycisk `postAdBtn` tylko gdy
  tytuł+opis+cena+stan+Paczkomat gotowe i zdjęcia dodane ≥ 6 s wcześniej; bez zdjęć nie publikuje. Po kliknięciu disarm.
  Test 2026-10-24 17:48 (za zgodą użytkownika): zdjęcie dodane w <5 s, „Dodaj ogłoszenie” kliknięte ~12 s po
  „Otwórz OLX”, OLX przeszedł do PromoteAdActivity, potem „Twoje ogłoszenie jest już aktywne”. 17:51 ogłoszenia
  nie było jeszcze w „Aktywne” (wyszukiwanie „Gabi” – brak; pewnie moderacja/opóźnienie). Nie sprawdzono, czy w
  opublikowanym ogłoszeniu są Stan i Paczkomat M (na zrzucie z 5 s sekcja Stan jeszcze się ładowała).
- „Promuj ogłoszenie”: usługa zawsze klika „Nie promuj” (decyzja użytkownika) – po ID
  `monetization_vas_dont_promote_cta`, przez 2 min po swojej publikacji. Płatnego „Dodaj promowanie” nigdy nie klikać.
  Jeszcze nieprzetestowane (w teście 17:50 kliknięte ręcznie przez adb).
- 2026-10-24 18:07: po zamknięciu Wystaw z listy ostatnich aplikacji (MIUI „SwipeUpClean”) proces zginął, a usługa
  została w „Crashed services” – włączona w ustawieniach, ale niepodłączona, więc OLX zostawał pusty. Teraz
  `OlxFillService.running` (ustawiane w onServiceConnected/onDestroy) + `vm.serviceWorking`; gdy włączona, ale nie
  działa – ServiceRequiredScreen(stopped = true): „wyłącz i włącz ponownie” + jak zablokować Wystaw w ostatnich
  aplikacjach i ustawić baterię „Bez ograniczeń”/Autostart. `adb install -r` też podłącza zawieszoną usługę ponownie.
  UWAGA: `am force-stop pl.wystaw.app` WYŁĄCZA usługę w ustawieniach (użytkownik musi ją włączyć ręcznie) – nie używać.
  18:55/18:58 znów zabite (SwipeUpClean, potem OneKeyClean = „wyczyść wszystko”). 19:03: usługa przywrócona przez
  `adb install -r`, Wystaw zablokowany kłódką w ostatnich aplikacjach, Zasilanie = „Bez ograniczeń”, „Wstrzymaj
  działanie aplikacji…” wyłączone.
- 19:07 test: przesunięcie karty Wystaw W GÓRĘ (SwipeUpClean) zabija WSZYSTKIE procesy pakietu, także
  `:fill` w osobnym procesie i mimo kłódki (kłódka chroni tylko przed „wyczyść wszystko”). Osobny proces wycofany.
  Wykrywanie działania: `AccessibilityManager.getEnabledAccessibilityServiceList` (tylko podłączone usługi) w
  refreshServiceState → `vm.serviceWorking`. Jedyne realne obejście: ukryć Wystaw z listy ostatnich aplikacji
  (excludeFromRecents) – zrobione 19:13 na prośbę użytkownika: MainActivity ma `android:excludeFromRecents="true"`.
  Karta widoczna tylko póki Wystaw jest na wierzchu; po przejściu do OLX znika z ostatnich aplikacji (sprawdzone).
  KeepAliveCard: Zasilanie „Bez ograniczeń”, wyłączone „Wstrzymaj działanie…”, powrót do Wystaw ikoną.
  Wariant „stopped” ekranu jeszcze nieprzetestowany na telefonie.
- Testy przez adb na tym Xiaomi: pierwszy `input tap` po `uiautomator dump` bywa gubiony – sprawdzać zrzutem ekranu.
- SDK bez Android Studio: cmdline-tools, platform-tools, platforms;android-35, build-tools;35.0.0
  w %LOCALAPPDATA%\Android\Sdk. Budowa z JAVA_HOME = C:\ORLEN\Java\jdk-21.0.12.1 (skrypt szuka JBR ze Studio,
  którego nie ma – JAVA_HOME trzeba ustawić ręcznie).
- Telefon: Xiaomi 2210129SG (HyperOS), kabel USB, debugowanie USB i „Instaluj przez USB” włączone
  (instalację z adb trzeba potwierdzić na telefonie). Na komputerze jest też Vysor (podgląd ekranu telefonu).

## Następne kroki
1. Przebudowa i instalacja: `.\uruchom-na-telefonie.ps1` (pisze local.properties, `gradlew installDebug` → build.log,
   potem logcat → logcat.txt).
2. Na telefonie: Ustawienia aplikacji → klucz API (Gemini: aistudio.google.com/apikey).
   Włączyć usługę w Ułatwieniach dostępu (Android 13+: najpierw „Zezwól na ustawienia z ograniczonym dostępem”).
3. Test w OLX: sprawdzić `adb shell uiautomator dump` na ekranie „Dodaj ogłoszenie”, dopasować heurystykę
   w `OlxFillService.classify()` do prawdziwych etykiet/ID pól. Podczas testów przez adb NIE klikać „Dodaj ogłoszenie”
   i nie uruchamiać testu, który opublikuje ogłoszenie, bez zgody użytkownika na ten konkretny test.

## Przydatne polecenia
- Budowa: `.\gradlew.bat assembleDebug` / `installDebug`
- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- Zrzut drzewa widoków: `adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml`
