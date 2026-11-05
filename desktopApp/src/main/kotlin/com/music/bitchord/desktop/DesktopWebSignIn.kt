package com.music.bitchord.desktop

import java.net.CookieHandler
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.web.WebView
import javafx.stage.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** In-app Spotify authentication; the retired YouTube Music WebView has been removed. */
internal object DesktopWebSignIn {

    enum class Service(val title: String, val startUrl: String, val cookieUrl: String) {
        SPOTIFY(
            "Sign in to Spotify",
            "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F",
            "https://open.spotify.com/",
        ),
    }

    /** Whether a sign-in window can be shown at all: it needs the JavaFX toolkit. */
    val available: Boolean by lazy {
        // Spotify's JavaFX sign-in needs only the toolkit, not the retired
        // BotGuard/PoToken player component that previously initialized it.
        runCatching {
            val started = CountDownLatch(1)
            try {
                Platform.startup { started.countDown() }
            } catch (_: IllegalStateException) {
                started.countDown()
            }
            Platform.setImplicitExit(false)
            started.await()
            true
        }.getOrDefault(false)
    }

    /** The `sp_dc` cookie's value, taken as soon as Spotify sets it. */
    suspend fun captureSpotify(): String = capture(Service.SPOTIFY)

    private suspend fun capture(service: Service): String {
        check(available) { "This desktop cannot show a sign-in window (JavaFX did not start)." }
        return suspendCancellableCoroutine { continuation ->
            Platform.runLater {
                val window = SignInWindow(service) { result ->
                    if (continuation.isActive) continuation.resumeWith(result)
                }
                continuation.invokeOnCancellation { Platform.runLater(window::cancel) }
                window.show()
            }
        }
    }

    private const val SPOTIFY_COOKIE = "sp_dc"

    /** The cookies [service] would send, as a `Cookie` header value. */
    private fun cookies(service: Service): String = runCatching {
        CookieHandler.getDefault().get(URI(service.cookieUrl), emptyMap())["Cookie"].orEmpty().joinToString("; ")
    }.getOrDefault("")

    private fun spotifySession(): String? = cookies(Service.SPOTIFY).split(';')
        .map(String::trim)
        .firstOrNull { it.substringBefore('=') == SPOTIFY_COOKIE }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)

    /** One sign-in: a window that resolves [finish] once, with the session or a cancellation. */
    private class SignInWindow(
        private val service: Service,
        private val finish: (Result<String>) -> Unit,
    ) {
        private val stage = Stage()
        private val root = BorderPane()
        // The page's host, since the window has no address bar to show it.
        private val where = Label().apply { style = "-fx-text-fill: #aaaaaa;" }
        private var view: WebView? = null
        private var storage: Path? = null
        private var done = false

        init {
            stage.title = service.title
            val startOver = Button("Start over").apply { setOnAction { load() } }
            val bar = HBox(8.0, startOver, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, where)
            bar.padding = Insets(8.0)
            root.bottom = bar
            stage.scene = Scene(root, 480.0, 760.0)
            // Closing the window is the listener cancelling.
            stage.setOnHidden { cancel() }
            load()
        }

        fun show() = stage.show()

        fun cancel() = complete(Result.failure(CancellationException("sign-in window closed")))

        /**
         * A clean start: an empty cookie jar and a new WebView with storage of its own. Clearing only
         * the cookies is not enough to change account — Spotify also remembers the login in web
         * storage — and starting over is how the listener picks another Google, Facebook or Apple
         * account.
         */
        private fun load() {
            discard()
            DesktopWebCookies.reset()
            val folder = Files.createTempDirectory("bitchord-sign-in-").also { storage = it }
            val next = WebView().also { view = it }
            val engine = next.engine
            engine.userDataDirectory = folder.toFile()
            engine.locationProperty().addListener { _, _, url -> where.text = hostOf(url) }
            engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED && view === next) onPage(engine.location.orEmpty())
            }
            root.center = next
            engine.load(service.startUrl)
        }

        private fun onPage(url: String) {
            if (url.startsWith("https://open.spotify.com")) {
                spotifySession()?.let { complete(Result.success(it)) }
            }
        }

        private fun complete(result: Result<String>) {
            if (done) return
            done = true
            if (result.isSuccess) DesktopTrackLog.log("sign-in: ${service.name} session captured in the sign-in window")
            stage.close()
            discard()
            // BitChord keeps its own copy of the session; the window's goes with it.
            DesktopWebCookies.reset()
            finish(result)
        }

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

        /** Stops the current page and deletes its storage. */
        private fun discard() {
            view?.engine?.load("about:blank")
            view = null
            storage?.toFile()?.let { folder ->
                if (!folder.deleteRecursively()) {
                    // The page may still hold its files open for a moment. Retry as the app exits;
                    // a hard kill still leaves the folder behind.
                    DesktopTrackLog.log("sign-in: could not delete ${folder.name} yet; removing it at exit")
                    Runtime.getRuntime().addShutdownHook(Thread { folder.deleteRecursively() })
                }
            }
            storage = null
        }
    }
}

/**
 * The cookie jar every JavaFX WebView in the app uses, installed before the first one exists.
 *
 * JavaFX's HTTP2Loader builds one HttpClient with whatever [CookieHandler.getDefault] is at its first
 * request and keeps it, so replacing the default afterwards changes nothing for the WebView. This
 * wrapper stays the default and [reset] swaps what is inside it. The jar inside is JavaFX's own:
 * `java.net.CookieManager` drops cookies Spotify's login depends on.
 */
internal object DesktopWebCookies : CookieHandler() {
    @Volatile
    private var jar: CookieHandler = com.sun.webkit.network.CookieManager()

    fun install() = setDefault(this)

    fun reset() {
        jar = com.sun.webkit.network.CookieManager()
    }

    override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> =
        jar.get(uri, requestHeaders)

    override fun put(uri: URI, responseHeaders: Map<String, List<String>>) = jar.put(uri, responseHeaders)
}
