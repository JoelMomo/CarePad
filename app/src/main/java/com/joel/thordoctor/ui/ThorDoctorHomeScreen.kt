package com.joel.thordoctor.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joel.thordoctor.DiagnosticStorage
import com.joel.thordoctor.GameLibraryStorage
import com.joel.thordoctor.MonitorError
import com.joel.thordoctor.MonitorState
import com.joel.thordoctor.R
import com.joel.thordoctor.SessionMonitorService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThorDoctorHomeScreen(
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val openSettings = rememberCozyClick(onOpenSettings)

    var diagnosticAvailable by remember { mutableStateOf(false) }
    var lastDiagnosticFingerprint by remember { mutableStateOf("") }
    var lastSessionFingerprint by remember { mutableStateOf("") }
    var diagnosticSummary by remember { mutableStateOf<DiagnosticSummary?>(null) }
    var sessionSummary by remember { mutableStateOf<SessionSummary?>(null) }
    var liveMetrics by remember { mutableStateOf<LiveMetrics?>(null) }

    var monitorState by remember {
        mutableStateOf(SessionMonitorService.stateForUi(context))
    }
    var currentEmulatorName by remember {
        mutableStateOf(SessionMonitorService.emulatorNameForUi(context))
    }
    var remainingSeconds by remember {
        mutableStateOf(SessionMonitorService.currentRemainingSeconds)
    }
    var monitorError by remember {
        mutableStateOf(SessionMonitorService.currentError)
    }
    var monitoring by remember {
        mutableStateOf(SessionMonitorService.isActiveForUi(context))
    }
    var sessionElapsedSeconds by remember {
        mutableStateOf(SessionMonitorService.elapsedSecondsForUi(context))
    }

    fun startDiagnostic() {
        val intent = Intent(
            context,
            SessionMonitorService::class.java
        ).apply {
            action = SessionMonitorService.ACTION_START
        }

        ContextCompat.startForegroundService(context, intent)
        monitoring = true
        monitorState = MonitorState.WAITING_EMULATOR
        currentEmulatorName = null
        remainingSeconds = 0L
        monitorError = null
        sessionElapsedSeconds = 0L
        liveMetrics = null
    }

    fun stopDiagnostic() {
        val intent = Intent(
            context,
            SessionMonitorService::class.java
        ).apply {
            action = SessionMonitorService.ACTION_STOP
        }

        context.startService(intent)
        monitorState = MonitorState.FINISHING
        remainingSeconds = 0L
    }

    LaunchedEffect(Unit) {
        while (true) {
            val stateNow = SessionMonitorService.stateForUi(context)
            val activeNow = SessionMonitorService.isActiveForUi(context)
            val emulatorNow = SessionMonitorService.emulatorNameForUi(context)

            monitorState = stateNow
            currentEmulatorName = emulatorNow
            remainingSeconds = SessionMonitorService.currentRemainingSeconds
            monitorError = SessionMonitorService.currentError
            monitoring = activeNow
            sessionElapsedSeconds = SessionMonitorService.elapsedSecondsForUi(context)

            liveMetrics = if (
                activeNow &&
                emulatorNow != null &&
                (stateNow == MonitorState.MONITORING || stateNow == MonitorState.FINISHING)
            ) {
                try {
                    readLiveMetrics(context)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            val sessionInfo = DiagnosticStorage.documentInfo(
                context,
                DiagnosticStorage.SESSION_FILENAME
            )

            if (sessionInfo != null) {
                val fingerprint = "${sessionInfo.lastModified}:${sessionInfo.sizeBytes}"
                if (fingerprint != lastSessionFingerprint) {
                    lastSessionFingerprint = fingerprint
                    sessionSummary = try {
                        readSessionSummary(context)
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                lastSessionFingerprint = ""
                sessionSummary = null
            }

            val diagnosticInfo = DiagnosticStorage.documentInfo(
                context,
                DiagnosticStorage.DIAGNOSTIC_FILENAME
            )
            diagnosticAvailable = diagnosticInfo != null

            if (diagnosticInfo != null) {
                val fingerprint = "${diagnosticInfo.lastModified}:${diagnosticInfo.sizeBytes}"
                if (fingerprint != lastDiagnosticFingerprint) {
                    lastDiagnosticFingerprint = fingerprint
                    diagnosticSummary = try {
                        readSummary(context)
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                lastDiagnosticFingerprint = ""
                diagnosticSummary = null
            }

            val cachedGameCount = GameLibraryStorage.cachedGameCount(context)
            diagnosticSummary = diagnosticSummary?.let { summary ->
                if (summary.gameCount == cachedGameCount) {
                    summary
                } else {
                    summary.copy(gameCount = cachedGameCount)
                }
            }

            delay(1_000)
        }
    }

    val showingCompletion =
        !monitoring &&
            monitorState == MonitorState.COMPLETED &&
            diagnosticAvailable &&
            sessionSummary != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        IconButton(
                            onClick = openSettings,
                            enabled = !monitoring
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.settings),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 10.dp,
                bottom = 30.dp
            ),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            item {
                AnimatedContent(
                    targetState = showingCompletion,
                    transitionSpec = {
                        (
                            fadeIn(tween(280)) +
                                slideInVertically(tween(320)) { height -> height / 10 } +
                                scaleIn(
                                    animationSpec = tween(320),
                                    initialScale = 0.985f
                                )
                            ) togetherWith
                            (
                                fadeOut(tween(170)) +
                                    slideOutVertically(tween(220)) { height -> -height / 12 } +
                                    scaleOut(
                                        animationSpec = tween(220),
                                        targetScale = 0.99f
                                    )
                                )
                    },
                    label = "diagnosticCompletionTransition"
                ) { completed ->
                    if (completed) {
                        sessionSummary?.let { session ->
                            CompletionCard(
                                session = session,
                                onShare = { shareDiagnostic(context) },
                                onNewDiagnostic = { startDiagnostic() }
                            )
                        }
                    } else {
                        DiagnosticHeroCard(
                            monitoring = monitoring,
                            state = monitorState,
                            emulatorName = currentEmulatorName,
                            remainingSeconds = remainingSeconds,
                            elapsedSeconds = sessionElapsedSeconds,
                            error = monitorError,
                            liveMetrics = liveMetrics,
                            onStart = { startDiagnostic() },
                            onStop = { stopDiagnostic() }
                        )
                    }
                }
            }

            if (!showingCompletion && diagnosticAvailable && !monitoring) {
                item {
                    ShareDiagnosticButton(
                        onClick = { shareDiagnostic(context) }
                    )
                }
            }
            if (!monitoring) {
                if (!showingCompletion) {
                    sessionSummary?.let { session ->
                        item {
                            CozySectionTitle(
                                icon = Icons.Rounded.SportsEsports,
                                title = stringResource(R.string.last_session),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                        item {
                            SessionCard(session)
                        }
                    }
                }

                diagnosticSummary?.let { result ->
                    item {
                        CozySectionTitle(
                            icon = Icons.Rounded.Smartphone,
                            title = stringResource(R.string.device),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    item {
                        DeviceCard(result)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticHeroCard(
    monitoring: Boolean,
    state: MonitorState,
    emulatorName: String?,
    remainingSeconds: Long,
    elapsedSeconds: Long,
    error: MonitorError?,
    liveMetrics: LiveMetrics?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val statusText = monitorStatusText(
        state = state,
        emulatorName = emulatorName,
        remainingSeconds = remainingSeconds,
        error = error
    )

    val accentColor = when (state) {
        MonitorState.ERROR -> MaterialTheme.colorScheme.error
        MonitorState.COMPLETED -> MaterialTheme.colorScheme.secondary
        MonitorState.GENERATING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    val heroColor = when (state) {
        MonitorState.ERROR -> MaterialTheme.colorScheme.errorContainer
        MonitorState.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        MonitorState.GENERATING -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val animatedHeroColor by animateColorAsState(
        targetValue = heroColor,
        animationSpec = tween(380),
        label = "diagnosticHeroColor"
    )

    val showTimer = monitoring &&
        (state == MonitorState.MONITORING || state == MonitorState.FINISHING) &&
        emulatorName != null

    val infiniteTransition = rememberInfiniteTransition(label = "diagnosticPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_050),
            repeatMode = RepeatMode.Reverse
        ),
        label = "diagnosticPulseAlpha"
    )

    val startPress = rememberCozyPressState()
    val stopPress = rememberCozyPressState()

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = animatedHeroColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 14.dp)
                    .size(68.dp)
                    .alpha(0.30f),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {}

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(9.dp)
                                .alpha(if (monitoring) pulseAlpha else 1f),
                            shape = CircleShape,
                            color = accentColor
                        ) {}
                        Text(
                            text = stringResource(R.string.status_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (state != MonitorState.IDLE) {
                    DiagnosticJourneyCarousel(state)
                }

                AnimatedContent(
                    targetState = showTimer,
                    transitionSpec = {
                        (
                            fadeIn(tween(220)) +
                                slideInVertically(tween(250)) { height -> height / 10 }
                            ) togetherWith
                            (
                                fadeOut(tween(150)) +
                                    slideOutVertically(tween(190)) { height -> -height / 12 }
                                )
                    },
                    label = "diagnosticBodyTransition"
                ) { timerVisible ->
                    if (timerVisible) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = formatLiveDuration(elapsedSeconds),
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )

                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SportsEsports,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp),
                                            tint = accentColor
                                        )
                                        Text(
                                            text = emulatorName.orEmpty(),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                AnimatedContent(
                                    targetState = statusText,
                                    transitionSpec = {
                                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                                    },
                                    label = "diagnosticStatusText"
                                ) { animatedStatus ->
                                    Text(
                                        text = animatedStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            LiveMetricsGrid(liveMetrics)
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                modifier = Modifier.size(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = heroIconForState(state),
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(25.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                AnimatedContent(
                                    targetState = statusText,
                                    transitionSpec = {
                                        (
                                            fadeIn(tween(190)) +
                                                slideInVertically(tween(210)) { height -> height / 4 }
                                            ) togetherWith fadeOut(tween(130))
                                    },
                                    label = "diagnosticHeadlineTransition"
                                ) { animatedStatus ->
                                    Text(
                                        text = animatedStatus,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                if (!monitoring && state == MonitorState.IDLE) {
                                    Text(
                                        text = stringResource(R.string.app_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                when {
                    !monitoring -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .cozyPress(startPress.scale),
                            interactionSource = startPress.interactionSource,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.start_diagnostic),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    state != MonitorState.GENERATING -> {
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .cozyPress(stopPress.scale),
                            interactionSource = stopPress.interactionSource,
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.stop_diagnostic),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticJourneyCarousel(state: MonitorState) {
    val currentStep = when (state) {
        MonitorState.WAITING_EMULATOR -> 0
        MonitorState.MONITORING,
        MonitorState.FINISHING -> 1
        MonitorState.GENERATING,
        MonitorState.COMPLETED,
        MonitorState.ERROR -> 2
        MonitorState.IDLE -> -1
    }

    val completed = state == MonitorState.COMPLETED

    val titles = if (completed) {
        listOf(
            stringResource(R.string.journey_completed_detected),
            stringResource(R.string.journey_completed_analyzed),
            stringResource(R.string.journey_completed_ready)
        )
    } else {
        listOf(
            stringResource(R.string.journey_waiting),
            stringResource(R.string.journey_monitoring),
            stringResource(R.string.journey_preparing)
        )
    }

    val steps = if (completed) {
        listOf(
            Triple(titles[0], Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.secondary),
            Triple(titles[1], Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.secondary),
            Triple(titles[2], Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.secondary)
        )
    } else {
        listOf(
            Triple(titles[0], Icons.Rounded.Timer, MaterialTheme.colorScheme.primary),
            Triple(titles[1], Icons.Rounded.SportsEsports, MaterialTheme.colorScheme.secondary),
            Triple(titles[2], Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.tertiary)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEachIndexed { index, step ->
            JourneyStepCard(
                modifier = Modifier.weight(1f),
                title = step.first,
                icon = step.second,
                tint = step.third,
                active = index == currentStep,
                completed = index < currentStep || completed
            )
        }
    }
}

@Composable
private fun JourneyStepCard(
    modifier: Modifier,
    title: String,
    icon: ImageVector,
    tint: Color,
    active: Boolean,
    completed: Boolean
) {
    val container = when {
        active -> tint.copy(alpha = 0.22f)
        completed -> tint.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
    }

    val animatedContainer by animateColorAsState(
        targetValue = container,
        animationSpec = tween(260),
        label = "journeyStepColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (active) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "journeyStepScale"
    )

    Surface(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = RoundedCornerShape(17.dp),
        color = animatedContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (completed) Icons.Rounded.CheckCircle else icon,
                contentDescription = null,
                tint = if (active || completed) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (active || completed) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LiveMetricsGrid(metrics: LiveMetrics?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.live_metrics),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            LiveMetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Memory,
                label = stringResource(R.string.cpu),
                value = formatPercent(metrics?.cpuPercent),
                color = MaterialTheme.colorScheme.primary
            )
            LiveMetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Speed,
                label = stringResource(R.string.gpu),
                value = formatPercent(metrics?.gpuPercent),
                color = MaterialTheme.colorScheme.secondary
            )
            LiveMetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Storage,
                label = stringResource(R.string.ram),
                value = formatPercent(metrics?.ramPercent),
                color = MaterialTheme.colorScheme.tertiary
            )
            LiveMetricTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Thermostat,
                label = stringResource(R.string.temperature_short),
                value = formatTemperature(metrics?.temperatureC),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun LiveMetricTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = color.copy(alpha = 0.14f),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        (
                            fadeIn(tween(170)) +
                                slideInVertically(tween(180)) { height -> height / 3 }
                            ) togetherWith fadeOut(tween(110))
                    },
                    label = "liveMetricValue"
                ) { animatedValue ->
                    Text(
                        text = animatedValue,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionCard(
    session: SessionSummary,
    onShare: () -> Unit,
    onNewDiagnostic: () -> Unit
) {
    val context = LocalContext.current
    val newPress = rememberCozyPressState()

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiagnosticJourneyCarousel(MonitorState.COMPLETED)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diagnostic_completed_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = stringResource(R.string.diagnostic_completed_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SportsEsports,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.emulator,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.session_seconds,
                                session.durationSeconds.toInt().coerceAtLeast(0),
                                session.durationSeconds.toInt().coerceAtLeast(0)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.session_samples,
                                session.sampleCount,
                                session.sampleCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CompletionMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.average_cpu),
                    value = formatPercent(session.cpuAveragePercent),
                    color = MaterialTheme.colorScheme.primary
                )
                CompletionMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.average_gpu),
                    value = formatPercent(session.gpuAveragePercent),
                    color = MaterialTheme.colorScheme.secondary
                )
                CompletionMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.average_ram),
                    value = formatPercent(session.ramAveragePercent),
                    color = MaterialTheme.colorScheme.tertiary
                )
                CompletionMetricTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.maximum_temperature),
                    value = formatTemperature(session.maximumTemperatureC),
                    color = MaterialTheme.colorScheme.error
                )
            }

            DiagnosticShareActions(
                onShare = onShare,
                emphasized = true
            )

            FilledTonalButton(
                onClick = onNewDiagnostic,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .cozyPress(newPress.scale),
                interactionSource = newPress.interactionSource,
                shape = RoundedCornerShape(17.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.new_diagnostic),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CompletionMetricTile(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = color
            )
        }
    }
}

@Composable
private fun ShareDiagnosticButton(
    onClick: () -> Unit
) {
    DiagnosticShareActions(
        onShare = onClick,
        emphasized = false
    )
}

@Composable
private fun SessionCard(session: SessionSummary) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SportsEsports,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Text(
                    text = session.emulator,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Timer,
                    label = stringResource(R.string.duration),
                    value = formatDuration(LocalContext.current, session.durationSeconds),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.CheckCircle,
                    label = stringResource(R.string.samples),
                    value = session.sampleCount.toString(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(result: DiagnosticSummary) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeviceStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.model),
                    value = result.device,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                DeviceStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.android),
                    value = result.androidVersion,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DeviceStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.emulators),
                    value = result.emulatorsInstalled.toString(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
                DeviceStatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.readable_configs),
                    value = result.readableConfigs.toString(),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DeviceStatTile(
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.games),
                value = result.gameCount.toString(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun CozySectionTitle(
    icon: ImageVector,
    title: String,
    tint: Color
) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.16f),
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(19.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.72f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun DeviceStatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.72f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = contentColor
            )
        }
    }
}

private fun heroIconForState(state: MonitorState): ImageVector =
    when (state) {
        MonitorState.ERROR -> Icons.Rounded.Warning
        MonitorState.COMPLETED -> Icons.Rounded.CheckCircle
        MonitorState.MONITORING,
        MonitorState.FINISHING,
        MonitorState.GENERATING -> Icons.Rounded.Timer
        else -> Icons.Rounded.PlayArrow
    }

@Composable
private fun monitorStatusText(
    state: MonitorState,
    emulatorName: String?,
    remainingSeconds: Long,
    error: MonitorError?
): String =
    when (state) {
        MonitorState.IDLE -> stringResource(R.string.status_ready)
        MonitorState.WAITING_EMULATOR -> stringResource(R.string.status_waiting_emulator)
        MonitorState.MONITORING -> {
            if (emulatorName != null) {
                stringResource(R.string.status_monitoring, emulatorName)
            } else {
                stringResource(R.string.status_monitoring_generic)
            }
        }
        MonitorState.FINISHING -> {
            if (emulatorName != null && remainingSeconds > 0L) {
                stringResource(
                    R.string.status_finishing_background,
                    remainingSeconds
                )
            } else {
                stringResource(R.string.status_finishing)
            }
        }
        MonitorState.GENERATING -> stringResource(R.string.status_generating)
        MonitorState.COMPLETED -> stringResource(R.string.status_completed)
        MonitorState.ERROR -> when (error) {
            MonitorError.SESSION_SAVE_FAILED -> stringResource(R.string.error_save_session)
            MonitorError.DIAGNOSTIC_GENERATION_FAILED -> stringResource(
                R.string.error_generate_diagnostic
            )
            null -> stringResource(R.string.status_error)
        }
    }