package com.example.bookapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bookapp.data.AppDatabase
import com.example.bookapp.data.Book
import com.example.bookapp.data.BookImporter
import com.example.bookapp.data.CoverChoice
import com.example.bookapp.data.CoverMaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).bookDao()

    val books: StateFlow<List<Book>> = dao.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // true while the app is busy (importing PDFs, saving edits)
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    // one-time message for the screen to show (success or error)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun addBooks(uris: List<Uri>) {
        viewModelScope.launch {
            _isImporting.value = true
            var added = 0
            var duplicates = 0
            var failed = 0

            for (uri in uris) {
                try {
                    val book = BookImporter.import(getApplication(), uri) { hash ->
                        dao.countByHash(hash) > 0
                    }
                    if (book == null) {
                        duplicates++
                    } else {
                        dao.insertBook(book)
                        added++
                    }
                } catch (e: Exception) {
                    failed++
                }
            }

            _isImporting.value = false

            val parts = mutableListOf<String>()
            if (added > 0) parts.add(if (added == 1) "1 book added" else "$added books added")
            if (duplicates > 0) parts.add("$duplicates already in your library")
            if (failed > 0) parts.add("$failed could not be added")
            _message.value = parts.joinToString(", ")
        }
    }

    /** Saves a new title and, if cover is not null, replaces the cover. */
    fun saveBookEdits(book: Book, newTitle: String, cover: CoverChoice?) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val oldCoverPath = book.coverPath
                var newCoverPath = oldCoverPath

                if (cover != null) {
                    newCoverPath = CoverMaker.create(getApplication(), book, cover)
                }

                dao.updateBook(
                    book.copy(
                        title = newTitle.ifBlank { book.title },
                        coverPath = newCoverPath
                    )
                )

                // Only delete the old cover after the database points to the new one
                if (cover != null && oldCoverPath != null) {
                    File(oldCoverPath).delete()
                }
                _message.value = "Changes saved"
            } catch (e: Exception) {
                _message.value = "Could not save the changes. Please try a different image or page."
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }
}
