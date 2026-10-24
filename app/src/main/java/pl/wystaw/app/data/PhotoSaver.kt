package pl.wystaw.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap

/**
 * Kopiuje wybrane zdjęcia do albumu „Pictures/Wystaw”, żeby w galerii OLX były na samej górze.
 * Okładka (pierwsze zdjęcie) dostaje najnowszą datę, więc pojawia się jako pierwsza.
 */
object PhotoSaver {
    const val ALBUM = "Wystaw"

    fun saveToAlbum(ctx: Context, uris: List<Uri>): Int {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val now = System.currentTimeMillis()
        var saved = 0

        uris.forEachIndexed { index, source ->
            val mime = resolver.getType(source) ?: "image/jpeg"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "wystaw_${now}_${index + 1}.$ext")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.DATE_TAKEN, now - index * 1000L)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values) ?: return@forEachIndexed
            val ok = runCatching {
                resolver.openInputStream(source).use { input ->
                    resolver.openOutputStream(target).use { output ->
                        if (input == null || output == null) error("brak strumienia")
                        input.copyTo(output)
                    }
                }
            }.isSuccess
            if (ok) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(target, values, null, null)
                saved++
            } else {
                resolver.delete(target, null, null)
            }
        }
        return saved
    }
}
