package com.joel.thordoctor.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class CarePadNavigationLayout {
    RAIL,
    BOTTOM_BAR,
}

private val CarePadRailMinimumWidth = 600.dp
private val CarePadRailCompactWidth = 80.dp
private val CarePadRailExpandedWidth = 176.dp
private const val CarePadRailTransitionMillis = 180

internal fun carePadNavigationLayout(
    width: Dp,
    height: Dp,
): CarePadNavigationLayout = if (
    width >= CarePadRailMinimumWidth && width >= height
) {
    CarePadNavigationLayout.RAIL
} else {
    CarePadNavigationLayout.BOTTOM_BAR
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CarePadResponsiveNavigationScaffold(
    modifier: Modifier,
    selected: CarePadDestination,
    railVisualState: CarePadRailVisualState,
    focusRequesters: Map<CarePadDestination, FocusRequester>,
    onFocusChanged: (CarePadDestination, Boolean) -> Unit,
    onSelected: (CarePadDestination) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        when (carePadNavigationLayout(maxWidth, maxHeight)) {
            CarePadNavigationLayout.RAIL -> Row(modifier = Modifier.fillMaxSize()) {
                CarePadNavigationRail(
                    selected = selected,
                    visualState = railVisualState,
                    focusRequesters = focusRequesters,
                    onFocusChanged = onFocusChanged,
                    onSelected = onSelected,
                )
                content(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            CarePadNavigationLayout.BOTTOM_BAR -> Column(modifier = Modifier.fillMaxSize()) {
                content(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                CarePadNavigationBar(
                    selected = selected,
                    focusRequesters = focusRequesters,
                    onFocusChanged = onFocusChanged,
                    onSelected = onSelected,
                )
            }
        }
    }
}

@Composable
private fun CarePadNavigationRail(
    selected: CarePadDestination,
    visualState: CarePadRailVisualState,
    focusRequesters: Map<CarePadDestination, FocusRequester>,
    onFocusChanged: (CarePadDestination, Boolean) -> Unit,
    onSelected: (CarePadDestination) -> Unit,
) {
    val expanded = visualState == CarePadRailVisualState.EXPANDED
    val animatedWidth by animateDpAsState(
        targetValue = if (expanded) CarePadRailExpandedWidth else CarePadRailCompactWidth,
        animationSpec = tween(durationMillis = CarePadRailTransitionMillis),
    )

    NavigationRail(
        modifier = Modifier
            .focusRestorer()
            .focusGroup()
            .fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        navigationItems().forEach { item ->
            NavigationRailItem(
                selected = carePadRailItemSelected(selected, item.destination),
                onClick = { onSelected(item.destination) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes),
                    )
                },
                label = if (expanded) {
                    { Text(stringResource(item.labelRes)) }
                } else {
                    null
                },
                alwaysShowLabel = expanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = true }
                    .focusRequester(focusRequesters.getValue(item.destination))
                    .onFocusChanged { state ->
                        onFocusChanged(item.destination, state.isFocused)
                    },
            )
        }
    }
}

@Composable
private fun CarePadNavigationBar(
    selected: CarePadDestination,
    focusRequesters: Map<CarePadDestination, FocusRequester>,
    onFocusChanged: (CarePadDestination, Boolean) -> Unit,
    onSelected: (CarePadDestination) -> Unit,
) {
    NavigationBar(
        modifier = Modifier
            .focusRestorer()
            .focusGroup()
            .fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        navigationItems().forEach { item ->
            NavigationBarItem(
                selected = carePadRailItemSelected(selected, item.destination),
                onClick = { onSelected(item.destination) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes),
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
                alwaysShowLabel = true,
                modifier = Modifier
                    .weight(1f)
                    .focusProperties { canFocus = true }
                    .focusRequester(focusRequesters.getValue(item.destination))
                    .onFocusChanged { state ->
                        onFocusChanged(item.destination, state.isFocused)
                    },
            )
        }
    }
}
