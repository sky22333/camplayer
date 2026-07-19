package com.zhenshi.capture.screens.player

import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView
import com.zhenshi.capture.R
import com.zhenshi.capture.media.ConnectionState
import com.zhenshi.capture.media.SignalSource
import com.zhenshi.capture.media.SourceKeys
import com.zhenshi.capture.screens.components.AspectRatioVideoBox
import com.zhenshi.capture.screens.components.LocalAppSnackbar
import com.zhenshi.capture.screens.push.PlayerPushPanel
import com.zhenshi.capture.screens.push.PushViewModel
import com.zhenshi.capture.util.displayName
import com.zhenshi.capture.util.enterImmersiveLandscape
import com.zhenshi.capture.util.restoreDarkSystemBars
import com.zhenshi.capture.util.videoAspectRatio
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private enum class PlayerOrientation {
    Portrait,
    Landscape,
}

@Composable
fun PlayerScreen(
    sourceKey: String,
    profileKey: String,
    onBack: () -> Unit,
    onManagePushTargets: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val networkError by viewModel.networkError.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var showPushOverlay by remember { mutableStateOf(false) }
    var orientation by remember { mutableStateOf(PlayerOrientation.Portrait) }
    val leaving = remember { AtomicBoolean(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalActivity.current as ComponentActivity
    val pushViewModel: PushViewModel = hiltViewModel(viewModelStoreOwner = activity)
    val pushUiState by pushViewModel.playerPushUiState.collectAsStateWithLifecycle()
    val isPushStreaming = pushUiState.push.isStreaming
    val isLandscape = orientation == PlayerOrientation.Landscape
    val snackbar = LocalAppSnackbar.current
    val scheme = MaterialTheme.colorScheme

    val profile = state.profile
    val navProfile = remember(profileKey) { SourceKeys.decodeProfile(profileKey) }
    val aspectRatio = remember(profile, navProfile) {
        profile?.let { videoAspectRatio(it.width, it.height) }
            ?: navProfile?.let { videoAspectRatio(it.width, it.height) }
            ?: (16f / 9f)
    }

    LaunchedEffect(sourceKey, profileKey) {
        viewModel.start(sourceKey, profileKey)
    }

    LaunchedEffect(state.userMessage) {
        val message = state.userMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeUserMessage()
    }

    LaunchedEffect(controlsVisible, showPushOverlay) {
        if (controlsVisible && !showPushOverlay) {
            delay(2_800)
            controlsVisible = false
        }
    }

    // 进后台：先停推流再暂停采集
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    pushViewModel.onAppBackground()
                    viewModel.pauseForAppBackground()
                }
                Lifecycle.Event.ON_START -> {
                    viewModel.resumeFromAppBackground()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(orientation) {
        if (leaving.get()) return@LaunchedEffect
        activity.requestedOrientation = when (orientation) {
            PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    LaunchedEffect(isLandscape) {
        if (leaving.get()) return@LaunchedEffect
        if (isLandscape) {
            activity.enterImmersiveLandscape()
        } else {
            activity.restoreDarkSystemBars()
        }
    }

    /**
     * 离开唯一路径：Activity 级 await 停推流（含 Connecting）→ leavePreview/pop。
     * 方向/系统栏/常亮在 onDispose 恢复。
     */
    fun leavePlayback(onNavigate: (() -> Unit)?) {
        if (!leaving.compareAndSet(false, true)) {
            onNavigate?.invoke()
            return
        }
        showPushOverlay = false
        pushViewModel.stopThenLeave {
            viewModel.leavePreview(onNavigate ?: {})
        }
    }

    fun handlePlayerBack() {
        when {
            showPushOverlay -> showPushOverlay = false
            orientation == PlayerOrientation.Landscape -> {
                orientation = PlayerOrientation.Portrait
                controlsVisible = true
            }
            else -> leavePlayback(onBack)
        }
    }

    BackHandler(onBack = ::handlePlayerBack)

    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (!leaving.get()) {
                leavePlayback(onNavigate = null)
            }
            activity.restoreDarkSystemBars()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                if (!showPushOverlay) controlsVisible = !controlsVisible
            },
    ) {
        when (state.source) {
            is SignalSource.RtmpUrl, is SignalSource.RtspUrl -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { playerView ->
                        playerView.player = viewModel.exoPlayer()
                    },
                )
            }
            is SignalSource.UsbDevice, null -> {
                AspectRatioVideoBox(aspectRatio = aspectRatio) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            AspectRatioSurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        viewModel.onSurfaceChanged(this@apply, holder, width, height)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        viewModel.onSurfaceDestroyed()
                                    }
                                })
                            }
                        },
                    )
                }
            }
        }

        if (state.connection is ConnectionState.Connecting) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }

        val errorText = (state.connection as? ConnectionState.Error)?.message ?: networkError
        if (errorText != null) {
            Text(
                text = errorText,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(
                        if (isLandscape) {
                            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                        } else {
                            Modifier
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(start = 20.dp, end = 20.dp, bottom = 56.dp)
                        },
                    ),
            )
        }

        state.measuredLatencyMs?.takeIf { state.connection is ConnectionState.Ready }?.let { ms ->
            Text(
                text = "${ms}ms",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 10.dp, end = 14.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::handlePlayerBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.source?.displayName() ?: "—",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    profile?.let {
                        Text(
                            text = it.label,
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        orientation = when (orientation) {
                            PlayerOrientation.Portrait -> PlayerOrientation.Landscape
                            PlayerOrientation.Landscape -> PlayerOrientation.Portrait
                        }
                        controlsVisible = true
                    },
                ) {
                    Icon(
                        imageVector = when (orientation) {
                            PlayerOrientation.Portrait -> Icons.Outlined.CropLandscape
                            PlayerOrientation.Landscape -> Icons.Outlined.CropPortrait
                        },
                        contentDescription = stringResource(R.string.cd_rotate_screen),
                        tint = Color.White,
                    )
                }
                if (state.source is SignalSource.UsbDevice) {
                    IconButton(onClick = {
                        showPushOverlay = true
                        controlsVisible = true
                    }) {
                        Icon(
                            Icons.Outlined.Upload,
                            contentDescription = stringResource(R.string.cd_player_open_push),
                            tint = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }

        if (isPushStreaming) {
            Text(
                text = stringResource(R.string.push_running),
                color = scheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .then(
                        if (isLandscape) Modifier.padding(16.dp)
                        else Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(16.dp),
                    ),
            )
        }

        AnimatedVisibility(
            visible = showPushOverlay,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 5 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 5 }),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { showPushOverlay = false },
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = Color(0xFF161616),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    PlayerPushPanel(
                        viewModel = pushViewModel,
                        onManageTargets = {
                            leavePlayback(onManagePushTargets)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
