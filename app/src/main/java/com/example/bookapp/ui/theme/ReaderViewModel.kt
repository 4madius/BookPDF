package com.example.bookapp.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.bookapp.data.AppDatabase
import com.example.bookapp.data.Book
import com.example.bookapp.data.Bookmark
import com.example.bookapp.data.ReaderSettings
import com.example.bookapp.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ReaderViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle      // holds the "bookId" passed by navigation
) : AndroidViewModel(app) {

    private val bookId: Long = checkNotNull(savedStateHandle.get<Long>("bookId"))
    private val dao = AppDatabase.getInstance(app).bookDao()
    private val settingsRepository = SettingsRepository(app)

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Reader settings saved on the phone. It is null until they have been loaded,
    // so the reader never flashes the wrong mode (for example white before night mode).
    val settings: StateFlow<ReaderSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Bookmarks of this book, sorted by page
    val bookmarks: StateFlow<List<Bookmark>> = dao.getBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    // PdfRenderer can only handle ONE page at a time, so renders take turns using this lock
    private val renderLock = Mutex()

    // Keeps recently rendered pages in memory so swiping back is instant.
    // The size is measured in bytes: one eighth of the memory the app is allowed to use.
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { openBook() }
    }

    private suspend fun openBook() {
        try {
            val loaded = dao.getBook(bookId)
            if (loaded == null) {
                _error.value = "This book could not be found."
                return
            }
            val pfd = ParcelFileDescriptor.open(File(loaded.pdfPath), ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor = pfd
            renderer = PdfRenderer(pfd)
            _book.value = loaded
        } catch (e: Exception) {
            _error.value = "This book could not be opened."
        }
    }

    /** Renders one page (0-based) at the given width in pixels. Returns null if the book is closed. */
    suspend fun getPageBitmap(pageIndex: Int, targetWidth: Int): Bitmap? {
        val key = "$pageIndex@$targetWidth"
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            renderLock.withLock {
                cache.get(key)?.let { return@withLock it }   // another request may have rendered it meanwhile
                val pdf = renderer ?: return@withLock null

                val page = pdf.openPage(pageIndex)
                try {
                    val height = (targetWidth.toFloat() * page.height / page.width).toInt()
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)    // PdfRenderer draws on a transparent background
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                } finally {
                    page.close()
                }
            }
        }
    }

    fun saveLastReadPage(page: Int) {
        viewModelScope.launch { dao.saveLastReadPage(bookId, page) }
    }

    // ---- Bookmarks ----

    /**
     * [pages] are the pages currently on screen (one page, or two in double-page view).
     * If any of them is bookmarked, those bookmarks are removed. Otherwise the first page is bookmarked.
     */
    fun toggleBookmark(pages: List<Int>) {
        viewModelScope.launch {
            val existing = bookmarks.value.filter { it.pageNumber in pages }
            if (existing.isNotEmpty()) {
                existing.forEach { dao.deleteBookmark(it) }
            } else {
                pages.firstOrNull()?.let { dao.insertBookmark(Bookmark(bookId = bookId, pageNumber = it)) }
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch { dao.deleteBookmark(bookmark) }
    }

    // ---- Settings ----

    fun setNightMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNightMode(enabled) }
    }

    fun setBrightness(value: Float) {
        viewModelScope.launch { settingsRepository.setBrightness(value) }
    }

    fun setDoublePage(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDoublePage(enabled) }
    }

    override fun onCleared() {
        val pdf = renderer
        val pfd = fileDescriptor
        renderer = null
        fileDescriptor = null
        cache.evictAll()
        // Close after any render in progress has finished
        CoroutineScope(Dispatchers.IO).launch {
            renderLock.withLock {
                pdf?.close()
                pfd?.close()
            }
        }
    }
}
