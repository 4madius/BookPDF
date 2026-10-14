package com.example.bookapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val pdfPath: String,        // path of the PDF copied into app storage
    val coverPath: String?,     // path of the cover image (page 1), null = no cover yet
    val pageCount: Int,
    val lastReadPage: Int = 0,
    val fileHash: String        // fingerprint of the PDF's content, used to detect duplicates
)
