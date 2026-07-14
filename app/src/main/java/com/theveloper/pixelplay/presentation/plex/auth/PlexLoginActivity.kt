package com.theveloper.pixelplay.presentation.plex.auth

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.plex.model.PlexHomeUser
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme
import dagger.hilt.android.AndroidEntryPoint
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@AndroidEntryPoint
class PlexLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelPlayTheme {
                PlexLoginScreen(onClose = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlexLoginScreen(
    viewModel: PlexLoginViewModel = hiltViewModel(),
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val loginState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var showManualForm by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var pinPromptUser by remember { mutableStateOf<PlexHomeUser?>(null) }

    // Open the browser for PIN approval when the flow asks for it.
    LaunchedEffect(Unit) {
        viewModel.openUrlEvents.collect { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
        }
    }

    LaunchedEffect(loginState) {
        when (val state = loginState) {
            is PlexLoginState.Success -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.auth_welcome_toast, state.username),
                    Toast.LENGTH_SHORT
                ).show()
                onClose()
            }
            is PlexLoginState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearError()
            }
            else -> Unit
        }
    }

    val isLoading = loginState is PlexLoginState.Loading
    val inputShape = AbsoluteSmoothCornerShape(18.dp, 60)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.auth_plex_title),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    FilledIconButton(
                        modifier = Modifier.padding(start = 6.dp),
                        onClick = onClose,
                        enabled = !isLoading,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Plex Icon
            androidx.compose.material3.Surface(
                shape = CircleShape,
                color = Color(0xFFE5A00D),
                modifier = Modifier.size(64.dp),
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plex),
                        contentDescription = stringResource(R.string.auth_plex_logo_cd),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.auth_plex_title),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.auth_plex_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (val state = loginState) {
                is PlexLoginState.AwaitingApproval -> {
                    AwaitingApprovalCard(
                        code = state.code,
                        onOpenBrowser = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, state.authUrl.toUri())
                                )
                            }
                        },
                        onCancel = { viewModel.cancelWebAuth() }
                    )
                }

                is PlexLoginState.SelectHomeUser -> {
                    PickerCard(title = stringResource(R.string.auth_plex_pick_user)) {
                        state.users.forEach { user ->
                            ListItem(
                                headlineContent = {
                                    Text(user.title, fontFamily = GoogleSansRounded)
                                },
                                supportingContent = {
                                    val label = when {
                                        user.isAdmin -> stringResource(R.string.auth_plex_user_admin)
                                        user.isProtected -> stringResource(R.string.auth_plex_user_protected)
                                        else -> null
                                    }
                                    label?.let { Text(it, fontFamily = GoogleSansRounded) }
                                },
                                leadingContent = {
                                    Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (user.isProtected && !user.isAdmin) {
                                            pinPromptUser = user
                                        } else {
                                            viewModel.selectHomeUser(user)
                                        }
                                    },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                is PlexLoginState.SelectServer -> {
                    PickerCard(title = stringResource(R.string.auth_plex_pick_server)) {
                        state.servers.forEach { server ->
                            ListItem(
                                headlineContent = {
                                    Text(server.name, fontFamily = GoogleSansRounded)
                                },
                                supportingContent = {
                                    Text(
                                        server.uri,
                                        fontFamily = GoogleSansRounded,
                                        fontSize = 12.sp
                                    )
                                },
                                leadingContent = {
                                    Icon(Icons.Rounded.Dns, contentDescription = null)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { viewModel.selectServer(server) },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                else -> {
                    // ── Default: web auth first ──
                    Button(
                        onClick = { viewModel.startWebAuth() },
                        enabled = !isLoading,
                        shape = AbsoluteSmoothCornerShape(18.dp, 60),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        if (isLoading && !showManualForm) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.OpenInBrowser,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.auth_plex_web_signin),
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.auth_plex_web_hint),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = { showManualForm = !showManualForm }) {
                        Text(
                            text = if (showManualForm) {
                                stringResource(R.string.auth_plex_hide_manual)
                            } else {
                                stringResource(R.string.auth_plex_show_manual)
                            },
                            fontFamily = GoogleSansRounded
                        )
                    }

                    if (showManualForm) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AbsoluteSmoothCornerShape(28.dp, 60),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = stringResource(R.string.auth_details_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = stringResource(R.string.auth_plex_connect_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = GoogleSansRounded,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                PlexLoginField(
                                    value = serverUrl,
                                    onValueChange = { serverUrl = it },
                                    label = stringResource(R.string.auth_server_url_label),
                                    placeholder = stringResource(R.string.auth_plex_url_placeholder),
                                    supportingText = stringResource(R.string.auth_plex_url_hint),
                                    leadingIcon = Icons.Rounded.Dns,
                                    enabled = !isLoading,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                    ),
                                    shape = inputShape
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                PlexLoginField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = stringResource(R.string.auth_username_label),
                                    placeholder = stringResource(R.string.auth_username_placeholder),
                                    supportingText = stringResource(R.string.auth_plex_username_hint),
                                    leadingIcon = Icons.Rounded.Person,
                                    enabled = !isLoading,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Next
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                    ),
                                    shape = inputShape
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                PlexLoginField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = stringResource(R.string.auth_password_label),
                                    placeholder = stringResource(R.string.auth_password_placeholder),
                                    supportingText = stringResource(R.string.auth_plex_password_hint),
                                    leadingIcon = Icons.Rounded.Lock,
                                    enabled = !isLoading,
                                    visualTransformation = if (passwordVisible) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            if (serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                                viewModel.login(serverUrl, username, password)
                                            }
                                        }
                                    ),
                                    trailingContent = {
                                        IconButton(
                                            onClick = { passwordVisible = !passwordVisible },
                                            enabled = !isLoading
                                        ) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                contentDescription = stringResource(
                                                    if (passwordVisible) R.string.auth_hide_pwd_action else R.string.auth_show_pwd_action
                                                )
                                            )
                                        }
                                    },
                                    shape = inputShape
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                FilledTonalButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        viewModel.login(serverUrl, username, password)
                                    },
                                    enabled = !isLoading && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                                    shape = inputShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.auth_connecting_msg), fontFamily = GoogleSansRounded)
                                    } else {
                                        Text(
                                            stringResource(R.string.auth_connect_action),
                                            fontFamily = GoogleSansRounded,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.auth_plex_footer_msg),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = GoogleSansRounded,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // PIN entry for protected home users.
    pinPromptUser?.let { user ->
        var pin by remember(user.uuid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinPromptUser = null },
            title = {
                Text(
                    stringResource(R.string.auth_plex_pin_title, user.title),
                    fontFamily = GoogleSansRounded
                )
            },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { newPin ->
                        if (newPin.length <= 4 && newPin.all(Char::isDigit)) pin = newPin
                    },
                    label = { Text(stringResource(R.string.auth_plex_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pin.length == 4,
                    onClick = {
                        viewModel.selectHomeUser(user, pin)
                        pinPromptUser = null
                    }
                ) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pinPromptUser = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun AwaitingApprovalCard(
    code: String,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(28.dp, 60),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.auth_plex_waiting_approval),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.auth_plex_waiting_hint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onOpenBrowser) {
                    Icon(
                        Icons.Rounded.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auth_plex_reopen_browser), fontFamily = GoogleSansRounded)
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_cancel), fontFamily = GoogleSansRounded)
                }
            }
        }
    }
}

@Composable
private fun PickerCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AbsoluteSmoothCornerShape(28.dp, 60),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PlexLoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supportingText: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingContent: @Composable (() -> Unit)? = null,
    shape: AbsoluteSmoothCornerShape
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = shape,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = GoogleSansRounded
        ),
        label = { Text(text = label, fontFamily = GoogleSansRounded) },
        placeholder = { Text(text = placeholder, fontFamily = GoogleSansRounded) },
        supportingText = { Text(text = supportingText, fontFamily = GoogleSansRounded) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingIcon = trailingContent,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent
        )
    )
}
