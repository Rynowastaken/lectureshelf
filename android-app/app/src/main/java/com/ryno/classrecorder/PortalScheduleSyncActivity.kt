package com.ryno.classrecorder

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ryno.classrecorder.core.PortalScheduleEndpoint
import com.ryno.classrecorder.ui.AppearancePreferences
import com.ryno.classrecorder.ui.components.DshBackdrop
import com.ryno.classrecorder.ui.components.DshIcons
import com.ryno.classrecorder.ui.components.DshSecondaryButton
import com.ryno.classrecorder.ui.components.GlassIconButton
import com.ryno.classrecorder.ui.components.GlassSurface
import com.ryno.classrecorder.ui.components.StatusDot
import com.ryno.classrecorder.ui.theme.AppShape
import com.ryno.classrecorder.ui.theme.ClassRecorderTheme
import com.ryno.classrecorder.ui.theme.DshMotion
import com.ryno.classrecorder.ui.theme.GlassTier
import com.ryno.classrecorder.ui.theme.StatusTone
import com.ryno.classrecorder.ui.theme.tabular
import org.json.JSONObject
import org.json.JSONTokener

class PortalScheduleSyncActivity : ComponentActivity() {
    private lateinit var endpoint: PortalScheduleEndpoint
    private var webView: WebView? = null
    private var accessHost: String? = null
    private var visibleHost by mutableStateOf("")
    private var message by mutableStateOf<String?>(null)
    private var loadProgress by mutableIntStateOf(0)
    private var isLoading by mutableStateOf(true)
    private var isReadingTimetable by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = PortalScheduleEndpoint.parse(intent.getStringExtra(SOURCE_URL).orEmpty())
        if (source == null) {
            finish()
            return
        }
        endpoint = source
        accessHost = savedInstanceState?.getString(ACCESS_HOST)
        visibleHost = source.host
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(false)

        setContent {
            val (themeMode, accentId) = remember { AppearancePreferences.load(this) }
            val showingIdentityProvider = visibleHost != endpoint.host && visibleHost != accessHost

            BackHandler(enabled = showingIdentityProvider) { reloadPortal() }

            ClassRecorderTheme(themeMode = themeMode, accentId = accentId) {
                PortalSyncScreen(
                    endpoint = endpoint,
                    visibleHost = visibleHost,
                    showingIdentityProvider = showingIdentityProvider,
                    isLoading = isLoading,
                    loadProgress = loadProgress,
                    isReadingTimetable = isReadingTimetable,
                    message = message,
                    onBackToAccess = ::reloadPortal,
                    onReload = ::reloadPortal,
                    onClose = ::finish,
                    webViewFactory = { createWebView(savedInstanceState?.getBundle(WEB_STATE)) }
                )
            }
        }
    }

    private fun reloadPortal() {
        message = null
        isReadingTimetable = false
        isLoading = true
        loadProgress = 12
        webView?.loadUrl(endpoint.url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(state: Bundle?) = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadProgress = newProgress
                if (newProgress >= 100) isLoading = false
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || allowedHost(request.url.toString()) != null) return false
                message = endpoint.signInMethods
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                val host = allowedHost(url)
                if (host == null) {
                    view.stopLoading()
                    isLoading = false
                    isReadingTimetable = false
                    message = "The portal opened an unexpected address. Tap Retry to return to sign-in."
                    return
                }
                visibleHost = host
                message = null
                isLoading = true
                isReadingTimetable = endpoint.isTimetablePage(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    isLoading = false
                    isReadingTimetable = false
                    message = "Could not load $visibleHost. Check your connection and retry."
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                isLoading = false
                loadProgress = 100
                if (endpoint.isTimetablePage(url)) {
                    isReadingTimetable = true
                    readTimetable(view)
                } else {
                    isReadingTimetable = false
                }
            }
        }
        webView = this
        if (state == null || restoreState(state) == null) loadUrl(endpoint.url)
    }

    private fun allowedHost(url: String): String? {
        if (endpoint.isPortalPage(url)) return endpoint.host
        if (accessHost != null) endpoint.identityHost(url)?.let { return it }
        val host = PortalScheduleEndpoint.accessHost(url) ?: return null
        if (accessHost == null) accessHost = host
        return host.takeIf { it == accessHost }
    }

    private fun readTimetable(view: WebView) {
        val origin = JSONObject.quote(endpoint.origin)
        val path = JSONObject.quote(PortalScheduleEndpoint.TIMETABLE_PATH)
        view.evaluateJavascript("""
            (() => {
                if (location.origin !== $origin || location.pathname !== $path) return null;
                const text = document.body?.innerText;
                return typeof text === 'string' && text.length <= 131072 ? text : null;
            })()
        """.trimIndent()) { value ->
            if (isFinishing || isDestroyed || !endpoint.isTimetablePage(view.url.orEmpty())) {
                return@evaluateJavascript
            }
            val result = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
            val json = result?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json?.optJSONArray("periods") == null) {
                isReadingTimetable = false
                message = when (json?.optString("code")) {
                    "EPORTAL_LOGIN_REQUIRED" -> "Sign in to NUTC ePortal on your portal dashboard, then tap Retry."
                    else -> "The portal did not return a timetable. Check your sign-in and tap Retry."
                }
                return@evaluateJavascript
            }
            CookieManager.getInstance().flush()
            setResult(RESULT_OK, Intent().putExtra(SOURCE_URL, endpoint.url).putExtra(SCHEDULE_JSON, result))
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ACCESS_HOST, accessHost)
        webView?.let { view -> outState.putBundle(WEB_STATE, Bundle().also { view.saveState(it) }) }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        internal const val SOURCE_URL = "source_url"
        internal const val SCHEDULE_JSON = "schedule_json"
        private const val WEB_STATE = "web_state"
        private const val ACCESS_HOST = "access_host"
    }
}

