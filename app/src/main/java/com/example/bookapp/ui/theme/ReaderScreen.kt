package com.example.bookapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bookapp.data.Book
import com.example.bookapp.data.Bookmark
import com.example.bookapp.data.ReaderSettings
import com.example.bookapp.data.SYSTEM_BRIGHTNESS
import kotlinx.coroutines.launch
import kotlin.math.min

// Pages are never rendered wider than this, so a wide landscape screen doesn't create huge bitmaps
private const val MAX_RENDER_WIDTH_PX = 1600

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

// ---------------------------------------------------------------------------------
// Pages and "slots".
// The pager moves between SLOTS. In single-page view, slot n shows page n.
// In double-page view the book is laid out like a real book:
//   slot 0 = the cover alone (right side), slot 1 = pages 1 and 2, slot 2 = pages 3 and 4, ...
// Page numbers here are 0-based (page 0 is the first page).
// ---------------------------------------------------------------------------------

private fun slotCount(pageCount: Int, doublePage: Boolean) =
    if (doublePage) pageCount / 2 + 1 else pageCount

private fun slotForPage(page: Int, doublePage: Boolean) =
    if (doublePage) (page + 1) / 2 else page

private fun firstPageOfSlot(slot: Int, doublePage: Boolean) =
    if (doublePage) (if (slot == 0) 0 else 2 * slot - 1) else slot

