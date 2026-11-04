package com.example.aozorareader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

// ==========================================
// 1. データ構造 & Room DB
// ==========================================
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: Int,
    val title: String,
    val author: String,
    val downloadUrl: String,
    val localFilePath: String? = null,
    val isDownloaded: Boolean = false,
    val lastScrollPosition: Float = 0f
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE isDownloaded = 1 ORDER BY id DESC")
    fun getDownloadedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Book?

    @Query("""
        SELECT * FROM books 
        WHERE REPLACE(REPLACE(title, ' ', ''), '　', '') LIKE '%' || :query || '%' 
           OR REPLACE(REPLACE(author, ' ', ''), '　', '') LIKE '%' || :query || '%' 
        ORDER BY title ASC 
        LIMIT 200
    """)
    suspend fun searchLocal(query: String): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(book: Book)

    @Query("UPDATE books SET lastScrollPosition = :position WHERE id = :id")
    suspend fun updateScrollPosition(id: Int, position: Float)
}

@Database(entities = [Book::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

// ==========================================
// 2. リポジトリ
// ==========================================
class BookRepository(private val context: Context, private val bookDao: BookDao) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun extractTxt(bytes: ByteArray): ByteArray? {
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".txt", ignoreCase = true)) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun search(query: String): List<Book> = withContext(Dispatchers.IO) {
        val q = query.replace(" ", "").replace("　", "")
        if (q.isBlank()) return@withContext emptyList()
        bookDao.searchLocal(q)
    }

    suspend fun getBook(id: Int): Book? = withContext(Dispatchers.IO) {
        bookDao.getById(id)
    }

    suspend fun downloadBook(book: Book): Book? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(book.downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android; AozoraReader)")
                .build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bytes = res.body?.bytes() ?: return@use null

                val raw = (if (book.downloadUrl.endsWith(".zip", ignoreCase = true)) extractTxt(bytes) else bytes)
                    ?: return@use null

                val rawText = try {
                    Charset.forName("MS932").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw))
                        .toString()
                } catch (e: Exception) {
                    try {
                        Charset.forName("UTF-8").newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(raw))
                            .toString()
                    } catch (e2: Exception) {
                        String(raw, Charset.forName("windows-31j"))
                    }
                }

                val file = File(context.filesDir, "book_${book.id}.txt")
                file.writeText(rawText, Charsets.UTF_8)

                val updated = book.copy(isDownloaded = true, localFilePath = file.absolutePath)
                bookDao.insertOrUpdate(updated)
                updated
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun deleteBook(book: Book): Book = withContext(Dispatchers.IO) {
        book.localFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        val updated = book.copy(isDownloaded = false, localFilePath = null, lastScrollPosition = 0f)
        bookDao.insertOrUpdate(updated)
        updated
    }

    suspend fun saveBookmark(bookId: Int, position: Float) = withContext(Dispatchers.IO) {
        bookDao.updateScrollPosition(bookId, position)
    }
}