@Composable
private fun PortalSyncScreen(
    endpoint: PortalScheduleEndpoint,
    visibleHost: String,
    showingIdentityProvider: Boolean,
    isLoading: Boolean,
    loadProgress: Int,
    isReadingTimetable: Boolean,
    message: String?,
    onBackToAccess: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    webViewFactory: () -> WebView
) {
    val scheme = MaterialTheme.colorScheme
    val dsh = ClassRecorderTheme.dshColors
    val reduced = ClassRecorderTheme.reducedMotion

    val statusTone = when {
        message != null -> StatusTone.Warn
        isReadingTimetable || isLoading -> StatusTone.Busy
        else -> StatusTone.Ok
    }

    val headerTitle = when {
        isReadingTimetable -> "Importing timetable…"
        showingIdentityProvider -> visibleHost
        else -> "Sync ePortal"
    }

    val headerSubtitle = when {
        isReadingTimetable -> endpoint.host
        showingIdentityProvider -> "Sign in · tap Back to switch"
        else -> visibleHost
    }

    val progressAlpha by animateFloatAsState(
        targetValue = if (isLoading && loadProgress in 1..99) 1f else 0f,
        animationSpec = DshMotion.quickTween(reduced),
        label = "webProgressAlpha"
    )

    DshBackdrop(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Floating Mist Glass Chrome header matching MainActivity / AppPage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showingIdentityProvider) {
                    GlassIconButton(
                        icon = DshIcons.ArrowBack,
                        contentDescription = "Back to sign-in options",
                        onClick = onBackToAccess,
                        tier = GlassTier.Chrome
                    )
                } else {
                    GlassIconButton(
                        icon = DshIcons.Close,
                        contentDescription = "Close ePortal sign-in",
                        onClick = onClose,
                        tier = GlassTier.Chrome
                    )
                }

                GlassSurface(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 46.dp),
                    tier = GlassTier.Chrome,
                    stadium = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatusDot(tone = statusTone, size = 8.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = headerTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = headerSubtitle,
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = dsh.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (showingIdentityProvider) {
                    GlassIconButton(
                        icon = DshIcons.Close,
                        contentDescription = "Close ePortal sign-in",
                        onClick = onClose,
                        tier = GlassTier.Chrome
                    )
                } else {
                    GlassIconButton(
                        icon = DshIcons.Refresh,
                        contentDescription = "Reload portal",
                        onClick = onReload,
                        tier = GlassTier.Chrome
                    )
                }
            }

            // Contextual error / guidance banner
            AnimatedVisibility(
                visible = message != null,
                enter = expandVertically(DshMotion.collapseTween(reduced)) + fadeIn(DshMotion.quickTween(reduced)),
                exit = shrinkVertically(DshMotion.collapseTween(reduced)) + fadeOut(DshMotion.quickTween(reduced))
            ) {
                message?.let { notice ->
                    GlassSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        tier = GlassTier.Card,
                        shape = AppShape.radiusLg,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatusDot(tone = StatusTone.Warn, size = 8.dp)
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            DshSecondaryButton(
                                label = "Retry",
                                onClick = onReload,
                                minHeight = 48.dp
                            )
                        }
                    }
                }
            }

            // Rounded, hairline-framed WebView container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
                    .clip(AppShape.radiusXl)
                    .border(
                        width = 0.5.dp,
                        color = scheme.outlineVariant.copy(alpha = 0.5f),
                        shape = AppShape.radiusXl
                    )
            ) {
                AndroidView(
                    factory = { webViewFactory() },
                    modifier = Modifier.fillMaxSize()
                )

                // Real-time WebView progress bar along top edge of frame
                LinearProgressIndicator(
                    progress = { (loadProgress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter)
                        .alpha(progressAlpha),
                    color = scheme.primary,
                    trackColor = Color.Transparent
                )

                // Calm Mist Glass handoff overlay while extracting timetable JSON
                androidx.compose.animation.AnimatedVisibility(
                    visible = isReadingTimetable,
                    enter = fadeIn(DshMotion.quickTween(reduced)),
                    exit = fadeOut(DshMotion.quickTween(reduced)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    GlassSurface(
                        modifier = Modifier.fillMaxSize(),
                        tier = GlassTier.Sheet,
                        shape = AppShape.radiusXl
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = scheme.primary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Importing timetable…",
                                style = MaterialTheme.typography.titleMedium,
                                color = scheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = endpoint.host,
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = dsh.textTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}
