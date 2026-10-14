package com.example.bookapp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.bookapp.data.Book
import com.example.bookapp.data.CoverChoice
import java.io.File

@Composable
fun EditBookDialog(
    book: Book,
    onDismiss: () -> Unit,
    onSave: (title: String, cover: CoverChoice?) -> Unit   // cover = null means "keep the current cover"
) {
    var title by remember { mutableStateOf(book.title) }
    var pickedImage by remember { mutableStateOf<Uri?>(null) }
    var pageText by remember { mutableStateOf("") }

    // Opens Android's file picker, limited to images
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedImage = uri
            pageText = ""          // only one cover source at a time
        }
    }

    val pageNumber = pageText.toIntOrNull()
    val pageIsValid = pageText.isBlank() || (pageNumber != null && pageNumber in 1..book.pageCount)
    val canSave = title.isNotBlank() && pageIsValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit book") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Text("Cover", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pageText.isBlank()) {
                        AsyncImage(
                            model = pickedImage ?: book.coverPath?.let { File(it) },
                            contentDescription = "Cover preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(90.dp).aspectRatio(2f / 3f)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    OutlinedButton(onClick = { pickImage.launch(arrayOf("image/*")) }) {
                        Text("Choose image")
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(5)
                        pageText = digits
                        if (digits.isNotEmpty()) pickedImage = null   // only one cover source at a time
                    },
                    label = { Text("Or use a PDF page (1 to ${book.pageCount})") },
                    isError = !pageIsValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val image = pickedImage
                    val cover: CoverChoice? = when {
                        image != null -> CoverChoice.FromImage(image)
                        pageNumber != null -> CoverChoice.FromPdfPage(pageNumber - 1)
                        else -> null
                    }
                    onSave(title.trim(), cover)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
