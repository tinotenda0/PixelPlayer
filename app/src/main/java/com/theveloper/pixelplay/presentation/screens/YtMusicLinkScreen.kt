package com.theveloper.pixelplay.presentation.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.presentation.viewmodel.YtMusicLinkViewModel
import com.theveloper.pixelplay.presentation.viewmodel.YtMusicLinkViewModel.Phase
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.PixelPlayStatusBarStyle
import com.theveloper.pixelplay.ui.theme.ShapeCache

/**
 * Links a YouTube Music account via the OAuth device-code flow: the server issues a short code,
 * the user approves it at google.com/device, and we poll until it's accepted. No password is ever
 * entered here. Linking affects recommendations/library only — audio stays anonymous.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YtMusicLinkScreen(
    onBack: () -> Unit,
    viewModel: YtMusicLinkViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    PixelPlayStatusBarStyle(color = MaterialTheme.colorScheme.surface)

    // In-app Google sign-in: the user signs in on Google's own page, we read the resulting
    // cookies and hand them to the gateway. No password ever passes through the app.
    if (ui.phase == Phase.SIGNING_IN) {
        BackHandler { viewModel.cancelSignIn() }
        GoogleSignInWebView(
            onCookiesCaptured = { viewModel.submitCookies(it) },
            onCancel = { viewModel.cancelSignIn() }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "YouTube Music",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = GoogleSansRounded, fontSize = 32.sp
                ),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (ui.phase) {
                    Phase.LINKED ->
                        "Linked. Your home, library and likes now come from your own account, " +
                            "and what you play here teaches its recommendations."
                    Phase.UNCONFIGURED ->
                        "Linking isn't set up on the server yet. Add a Google OAuth client " +
                            "(TV & limited input) to the gateway first."
                    Phase.AWAITING_APPROVAL ->
                        "Open the link below and enter this code to approve access."
                    Phase.ERROR -> "Something went wrong starting the link. Try again."
                    else ->
                        "Link your account to get your real YouTube Music recommendations, " +
                            "playlists and likes. Only listening data is shared — downloads " +
                            "stay anonymous."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (ui.message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = ui.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when (ui.phase) {
                Phase.LOADING -> LoadingIndicator(modifier = Modifier.size(48.dp))
                Phase.VERIFYING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Verifying with YouTube…", style = MaterialTheme.typography.bodyLarge)
                }
                Phase.AWAITING_APPROVAL -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = ShapeCache.smooth24,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = ui.userCode,
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = GoogleSansRounded
                                ),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp)
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Waiting for approval…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { copyToClipboard(context, ui.userCode) },
                                shape = ShapeCache.smoothPill
                            ) { Text("Copy code") }
                            Button(
                                onClick = { openUrl(context, ui.verificationUrl) },
                                shape = ShapeCache.smoothPill
                            ) { Text("Open page") }
                        }
                    }
                }
                Phase.LINKED -> {
                    Surface(
                        shape = ShapeCache.smooth24,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Account linked",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
                        )
                    }
                }
                else -> Unit
            }
        }

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (ui.phase) {
                Phase.NOT_LINKED, Phase.ERROR -> Button(
                    onClick = { viewModel.beginSignIn() },
                    enabled = !ui.busy,
                    shape = ShapeCache.smoothPill,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign in with Google", style = MaterialTheme.typography.titleMedium) }

                Phase.AWAITING_APPROVAL -> TextButton(onClick = { viewModel.cancelLink() }) {
                    Text("Cancel")
                }

                Phase.LINKED -> OutlinedButton(
                    onClick = { viewModel.unlink() },
                    enabled = !ui.busy,
                    shape = ShapeCache.smoothPill,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Unlink", style = MaterialTheme.typography.titleMedium) }

                else -> Unit
            }
            TextButton(
                onClick = onBack,
                modifier = Modifier.padding(bottom = 20.dp)
            ) { Text("Done") }
        }
    }
}

/**
 * Google's real sign-in page in a WebView. On success we read the music.youtube.com cookies from
 * the native cookie store and hand them to the gateway.
 *
 * The desktop user-agent matters: Google refuses to sign in when it recognises an embedded
 * WebView (the "this browser may not be secure" wall), so we present as desktop Chrome.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleSignInWebView(
    onCookiesCaptured: (String) -> Unit,
    onCancel: () -> Unit
) {
    var captured by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                text = "Sign in to YouTube Music",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                // Manual fallback if auto-detection doesn't fire.
                val cookie = CookieManager.getInstance().getCookie(YTM_URL).orEmpty()
                if (cookie.isNotBlank() && !captured) {
                    captured = true
                    onCookiesCaptured(cookie)
                }
            }) { Text("Done") }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                WebView(ctx).apply {
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DESKTOP_USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (captured) return
                            val cookie = cookieManager.getCookie(YTM_URL).orEmpty()
                            // SAPISID only appears once the account session is established; the
                            // server re-verifies anyway, so a premature grab just gets rejected.
                            if (cookie.contains("SAPISID")) {
                                captured = true
                                cookieManager.flush()
                                onCookiesCaptured(cookie)
                            }
                        }
                    }
                    loadUrl(SIGN_IN_URL)
                }
            }
        )
    }
}

private const val YTM_URL = "https://music.youtube.com"
private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com%2F"
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("YouTube Music code", text))
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
