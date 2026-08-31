package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode

internal sealed interface CarePadFocusKey {
    data class Rail(val destination: CarePadDestination) : CarePadFocusKey
    data class Module(val packageName: String) : CarePadFocusKey
    data class Uninstall(val packageName: String) : CarePadFocusKey
    data class Theme(val mode: AppThemeMode) : CarePadFocusKey
    data class ContentFallback(val destination: CarePadDestination) : CarePadFocusKey
}

internal data class CarePadPendingFocus(
    val token: Long,
    val target: CarePadFocusKey,
)

internal data class CarePadFocusControllerState(
    val activeZone: CarePadFocusZone = CarePadFocusZone.CONTENT,
    val modality: CarePadInputMethod = CarePadInputMethod.TOUCH,
    val selectedDestination: CarePadDestination = CarePadDestination.HOME,
    val railPreferredDestination: CarePadDestination? = null,
    val contentPreferredTargets: Map<CarePadDestination, CarePadFocusKey> = emptyMap(),
    val contentFallbackTarget: CarePadFocusKey =
        CarePadFocusKey.ContentFallback(CarePadDestination.HOME),
    val observedFocus: CarePadFocusKey? = null,
    val pendingFocus: CarePadPendingFocus? = null,
    val nextFocusToken: Long = 1L,
)

internal sealed interface CarePadFocusEvent {
    data object TouchContext : CarePadFocusEvent
    data class TouchRail(val destination: CarePadDestination) : CarePadFocusEvent
    data class TouchContent(val target: CarePadFocusKey? = null) : CarePadFocusEvent
    data class DestinationSelected(val destination: CarePadDestination) : CarePadFocusEvent
    data class ContentTargetsChanged(
        val destination: CarePadDestination,
        val validTargets: Set<CarePadFocusKey>,
        val fallbackTarget: CarePadFocusKey,
    ) : CarePadFocusEvent

    data object ControllerActivity : CarePadFocusEvent
    data class ControllerL1(val repeat: Boolean = false) : CarePadFocusEvent

    data class FocusObserved(val key: CarePadFocusKey?) : CarePadFocusEvent
    data class ZoneFocusLost(val zone: CarePadFocusZone) : CarePadFocusEvent
    data class FocusRequestExecuted(val token: Long) : CarePadFocusEvent
}

internal fun reduceCarePadFocus(
    state: CarePadFocusControllerState,
    event: CarePadFocusEvent,
): CarePadFocusControllerState = when (event) {
    CarePadFocusEvent.TouchContext -> state.copy(
        modality = CarePadInputMethod.TOUCH,
        pendingFocus = null,
    )

    is CarePadFocusEvent.TouchRail -> {
        val touchedTarget = CarePadFocusKey.Rail(event.destination)
        state.copy(
            activeZone = CarePadFocusZone.RAIL,
            modality = CarePadInputMethod.TOUCH,
            railPreferredDestination = event.destination,
            observedFocus = state.observedFocus.takeIf { it == touchedTarget },
            pendingFocus = null,
        )
    }

    is CarePadFocusEvent.TouchContent -> {
        val touchedTarget = event.target?.takeIf {
            carePadFocusZone(it) == CarePadFocusZone.CONTENT &&
                it !is CarePadFocusKey.ContentFallback &&
                carePadFocusKeyMatchesDestination(it, state.selectedDestination)
        }
        val preferredTargets = if (touchedTarget != null) {
            state.contentPreferredTargets + (state.selectedDestination to touchedTarget)
        } else {
            state.contentPreferredTargets
        }
        state.copy(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.TOUCH,
            contentPreferredTargets = preferredTargets,
            observedFocus = if (touchedTarget == null) {
                state.observedFocus
            } else {
                state.observedFocus.takeIf { it == touchedTarget }
            },
            pendingFocus = null,
        )
    }

    is CarePadFocusEvent.DestinationSelected -> state.copy(
        selectedDestination = event.destination,
        contentFallbackTarget = carePadDefaultContentFallback(event.destination),
        observedFocus = if (state.activeZone == CarePadFocusZone.CONTENT) {
            null
        } else {
            state.observedFocus
        },
        pendingFocus = null,
    )

    is CarePadFocusEvent.ContentTargetsChanged ->
        reduceContentTargetsChanged(state, event)

    CarePadFocusEvent.ControllerActivity -> state.copy(
        modality = CarePadInputMethod.CONTROLLER,
    )

    is CarePadFocusEvent.ControllerL1 -> {
        val controllerState = state.copy(modality = CarePadInputMethod.CONTROLLER)
        if (event.repeat) {
            controllerState
        } else {
            val nextZone = when (controllerState.activeZone) {
                CarePadFocusZone.RAIL -> CarePadFocusZone.CONTENT
                CarePadFocusZone.CONTENT -> CarePadFocusZone.RAIL
            }
            controllerState.copy(
                activeZone = nextZone,
                pendingFocus = null,
            ).scheduleFocus(carePadEntryTarget(controllerState, nextZone))
        }
    }

    is CarePadFocusEvent.FocusObserved -> reduceFocusObserved(state, event.key)

    is CarePadFocusEvent.ZoneFocusLost -> {
        if (state.observedFocus?.let(::carePadFocusZone) == event.zone) {
            state.copy(observedFocus = null)
        } else {
            state
        }
    }

    is CarePadFocusEvent.FocusRequestExecuted -> {
        if (state.pendingFocus?.token == event.token) {
            state.copy(pendingFocus = null)
        } else {
            state
        }
    }
}

