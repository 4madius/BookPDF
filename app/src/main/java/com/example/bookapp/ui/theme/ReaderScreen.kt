package com.example.bookapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bookapp.data.Book
import com.example.bookapp.data.Bookmark
import com.example.bookapp.data.SYSTEM_BRIGHTNESS
import kotlinx.coroutines.launch

// Night mode: turns every color into its opposite (white page -> black, black text -> white)
private val InvertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val currentBook = book
    val errorMessage = error

    if (currentBook != null) {
        ReaderContent(currentBook, viewModel, onBack)
    } else {
        // Loading or error state
        Scaffold(
            topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (errorMessage != null) Text(errorMessage) else CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(book: Book, viewModel: ReaderViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Open at the page where the reader stopped last time
    val pagerState = rememberPagerState(
        initialPage = book.lastReadPage.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))
    ) { book.pageCount }

    // Save the page number every time a swipe finishes ("settled")
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { viewModel.saveLastReadPage(it) }
    }

    // Brightness is previewed live while the slider moves, and saved when the finger lifts
    var liveBrightness by remember(settings.brightness) { mutableFloatStateOf(settings.brightness) }
    BrightnessEffect(liveBrightness)

    var showSheet by remember { mutableStateOf(false) }
    val isBookmarked = bookmarks.any { it.pageNumber == pagerState.currentPage }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { viewModel.toggleBookmark(pagerState.currentPage) }) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = "Bookmark this page"
                        )
                    }
                    IconButton(onClick = { showSheet = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Reader settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(if (settings.nightMode) Color.Black else MaterialTheme.colorScheme.background)
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }

                // HorizontalPager = the swipe-left/right "slide" transition
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
                    PdfPage(viewModel, pageIndex, widthPx, settings.nightMode)
                }
            }

            Text(
                text = "${pagerState.currentPage + 1} / ${book.pageCount}",
                style = MaterialTheme.typography.labelLarge,
                color = if (settings.nightMode) Color.White else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)
            )
        }
    }

    if (showSheet) {
        SettingsSheet(
            nightMode = settings.nightMode,
            brightness = liveBrightness,
            bookmarks = bookmarks,
            onDismiss = { showSheet = false },
            onNightModeChange = { viewModel.setNightMode(it) },
            onUseSystemBrightnessChange = { useSystem ->
                val value = if (useSystem) SYSTEM_BRIGHTNESS else 0.5f
                liveBrightness = value
                viewModel.setBrightness(value)
            },
            onBrightnessChange = { liveBrightness = it },
            onBrightnessChangeFinished = { viewModel.setBrightness(liveBrightness) },
            onBookmarkClick = { bookmark ->
                scope.launch { pagerState.scrollToPage(bookmark.pageNumber) }
                showSheet = false
            },
            onBookmarkDelete = { viewModel.deleteBookmark(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    nightMode: Boolean,
    brightness: Float,
    bookmarks: List<Bookmark>,
    onDismiss: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onUseSystemBrightnessChange: (Boolean) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onBrightnessChangeFinished: () -> Unit,
    onBookmarkClick: (Bookmark) -> Unit,
    onBookmarkDelete: (Bookmark) -> Unit
) {
    val useSystemBrightness = brightness < 0f

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Reader settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Night mode")
                Switch(checked = nightMode, onCheckedChange = onNightModeChange)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Use system brightness")
                Switch(checked = useSystemBrightness, onCheckedChange = onUseSystemBrightnessChange)
            }

            Slider(
                value = if (useSystemBrightness) 0.5f else brightness,
                onValueChange = onBrightnessChange,
                onValueChangeFinished = onBrightnessChangeFinished,
                valueRange = 0.05f..1f,
                enabled = !useSystemBrightness
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
            if (bookmarks.isEmpty()) {
                Text(
                    "No bookmarks yet. Tap the bookmark icon at the top to save a page.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                bookmarks.forEach { bookmark ->
                    ListItem(
                        headlineContent = { Text("Page ${bookmark.pageNumber + 1}") },
                        trailingContent = {
                            IconButton(onClick = { onBookmarkDelete(bookmark) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark")
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onBookmarkClick(bookmark) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(viewModel: ReaderViewModel, pageIndex: Int, widthPx: Int, nightMode: Boolean) {
    // Renders the page in the background; restarts if the page number or width changes
    val bitmap by produceState<Bitmap?>(initialValue = null, pageIndex, widthPx) {
        value = viewModel.getPageBitmap(pageIndex, widthPx)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val pageImage = bitmap
        if (pageImage != null) {
            Image(
                bitmap = pageImage.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                colorFilter = if (nightMode) InvertFilter else null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** Sets the screen brightness while the reader is open, and restores the system value when leaving. */
@Composable
private fun BrightnessEffect(brightness: Float) {
    val activity = LocalContext.current.findActivity()

    DisposableEffect(activity, brightness) {
        activity?.window?.let { window ->
            val params = window.attributes
            // SYSTEM_BRIGHTNESS (-1) means "no override"
            params.screenBrightness = if (brightness < 0f) brightness else brightness.coerceIn(0.05f, 1f)
            window.attributes = params
        }
        onDispose { }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
