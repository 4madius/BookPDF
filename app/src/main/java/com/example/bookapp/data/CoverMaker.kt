package com.example.bookapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Where a new cover should come from. */
sealed interface CoverChoice {
    data class FromImage(val uri: Uri) : CoverChoice          // an image picked from the phone
    data class FromPdfPage(val pageIndex: Int) : CoverChoice  // a page of the book itself (0-based)
}

object CoverMaker {
    private const val COVER_WIDTH = 600

    /**
     * Creates a NEW cover file for the book and returns its path.
     * A new file name is used every time so the image cache never shows the old cover.
     */
    suspend fun create(context: Context, book: Book, choice: CoverChoice): String =
        withContext(Dispatchers.IO) {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val coverFile = File(coversDir, "${UUID.randomUUID()}.jpg")
            try {
                when (choice) {
                    is CoverChoice.FromImage -> saveImage(context, choice.uri, coverFile)
                    is CoverChoice.FromPdfPage -> savePdfPage(book.pdfPath, choice.pageIndex, coverFile)
                }
                coverFile.absolutePath
            } catch (e: Exception) {
                coverFile.delete()
                throw e
            }
        }

    private fun saveImage(context: Context, uri: Uri, outFile: File) {
        val resolver = context.contentResolver

        // 1. Read only the image size (cheap)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IllegalStateException("Not a valid image")

        // 2. Decode a smaller version so a big photo doesn't use too much memory
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Cannot read the image")

        try {
            // 3. Put it on a white background (JPEG has no transparency), then save
            val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            flat.eraseColor(Color.WHITE)
            Canvas(flat).drawBitmap(decoded, 0f, 0f, null)
            FileOutputStream(outFile).use { flat.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            flat.recycle()
        } finally {
            decoded.recycle()
        }
    }

    private fun savePdfPage(pdfPath: String, pageIndex: Int, outFile: File) {
        ParcelFileDescriptor.open(File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (pageIndex !in 0 until renderer.pageCount) {
                    throw IllegalArgumentException("Page out of range")
                }
                val page = renderer.openPage(pageIndex)
                try {
                    val height = (COVER_WIDTH.toFloat() * page.height / page.width).toInt()
                    val bitmap = Bitmap.createBitmap(COVER_WIDTH, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle()
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    }
}
