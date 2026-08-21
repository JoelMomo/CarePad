package com.joel.thordoctor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joel.thordoctor.R

@Composable
internal fun DiagnosticShareActions(
    onShare: () -> Unit,
    emphasized: Boolean
) {
    var infoVisible by remember { mutableStateOf(false) }
    val sharePress = rememberCozyPressState()
    val toggleInfo = rememberCozyClick {
        infoVisible = !infoVisible
    }

    val infoScale by animateFloatAsState(
        targetValue = if (infoVisible) 1.07f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "diagnosticInfoButtonScale"
    )

    val infoContainer = if (emphasized) {
        if (infoVisible) {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
        }
    } else {
        if (infoVisible) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    }

    val infoTint = if (emphasized) {
        MaterialTheme.colorScheme.secondary
    } else if (infoVisible) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emphasized) {
                Button(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .cozyPress(sharePress.scale),
                    interactionSource = sharePress.interactionSource,
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.share_diagnostic),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onShare,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .cozyPress(sharePress.scale),
                    interactionSource = sharePress.interactionSource,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.share_diagnostic),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = infoScale
                        scaleY = infoScale
                    },
                shape = CircleShape,
                color = infoContainer
            ) {
                IconButton(onClick = toggleInfo) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(
                            R.string.diagnostic_info_button_description
                        ),
                        tint = infoTint
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = infoVisible,
            enter = fadeIn(tween(190)) +
                expandVertically(
                    animationSpec = tween(260),
                    expandFrom = Alignment.Top
                ),
            exit = fadeOut(tween(130)) +
                shrinkVertically(
                    animationSpec = tween(190),
                    shrinkTowards = Alignment.Top
                )
        ) {
            DiagnosticUseBubble()
        }
    }
}

@Composable
private fun DiagnosticUseBubble() {
    val bubbleColor = MaterialTheme.colorScheme.tertiaryContainer
    val contentColor = MaterialTheme.colorScheme.onTertiaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = bubbleColor
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.diagnostic_info_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor
                    )
                }

                Text(
                    text = stringResource(R.string.diagnostic_info_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.78f)
                )

                DiagnosticUseLine(
                    color = MaterialTheme.colorScheme.primary,
                    text = stringResource(R.string.diagnostic_info_ai),
                    contentColor = contentColor
                )
                DiagnosticUseLine(
                    color = MaterialTheme.colorScheme.secondary,
                    text = stringResource(R.string.diagnostic_info_drive),
                    contentColor = contentColor
                )
                DiagnosticUseLine(
                    color = MaterialTheme.colorScheme.tertiary,
                    text = stringResource(R.string.diagnostic_info_technician),
                    contentColor = contentColor
                )
                DiagnosticUseLine(
                    color = MaterialTheme.colorScheme.error,
                    text = stringResource(R.string.diagnostic_info_compare),
                    contentColor = contentColor
                )

                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
                ) {
                    Text(
                        text = stringResource(R.string.diagnostic_info_privacy),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.82f)
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-18).dp, y = (-5).dp)
                .size(13.dp)
                .rotate(45f),
            shape = RoundedCornerShape(3.dp),
            color = bubbleColor
        ) {}
    }
}

@Composable
private fun DiagnosticUseLine(
    color: Color,
    text: String,
    contentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Surface(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp),
            shape = CircleShape,
            color = color
        ) {}

        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}
