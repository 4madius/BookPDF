package com.example.bookapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Turns a PDF picked by the user into a Book:
 * 1. copies the PDF into the app's private storage and fingerprints it (SHA-256)
 * 2. asks the caller whether a book with that fingerprint already exists
 * 3. renders page 1 as a cover image
 * 4. returns a Book (the caller saves it), or null if it was a duplicate
 */
object BookImporter {
    private const val COVER_WIDTH = 600   // pixels; height is calculated from the page ratio

    suspend fun import(
        context: Context,
        uri: Uri,
        isDuplicate: suspend (hash: String) -> Boolean
    ): Book? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val pdfFile = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.pdf")
        val coverFile = File(File(context.filesDir, "covers").apply { mkdirs() }, "$id.jpg")

        try {
            // 1. Copy the PDF while calculating its fingerprint at the same time
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open the selected file")
            input.use { src ->
                DigestInputStream(src, digest).use { hashing ->
                    pdfFile.outputStream().use { dst -> hashing.copyTo(dst) }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }

            // 2. Already in the library? Throw the copy away and stop.
            if (isDuplicate(hash)) {
                pdfFile.delete()
                return@withContext null
            }

            // 3. Render page 1 as the cover
            var pageCount = 0
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val renderer = PdfRenderer(pfd)   // throws if the PDF is damaged or password-protected
                try {
                    pageCount = renderer.pageCount
                    if (pageCount == 0) throw IllegalStateException("PDF has no pages")

                    val page = renderer.openPage(0)
                    try {
                        val height = (COVER_WIDTH.toFloat() * page.height / page.width).toInt()
                        val bitmap = Bitmap.createBitmap(COVER_WIDTH, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)   // PdfRenderer draws on a transparent background
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(coverFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        bitmap.recycle()
                    } finally {
                        page.close()
                    }
                } finally {
                    renderer.close()
                }
            }

            Book(
                title = getTitle(context, uri),
                pdfPath = pdfFile.absolutePath,
                coverPath = coverFile.absolutePath,
                pageCount = pageCount,
                fileHash = hash
            )
        } catch (e: Exception) {
            // Don't leave half-finished files behind
            pdfFile.delete()
            coverFile.delete()
            throw e
        }
    }

    // Android's PdfRenderer cannot read a PDF's metadata title, so we use the file name.
    // The user will be able to edit it later in the Customize module.
    private fun getTitle(context: Context, uri: Uri): String {
        var name = "Untitled"
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: name
                }
            }
        if (name.endsWith(".pdf", ignoreCase = true)) name = name.dropLast(4)
        return name.trim().ifBlank { "Untitled" }
    }
}
