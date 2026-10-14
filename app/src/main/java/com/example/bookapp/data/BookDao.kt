package com.example.bookapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY id DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: Long): Book?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    // How many saved books have this exact file content (0 = not a duplicate)
    @Query("SELECT COUNT(*) FROM books WHERE fileHash = :hash")
    suspend fun countByHash(hash: String): Int

    @Insert
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("UPDATE books SET lastReadPage = :page WHERE id = :bookId")
    suspend fun saveLastReadPage(bookId: Long, page: Int)

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY pageNumber")
    fun getBookmarks(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insertBookmark(bookmark: Bookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND pageNumber = :page")
    suspend fun deleteBookmarkAt(bookId: Long, page: Int)
}