private fun pagesInSlot(slot: Int, pageCount: Int, doublePage: Boolean): List<Int> =
    when {
        !doublePage -> listOf(slot)
        slot == 0 -> listOf(0)
        else -> listOf(2 * slot - 1, 2 * slot).filter { it < pageCount }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val currentBook = book
    val currentSettings = settings
    val errorMessage = error

    if (currentBook != null && currentSettings != null) {
        ReaderContent(currentBook, currentSettings, viewModel, onBack)
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
private fun ReaderContent(
    book: Book,
    settings: ReaderSettings,
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val doublePage = settings.doublePage
    val lastPage = (book.pageCount - 1).coerceAtLeast(0)

    // The page the reader is on (the first page on screen). It is kept when the view mode changes
    // and when the phone is rotated.
    var currentPage by rememberSaveable { mutableIntStateOf(book.lastReadPage.coerceIn(0, lastPage)) }

    // A new pager is created whenever the view mode changes, because the number of slots changes
    val pagerState = key(doublePage) {
        rememberPagerState(
            initialPage = slotForPage(currentPage, doublePage)
        ) { slotCount(book.pageCount, doublePage) }
    }

    // Every time a swipe finishes ("settled"), remember and save the page
    LaunchedEffect(pagerState, doublePage) {
        snapshotFlow { pagerState.settledPage }.collect { slot ->
            val page = firstPageOfSlot(slot, doublePage)
            currentPage = page
            viewModel.saveLastReadPage(page)
        }
    }

    // Brightness is previewed live while the slider moves, and saved when the finger lifts
    var liveBrightness by remember(settings.brightness) { mutableFloatStateOf(settings.brightness) }
    BrightnessEffect(liveBrightness)

    var showSheet by remember { mutableStateOf(false) }

    // Tap the page to hide or show the top bar, the page counter and the system bars (fullscreen reading)
    var showControls by rememberSaveable { mutableStateOf(true) }
    ImmersiveEffect(hideBars = !showControls)

    val visiblePages = pagesInSlot(pagerState.currentPage, book.pageCount, doublePage)
    val isBookmarked = bookmarks.any { it.pageNumber in visiblePages }

    Scaffold(
        topBar = {
            if (showControls) TopAppBar(
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = { viewModel.toggleBookmark(visiblePages) }) {
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
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null          // no ripple, a tap just toggles the controls
                    ) { showControls = !showControls }
            ) {
                val fullWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                // In double-page view each page gets half of the width
                val pageWidthPx = min(if (doublePage) fullWidthPx / 2 else fullWidthPx, MAX_RENDER_WIDTH_PX)
                // Phone turned sideways: single pages fill the width and scroll up and down
                val isLandscape = maxWidth > maxHeight

                // HorizontalPager = the swipe-left/right "slide" transition
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { slot ->
                    if (!doublePage) {
                        PdfPage(viewModel, slot, pageWidthPx, settings.nightMode, fitWidth = isLandscape)
                    } else {
                        // slot 0: nothing on the left, the cover on the right.
                        // Last slot of a book with an even number of pages: nothing on the right.
                        val leftPage: Int? = if (slot == 0) null else 2 * slot - 1
                        val rightPage: Int? = if (slot == 0) 0 else (2 * slot).takeIf { it < book.pageCount }

                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (leftPage != null) {
                                    PdfPage(viewModel, leftPage, pageWidthPx, settings.nightMode, Alignment.CenterEnd)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (rightPage != null) {
                                    PdfPage(viewModel, rightPage, pageWidthPx, settings.nightMode, Alignment.CenterStart)
                                }
                            }
                        }
                    }
                }
            }

            if (showControls) {
                val pageLabel = if (visiblePages.size == 1) {
                    "${visiblePages.first() + 1}"
                } else {
                    "${visiblePages.first() + 1}-${visiblePages.last() + 1}"
                }
                Text(
                    text = "$pageLabel / ${book.pageCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (settings.nightMode) Color.White else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)
                )
            }
        }
    }

    if (showSheet) {
        SettingsSheet(
            nightMode = settings.nightMode,
            doublePage = doublePage,
            brightness = liveBrightness,
            bookmarks = bookmarks,
            onDismiss = { showSheet = false },
            onNightModeChange = { viewModel.setNightMode(it) },
            onDoublePageChange = { viewModel.setDoublePage(it) },
            onUseSystemBrightnessChange = { useSystem ->
                val value = if (useSystem) SYSTEM_BRIGHTNESS else 0.5f
                liveBrightness = value
                viewModel.setBrightness(value)
            },
            onBrightnessChange = { liveBrightness = it },
            onBrightnessChangeFinished = { viewModel.setBrightness(liveBrightness) },
            onBookmarkClick = { bookmark ->
                scope.launch { pagerState.scrollToPage(slotForPage(bookmark.pageNumber, doublePage)) }
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
    doublePage: Boolean,
    brightness: Float,
    bookmarks: List<Bookmark>,
    onDismiss: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onDoublePageChange: (Boolean) -> Unit,
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
                Column(Modifier.weight(1f)) {
                    Text("Double page view")
                    Text(
                        "Two pages side by side. Works best with the phone turned sideways.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = doublePage, onCheckedChange = onDoublePageChange)
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
private fun PdfPage(
    viewModel: ReaderViewModel,
    pageIndex: Int,
    widthPx: Int,
    nightMode: Boolean,
    alignment: Alignment = Alignment.Center,    // in double-page view pages lean toward the middle
    fitWidth: Boolean = false                   // true = fill the width and scroll vertically
) {
    // Renders the page in the background; restarts if the page number or width changes
    val bitmap by produceState<Bitmap?>(initialValue = null, pageIndex, widthPx) {
        value = viewModel.getPageBitmap(pageIndex, widthPx)
    }
    val pageImage = bitmap

    if (fitWidth) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (pageImage != null) {
                Image(
                    bitmap = pageImage.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                    colorFilter = if (nightMode) InvertFilter else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(pageImage.width.toFloat() / pageImage.height)
                )
            } else {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (pageImage != null) {
                Image(
                    bitmap = pageImage.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    alignment = alignment,
                    colorFilter = if (nightMode) InvertFilter else null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator()
            }
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

/** Hides the status and navigation bars (fullscreen) while [hideBars] is true. */
@Composable
private fun ImmersiveEffect(hideBars: Boolean) {
    val activity = LocalContext.current.findActivity()

    DisposableEffect(activity, hideBars) {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (hideBars) {
                // Swiping from the screen edge shows the bars briefly
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // Always bring the bars back when leaving the reader
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
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