// ==========================================
// 3. 青空文庫パーサー
// ==========================================
object AozoraParser {
    fun toHtml(rawText: String, initialFontSize: Int, initialDarkMode: Boolean, savedRatio: Float): String {
        var text = rawText.replace("\r\n", "\n").replace("\r", "\n")

        if (text.contains("【テキスト中に現れる記号について】")) {
            val headerRegex = Regex("""(?s)^.*?-{35,}\n.*?-{35,}\n+""")
            text = text.replaceFirst(headerRegex, "")
        }

        val bottomIdx = text.lastIndexOf("\n底本：")
        if (bottomIdx != -1) {
            text = text.substring(0, bottomIdx)
        } else {
            val altBottomIdx = text.lastIndexOf("底本：")
            if (altBottomIdx != -1 && altBottomIdx > text.length * 0.7) {
                text = text.substring(0, altBottomIdx)
            }
        }

        // 注記［＃...］を先に除去してからHTMLエスケープ・ルビ変換を行う
        text = text.replace(Regex("※?［＃[^］]*］"), "")
        text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        text = text.replace(Regex("[｜|]([^《》\\n]+)《([^》\\n]+)》")) { "<ruby>${it.groupValues[1]}<rt>${it.groupValues[2]}</rt></ruby>" }
        text = text.replace(Regex("([一-龠々〆ヵヶ]+)《([^》\\n]+)》")) { "<ruby>${it.groupValues[1]}<rt>${it.groupValues[2]}</rt></ruby>" }
        text = text.replace("\n", "<br>")

        val bgColor = if (initialDarkMode) "#121212" else "#faf7f0"
        val textColor = if (initialDarkMode) "#e0e0e0" else "#2b2b2b"
        val rubyColor = if (initialDarkMode) "#888888" else "#666666"
        val ratio = if (savedRatio in 0.0001f..1f) savedRatio else 0f

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <style>
                * { box-sizing: border-box; }
                html, body {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    height: 100%;
                    background-color: $bgColor;
                    overflow: hidden;
                    user-select: none;
                    -webkit-user-select: none;
                    -webkit-tap-highlight-color: transparent;
                }
                #content {
                    width: 100vw;
                    height: 100vh;
                    padding: 84px 28px 36px 28px;
                    writing-mode: vertical-rl;
                    -webkit-writing-mode: vertical-rl;
                    overflow-x: scroll;
                    overflow-y: hidden;
                    background-color: $bgColor;
                    color: $textColor;
                    font-family: 'Noto Serif CJK JP', 'Noto Serif JP', 'Hiragino Mincho ProN', 'Yu Mincho', serif;
                    font-size: ${initialFontSize}px;
                    line-height: 2.1;
                    transition: background-color 0.25s, color 0.25s;
                }
                #content::-webkit-scrollbar { display: none; }
                ruby rt { font-size: 0.5em; color: $rubyColor; }
                #prog {
                    position: fixed;
                    left: 0; right: 0; bottom: 8px;
                    text-align: center;
                    font-size: 12px;
                    font-family: sans-serif;
                    color: $rubyColor;
                    pointer-events: none;
                }
            </style>
            <script>
                var savedRatio = $ratio;
                var touching = false;
                var startX = 0;
                var startY = 0;
                var isMoved = false;
                var snapTimer = null;
                var saveTimer = null;
                var scrollSign = -1;

                function el() { return document.getElementById('content'); }

                function detectSign() {
                    var c = el();
                    if (!c) return;
                    var prev = c.scrollLeft;
                    c.scrollLeft = -10;
                    if (c.scrollLeft < 0) {
                        scrollSign = -1;
                    } else {
                        c.scrollLeft = 10;
                        scrollSign = (c.scrollLeft > 0) ? 1 : -1;
                    }
                    c.scrollLeft = prev;
                }

                function step() {
                    var c = el();
                    var fs = parseFloat(getComputedStyle(c).fontSize);
                    var pitch = fs * 2.1;
                    var avail = c.clientWidth - 56;
                    return Math.max(pitch, Math.floor(avail / pitch) * pitch);
                }
                function maxScroll() { var c = el(); return Math.max(0, c.scrollWidth - c.clientWidth); }
                function cur() { return Math.abs(el().scrollLeft); }
                function ratio() { var m = maxScroll(); return m > 0 ? Math.min(1, cur() / m) : 0; }

                function scrollToPos(p, smooth) {
                    var c = el();
                    p = Math.max(0, Math.min(maxScroll(), p));
                    c.scrollTo({ left: scrollSign * p, behavior: smooth ? 'smooth' : 'auto' });
                }

                function go(dir) {
                    var s = step();
                    var t = (Math.round(cur() / s) + dir) * s;
                    scrollToPos(t, true);
                }

                function updateProgress() {
                    var p = document.getElementById('prog');
                    if (!p) return;
                    var s = step();
                    var m = maxScroll();
                    var total = Math.max(1, Math.ceil(m / s - 0.01) + 1);
                    var page = Math.min(total, Math.round(cur() / s) + 1);
                    p.textContent = page + ' / ' + total + '  (' + Math.round(ratio() * 100) + '%)';
                }

                function flushScroll() {
                    if (window.AndroidBridge) { window.AndroidBridge.saveScroll(ratio()); }
                }

                function trySnap() {
                    if (touching) return;
                    var s = step();
                    var m = maxScroll();
                    var c0 = cur();
                    if (c0 >= m - 2) return;
                    var t = Math.round(c0 / s) * s;
                    if (Math.abs(c0 - t) > 2) { scrollToPos(t, true); }
                }

                function onScrolled() {
                    updateProgress();
                    clearTimeout(saveTimer);
                    saveTimer = setTimeout(flushScroll, 400);
                    clearTimeout(snapTimer);
                    snapTimer = setTimeout(trySnap, 160);
                }

                function updateTheme(isDark) {
                    var bg = isDark ? '#121212' : '#faf7f0';
                    var fg = isDark ? '#e0e0e0' : '#2b2b2b';
                    var sub = isDark ? '#888888' : '#666666';
                    document.documentElement.style.backgroundColor = bg;
                    document.body.style.backgroundColor = bg;
                    var c = el();
                    if (c) { c.style.backgroundColor = bg; c.style.color = fg; }
                    var rts = document.querySelectorAll('ruby rt');
                    for (var i = 0; i < rts.length; i++) { rts[i].style.color = sub; }
                    var p = document.getElementById('prog');
                    if (p) { p.style.color = sub; }
                }

                function updateFontSize(size) {
                    var c = el();
                    if (!c) return;
                    var currentFs = parseFloat(getComputedStyle(c).fontSize);
                    if (Math.abs(currentFs - size) < 0.5) return;
                    var r = ratio();
                    c.style.fontSize = size + 'px';
                    setTimeout(function () {
                        scrollToPos(r * maxScroll(), false);
                        updateProgress();
                        trySnap();
                    }, 60);
                }

                function handleTap(event) {
                    if (isMoved) return;
                    var x = event.clientX;
                    var w = window.innerWidth;
                    if (x < w * 0.35) {
                        go(1);
                    } else if (x > w * 0.65) {
                        go(-1);
                    } else if (window.AndroidBridge) {
                        window.AndroidBridge.toggleMenu();
                    }
                }

                window.addEventListener('load', function () {
                    setTimeout(function () {
                        var c = el();
                        if (!c) return;
                        detectSign();
                        if (savedRatio > 0) { scrollToPos(savedRatio * maxScroll(), false); }
                        updateProgress();
                        trySnap();
                        c.addEventListener('scroll', onScrolled);
                        c.addEventListener('touchstart', function (e) {
                            touching = true;
                            isMoved = false;
                            if (e.touches && e.touches.length > 0) {
                                startX = e.touches[0].clientX;
                                startY = e.touches[0].clientY;
                            }
                        }, { passive: true });
                        c.addEventListener('touchmove', function (e) {
                            if (e.touches && e.touches.length > 0) {
                                var dx = Math.abs(e.touches[0].clientX - startX);
                                var dy = Math.abs(e.touches[0].clientY - startY);
                                if (dx > 12 || dy > 12) { isMoved = true; }
                            }
                        }, { passive: true });
                        var endTouch = function () {
                            touching = false;
                            clearTimeout(snapTimer);
                            snapTimer = setTimeout(trySnap, 160);
                        };
                        c.addEventListener('touchend', endTouch, { passive: true });
                        c.addEventListener('touchcancel', endTouch, { passive: true });
                    }, 120);
                });
            </script>
        </head>
        <body onclick="handleTap(event)">
            <div id="content">$text</div>
            <div id="prog"></div>
        </body>
        </html>
        """.trimIndent()
    }
}

// ==========================================
// 4. UI: リーダー画面
// ==========================================
class AndroidBridge(
    private val onToggle: () -> Unit,
    private val onSaveScroll: (Float) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun toggleMenu() { mainHandler.post { onToggle() } }

    @JavascriptInterface
    fun saveScroll(position: Float) { mainHandler.post { onSaveScroll(position) } }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book,
    onBack: () -> Unit,
    onSaveBookmark: (Float) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }

    var fontSize by rememberSaveable { mutableIntStateOf(prefs.getInt("fontSize", 18)) }
    var isDarkMode by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
    var showControls by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val currentOnSaveBookmark by rememberUpdatedState(onSaveBookmark)
    val currentOnBack by rememberUpdatedState(onBack)

    val handleBack: () -> Unit = {
        val w = webViewRef
        if (w != null) {
            w.evaluateJavascript("flushScroll();") { currentOnBack() }
        } else {
            currentOnBack()
        }
    }
    BackHandler(onBack = handleBack)

    val htmlContent by produceState<String?>(initialValue = null, key1 = book.localFilePath) {
        value = withContext(Dispatchers.IO) {
            val path = book.localFilePath
            if (path != null && File(path).exists()) {
                AozoraParser.toHtml(File(path).readText(Charsets.UTF_8), fontSize, isDarkMode, book.lastScrollPosition)
            } else {
                "<html><body><p>作品を読み込めませんでした</p></body></html>"
            }
        }
    }

    LaunchedEffect(isDarkMode, webViewRef) {
        prefs.edit().putBoolean("dark", isDarkMode).apply()
        webViewRef?.evaluateJavascript("updateTheme($isDarkMode);", null)
    }
    LaunchedEffect(fontSize, webViewRef) {
        prefs.edit().putInt("fontSize", fontSize).apply()
        webViewRef?.evaluateJavascript("updateFontSize($fontSize);", null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (htmlContent == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = WebViewClient()
                        settings.apply {
                            javaScriptEnabled = true
                            loadWithOverviewMode = false
                            useWideViewPort = false
                        }
                        addJavascriptInterface(
                            AndroidBridge(
                                onToggle = { showControls = !showControls },
                                onSaveScroll = { pos -> currentOnSaveBookmark(pos) }
                            ),
                            "AndroidBridge"
                        )
                        loadDataWithBaseURL(null, htmlContent!!, "text/html", "utf-8", null)
                        webViewRef = this
                    }
                }
            )

            DisposableEffect(Unit) {
                onDispose {
                    webViewRef?.destroy()
                    webViewRef = null
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = { Text(book.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { if (fontSize > 12) fontSize -= 2 }, enabled = fontSize > 12) {
                        Icon(Icons.Default.TextDecrease, contentDescription = "文字縮小")
                    }
                    IconButton(onClick = { if (fontSize < 32) fontSize += 2 }, enabled = fontSize < 32) {
                        Icon(Icons.Default.TextIncrease, contentDescription = "文字拡大")
                    }
                    IconButton(onClick = { isDarkMode = !isDarkMode }) {
                        Icon(if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "テーマ")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        }
    }
}

// ==========================================
// 5. UI: メイン画面
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    downloadedBooks: List<Book>,
    searchResults: List<Book>,
    searchQuery: String,
    isSearching: Boolean,
    hasSearched: Boolean,
    downloadingIds: Set<Int>,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onDownloadClick: (Book) -> Unit,
    onDeleteClick: (Book) -> Unit,
    onBookClick: (Book) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }

    if (bookToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("作品の削除") },
            text = { Text("『${bookToDelete!!.title}』を端末から削除しますか？\n（いつでも再検索してダウンロード可能です）") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = bookToDelete!!
                        bookToDelete = null
                        onDeleteClick(target)
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (selectedTab == 0) "本棚（オフライン）" else "青空文庫 全作品検索") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text("本棚") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("探す") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                if (downloadedBooks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("保存された作品がありません。\n「探す」タブから本を検索して保存してください。")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(downloadedBooks, key = { it.id }) { book ->
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = {
                                    val pct = (book.lastScrollPosition.coerceIn(0f, 1f) * 100).toInt()
                                    Text(if (pct > 0) "${book.author}　・　読了率 ${pct}%" else book.author)
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FilledTonalButton(onClick = { onBookClick(book) }) {
                                            Text("読む")
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(onClick = { bookToDelete = book }) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "削除",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { onBookClick(book) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                val focusManager = LocalFocusManager.current
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onQueryChange,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("作品名・作家名（例: 漱石、こころ）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                focusManager.clearFocus()
                                onSearchSubmit()
                            })
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            focusManager.clearFocus()
                            onSearchSubmit()
                        }) {
                            Text("検索")
                        }
                    }

                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (hasSearched && searchResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("該当する作品が見つかりませんでした。\n別のキーワードでお試しください。")
                        }
                    } else if (!hasSearched) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("作家名や作品名を入力して検索してください。\n（全作品から完全オフラインで即座に検索できます）")
                        }
                    } else {
                        Text(
                            text = "${searchResults.size}件の作品が見つかりました",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(searchResults, key = { it.id }) { book ->
                                val isDownloading = downloadingIds.contains(book.id)
                                ListItem(
                                    headlineContent = { Text(book.title) },
                                    supportingContent = { Text(book.author) },
                                    trailingContent = {
                                        if (book.isDownloaded) {
                                            TextButton(onClick = { onBookClick(book) }) { Text("読む") }
                                        } else if (isDownloading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.5.dp
                                            )
                                        } else {
                                            IconButton(onClick = { onDownloadClick(book) }) {
                                                Icon(Icons.Default.Download, contentDescription = "ダウンロード")
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 6. エントリーポイント
// ==========================================
class MainActivity : ComponentActivity() {
    private val db by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "aozora_official_v2.db")
            .createFromAsset("databases/aozora_official.db")
            .fallbackToDestructiveMigration()
            .build()
    }
    private val repository by lazy { BookRepository(this, db.bookDao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var currentReadingBook by remember { mutableStateOf<Book?>(null) }
                var searchQuery by rememberSaveable { mutableStateOf("") }
                var searchResults by remember { mutableStateOf<List<Book>>(emptyList()) }
                var isSearching by remember { mutableStateOf(false) }
                var hasSearched by rememberSaveable { mutableStateOf(false) }
                var downloadingIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

                val downloadedBooksFlow = remember { db.bookDao().getDownloadedBooks() }
                val downloadedBooks by downloadedBooksFlow.collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(snackbarHost = {
                    if (currentReadingBook == null) {
                        SnackbarHost(snackbarHostState)
                    }
                }) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        val reading = currentReadingBook
                        if (reading != null) {
                            ReaderScreen(
                                book = reading,
                                onBack = { currentReadingBook = null },
                                onSaveBookmark = { position ->
                                    scope.launch { repository.saveBookmark(reading.id, position) }
                                }
                            )
                        } else {
                            MainScreen(
                                downloadedBooks = downloadedBooks,
                                searchResults = searchResults,
                                searchQuery = searchQuery,
                                isSearching = isSearching,
                                hasSearched = hasSearched,
                                downloadingIds = downloadingIds,
                                onQueryChange = { searchQuery = it },
                                onSearchSubmit = {
                                    scope.launch {
                                        isSearching = true
                                        hasSearched = true
                                        searchResults = repository.search(searchQuery)
                                        isSearching = false
                                    }
                                },
                                onDownloadClick = { book ->
                                    if (!downloadingIds.contains(book.id)) {
                                        scope.launch {
                                            downloadingIds = downloadingIds + book.id
                                            val updated = repository.downloadBook(book)
                                            downloadingIds = downloadingIds - book.id

                                            if (updated != null) {
                                                searchResults = searchResults.map { if (it.id == book.id) updated else it }
                                                snackbarHostState.showSnackbar("『${book.title}』をオフラインに保存しました")
                                            } else {
                                                snackbarHostState.showSnackbar("ダウンロードに失敗しました")
                                            }
                                        }
                                    }
                                },
                                onDeleteClick = { book ->
                                    scope.launch {
                                        val updated = repository.deleteBook(book)
                                        searchResults = searchResults.map { if (it.id == book.id) updated else it }
                                        snackbarHostState.showSnackbar("『${book.title}』を削除しました")
                                    }
                                },
                                onBookClick = { book ->
                                    scope.launch {
                                        currentReadingBook = repository.getBook(book.id) ?: book
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
