# Wystaw – zdjęcia → ogłoszenie OLX (Android)

Aplikacja robi ogłoszenie z wybranych zdjęć: AI rozpoznaje przedmiot, pisze tytuł, opis i parametry, dobiera kategorię i szacuje cenę. Potem otwiera aplikację OLX. Gdy pokaże się formularz dodawania ogłoszenia, usługa ułatwień dostępu sama wpisuje w niego tytuł, opis i cenę.

## Pobieranie

Najnowszy plik APK: **https://github.com/mariankolosalny-lgtm/OLX-android/releases/latest**
Otwórz link na telefonie, pobierz `Wystaw-vX.Y.Z.apk` i zainstaluj. Kolejne wersje instalują się na poprzednią
(ten sam klucz podpisu), bez utraty ustawień.

Wydanie ma wbudowany klucz Gemini, więc aplikacja działa od razu. W Ustawieniach można wpisać własny klucz –
wtedy ma on pierwszeństwo (własny limit).

**Nowe wydanie:** `git tag v0.2.1 && git push origin v0.2.1` – workflow „Wydanie APK” zbuduje i opublikuje plik.
Wymaga sekretów `WYSTAW_KEYSTORE_BASE64`, `WYSTAW_KEYSTORE_PASSWORD` i `WYSTAW_GEMINI_KEY`.

## Jak zbudować plik APK

**Sposób A – GitHub (bez instalowania niczego):**
1. Załóż prywatne repozytorium na GitHubie i wgraj do niego zawartość tego folderu.
2. Wejdź w zakładkę **Actions** → „Zbuduj APK”. Budowanie rusza samo po każdym wgraniu zmian.
3. Po kilku minutach pobierz `wystaw-apk` z sekcji **Artifacts** i rozpakuj plik `app-release.apk`.

**Sposób B – Android Studio:**
1. File → Open → wskaż ten folder i poczekaj na synchronizację Gradle.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. Plik będzie w `app/build/outputs/apk/`.

## Instalacja i pierwsze uruchomienie

1. Skopiuj APK na telefon i otwórz go. Zezwól na instalację z tego źródła.
2. W aplikacji wejdź w **Ustawienia** i wybierz model AI:
   - **Gemini** (domyślnie) ma darmowy limit. Klucz założysz na https://aistudio.google.com/apikey
   - **Claude** jest płatny za użycie. Klucz założysz na https://console.anthropic.com/settings/keys
3. Włącz usługę: Ustawienia telefonu → Ułatwienia dostępu → Wystaw → włącz.
   Na Androidzie 13 i nowszym przełącznik bywa szary, bo aplikacja jest zainstalowana z pliku. Wtedy wejdź w
   Informacje o aplikacji Wystaw → menu ⋮ → „Zezwól na ustawienia z ograniczonym dostępem” i spróbuj ponownie.

## Jak to działa

| Krok | Co się dzieje |
|---|---|
| Zdjęcia | Aparat, galeria albo „Udostępnij → Wystaw”. Do 8 zdjęć, pierwsze to okładka. |
| AI | Do modelu idzie do 6 zdjęć zmniejszonych do 1280 px. Odpowiedź wraca jako JSON: tytuł, kategoria, opis, stan, widełki ceny, parametry i uwagi. |
| Edycja | Wszystkie pola można poprawić. Zmiany zapisują się na bieżąco. |
| Album | Zdjęcia są kopiowane do `Pictures/Wystaw`, okładka jako najnowsza, więc w galerii OLX są na górze. |
| OLX | „Otwórz OLX” włącza wypełnianie na 2 godziny. Usługa szuka pól po etykietach „Tytuł”, „Opis” i „Cena” i wpisuje wartości tylko do pustych pól. |
| Pasek | Nad OLX pojawia się pasek: Tytuł, Opis, Cena, Kategoria, Stan, Parametry. Stuknięcie wstawia wartość do pola z kursorem albo kopiuje ją do schowka. |

## Ograniczenia

- Kategorię, stan i parametry w OLX wybiera się z list. Ich nie wypełniamy automatycznie. Na pasku masz tylko podpowiedź.
- Pola są rozpoznawane po tekstach na ekranie. Jeśli OLX zmieni formularz, automat może nie znaleźć pola. Wtedy zostaje pasek.
- Zdjęcia w OLX trzeba zaznaczyć ręcznie. Usługa nie steruje systemowym wyborem zdjęć.
- Cena to szacunek modelu AI, a nie dane z OLX.

## Struktura kodu

```
app/src/main/java/pl/wystaw/app/
  MainActivity.kt          – start, obsługa „Udostępnij”
  MainViewModel.kt         – stan aplikacji i przejścia między ekranami
  ai/ListingGenerator.kt   – wywołania Gemini / Claude, prompt, parsowanie JSON
  data/                    – Listing, ustawienia, magazyn ogłoszenia, zapis do albumu
  service/OlxFillService.kt – usługa ułatwień dostępu i pasek nad OLX
  ui/                      – ekrany (Jetpack Compose)
```

Nazwa pakietu OLX ustawiona w kodzie to `pl.tablica`. Jeśli Twoja wersja OLX ma inną, zmień `OLX_PACKAGES` w `OlxFillService.kt` i `<queries>` w `AndroidManifest.xml`.