internal fun carePadFocusZone(key: CarePadFocusKey): CarePadFocusZone = when (key) {
    is CarePadFocusKey.Rail -> CarePadFocusZone.RAIL
    is CarePadFocusKey.Module,
    is CarePadFocusKey.Uninstall,
    is CarePadFocusKey.Theme,
    is CarePadFocusKey.ContentFallback -> CarePadFocusZone.CONTENT
}

internal fun carePadFocusKeyMatchesDestination(
    key: CarePadFocusKey,
    destination: CarePadDestination,
): Boolean = when (key) {
    is CarePadFocusKey.Rail -> true
    is CarePadFocusKey.Module,
    is CarePadFocusKey.Uninstall -> destination == CarePadDestination.HOME
    is CarePadFocusKey.Theme -> destination == CarePadDestination.SETTINGS
    is CarePadFocusKey.ContentFallback -> key.destination == destination
}

internal fun carePadDefaultContentFallback(
    destination: CarePadDestination,
): CarePadFocusKey = when (destination) {
    CarePadDestination.HOME -> CarePadFocusKey.ContentFallback(CarePadDestination.HOME)
    CarePadDestination.ADD_MODULES ->
        CarePadFocusKey.ContentFallback(CarePadDestination.ADD_MODULES)
    CarePadDestination.SETTINGS -> CarePadFocusKey.Theme(AppThemeMode.SYSTEM)
}

internal fun carePadContentTargets(
    destination: CarePadDestination,
    visiblePackages: Collection<String>,
    expandedPackage: String? = null,
): Set<CarePadFocusKey> = when (destination) {
    CarePadDestination.HOME -> buildSet {
        visiblePackages.forEach { packageName ->
            add(CarePadFocusKey.Module(packageName))
            if (expandedPackage == packageName) {
                add(CarePadFocusKey.Uninstall(packageName))
            }
        }
    }

    CarePadDestination.ADD_MODULES -> emptySet()
    CarePadDestination.SETTINGS ->
        AppThemeMode.entries.mapTo(linkedSetOf()) { CarePadFocusKey.Theme(it) }
}

internal fun carePadContentFallback(
    destination: CarePadDestination,
    visiblePackages: Collection<String>,
): CarePadFocusKey = when (destination) {
    CarePadDestination.HOME -> visiblePackages.firstOrNull()
        ?.let(CarePadFocusKey::Module)
        ?: CarePadFocusKey.ContentFallback(CarePadDestination.HOME)

    CarePadDestination.ADD_MODULES ->
        CarePadFocusKey.ContentFallback(CarePadDestination.ADD_MODULES)
    CarePadDestination.SETTINGS -> CarePadFocusKey.Theme(AppThemeMode.SYSTEM)
}

internal fun carePadEntryTarget(
    state: CarePadFocusControllerState,
    zone: CarePadFocusZone,
): CarePadFocusKey = when (zone) {
    CarePadFocusZone.RAIL -> CarePadFocusKey.Rail(
        state.railPreferredDestination ?: state.selectedDestination
    )

    CarePadFocusZone.CONTENT ->
        state.contentPreferredTargets[state.selectedDestination]
            ?: state.contentFallbackTarget
}

