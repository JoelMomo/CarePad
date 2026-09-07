package com.joel.thordoctor.ui

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent as AndroidKeyEvent
import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

internal data class CozyPressState(
    val interactionSource: MutableInteractionSource,
    val scale: Float
)

@Composable
internal fun rememberCozyPressState(): CozyPressState {
    val context = LocalContext.current
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed) {
        if (pressed) {
            performCozyFeedback(
                context = context,
                view = view
            )
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cozyButtonPress"
    )

    return CozyPressState(
        interactionSource = interactionSource,
        scale = scale
    )
}

@Composable
internal fun rememberCozyFeedback(): () -> Unit {
    val context = LocalContext.current
    val view = LocalView.current

    return remember(context, view) {
        {
            performCozyFeedback(
                context = context,
                view = view
            )
        }
    }
}

@Composable
internal fun rememberCozyClick(
    onClick: () -> Unit
): () -> Unit {
    val performFeedback = rememberCozyFeedback()
    val currentOnClick by rememberUpdatedState(onClick)

    return remember(performFeedback) {
        {
            performFeedback()
            currentOnClick()
        }
    }
}

@Composable
internal fun Modifier.cozyClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val onClickWithFeedback = rememberCozyClick(onClick)
    return clickable(
        enabled = enabled,
        onClick = onClickWithFeedback,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.cozyCombinedClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val onClickWithFeedback = rememberCozyClick(onClick)
    val performFeedback = rememberCozyFeedback()
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val onLongClickWithFeedback = remember(performFeedback) {
        {
            performFeedback()
            currentOnLongClick?.invoke()
        }
    }

    return combinedClickable(
        enabled = enabled,
        onClick = onClickWithFeedback,
        onLongClick = if (onLongClick == null) null else onLongClickWithFeedback,
    )
}

@Composable
internal fun Modifier.cozyControllerActivation(
    enabled: Boolean,
    onActivate: () -> Unit,
): Modifier {
    val onActivateWithFeedback = rememberCozyClick(onActivate)
    return onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (
            enabled &&
            event.type == KeyEventType.KeyDown &&
            native.repeatCount == 0 &&
            native.keyCode in setOf(
                AndroidKeyEvent.KEYCODE_BUTTON_A,
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            )
        ) {
            onActivateWithFeedback()
            true
        } else {
            false
        }
    }
}

private fun performCozyFeedback(
    context: Context,
    view: View
) {
    view.performHapticFeedback(
        HapticFeedbackConstants.KEYBOARD_TAP
    )

    val audioManager =
        context.getSystemService(
            AudioManager::class.java
        )

    try {
        audioManager?.playSoundEffect(
            SoundEffectConstants.CLICK,
            0.14f
        )
    } catch (_: Exception) {
        // Audio feedback is optional. Never block the action if it is unavailable.
    }
}

internal fun Modifier.cozyPress(scale: Float): Modifier =
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