internal fun carePadControllerActionTarget(
    state: CarePadFocusControllerState,
    visiblePackages: Collection<String>,
    expandedPackage: String? = null,
): CarePadFocusKey? {
    val focused = state.observedFocus ?: return null
    if (carePadFocusZone(focused) != state.activeZone) {
        return null
    }
    return when (focused) {
        is CarePadFocusKey.Rail -> focused
        is CarePadFocusKey.Module -> focused.takeIf {
            state.selectedDestination == CarePadDestination.HOME &&
                it.packageName in visiblePackages
        }
        is CarePadFocusKey.Uninstall -> focused.takeIf {
            state.selectedDestination == CarePadDestination.HOME &&
                it.packageName in visiblePackages &&
                expandedPackage == it.packageName
        }
        is CarePadFocusKey.Theme -> focused.takeIf {
            state.selectedDestination == CarePadDestination.SETTINGS
        }
        is CarePadFocusKey.ContentFallback -> null
    }
}

internal fun carePadDetailsControllerActionAllowed(
    state: CarePadFocusControllerState,
    visiblePackages: Collection<String>,
): Boolean {
    val packageName = when (val focused = state.observedFocus) {
        is CarePadFocusKey.Module -> focused.packageName
        is CarePadFocusKey.Uninstall -> focused.packageName
        else -> return false
    }
    return state.activeZone == CarePadFocusZone.CONTENT &&
        state.selectedDestination == CarePadDestination.HOME &&
        packageName in visiblePackages
}

private fun reduceContentTargetsChanged(
    state: CarePadFocusControllerState,
    event: CarePadFocusEvent.ContentTargetsChanged,
): CarePadFocusControllerState {
    require(carePadFocusZone(event.fallbackTarget) == CarePadFocusZone.CONTENT)
    require(carePadFocusKeyMatchesDestination(event.fallbackTarget, event.destination))
    require(event.validTargets.all {
        carePadFocusZone(it) == CarePadFocusZone.CONTENT &&
            carePadFocusKeyMatchesDestination(it, event.destination)
    })

    val remembered = state.contentPreferredTargets[event.destination]
    val preferredTargets = if (remembered != null && remembered !in event.validTargets) {
        state.contentPreferredTargets - event.destination
    } else {
        state.contentPreferredTargets
    }
    val selectedContext = state.selectedDestination == event.destination
    val observed = state.observedFocus
    var next = state.copy(
        contentPreferredTargets = preferredTargets,
        contentFallbackTarget = if (selectedContext) {
            event.fallbackTarget
        } else {
            state.contentFallbackTarget
        },
        observedFocus = if (
            selectedContext &&
            observed != null &&
            carePadFocusZone(observed) == CarePadFocusZone.CONTENT &&
            observed != event.fallbackTarget &&
            observed !in event.validTargets
        ) {
            null
        } else {
            observed
        },
    )

    val requested = next.pendingFocus?.target
    if (
        selectedContext &&
        requested != null &&
        carePadFocusZone(requested) == CarePadFocusZone.CONTENT &&
        requested != event.fallbackTarget &&
        requested !in event.validTargets
    ) {
        next = next.copy(pendingFocus = null).scheduleFocus(event.fallbackTarget)
    }
    return next
}

private fun reduceFocusObserved(
    state: CarePadFocusControllerState,
    key: CarePadFocusKey?,
): CarePadFocusControllerState {
    val normalizedKey = key?.takeIf {
        carePadFocusZone(it) == CarePadFocusZone.RAIL ||
            carePadFocusKeyMatchesDestination(it, state.selectedDestination)
    }
    var next = state.copy(observedFocus = normalizedKey)
    when (normalizedKey) {
        is CarePadFocusKey.Rail -> {
            if (state.activeZone == CarePadFocusZone.RAIL) {
                next = next.copy(railPreferredDestination = normalizedKey.destination)
            }
        }
        is CarePadFocusKey.Module,
        is CarePadFocusKey.Uninstall,
        is CarePadFocusKey.Theme -> {
            next = next.copy(
                contentPreferredTargets = next.contentPreferredTargets +
                    (next.selectedDestination to normalizedKey)
            )
        }
        is CarePadFocusKey.ContentFallback,
        null -> Unit
    }

    if (next.pendingFocus?.target == normalizedKey) {
        next = next.copy(pendingFocus = null)
    }
    return next
}

private fun CarePadFocusControllerState.scheduleFocus(
    target: CarePadFocusKey,
): CarePadFocusControllerState = copy(
    pendingFocus = CarePadPendingFocus(
        token = nextFocusToken,
        target = target,
    ),
    nextFocusToken = nextFocusToken + 1,
)
