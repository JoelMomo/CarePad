package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode

internal sealed interface CarePadFocusKey {
    data class Rail(val destination: CarePadDestination) : CarePadFocusKey
    data class Module(val packageName: String) : CarePadFocusKey
    data class Theme(val mode: AppThemeMode) : CarePadFocusKey
    data class ContentFallback(val destination: CarePadDestination) : CarePadFocusKey
}

internal data class CarePadFocusControllerState(
    val modality: CarePadInputMethod = CarePadInputMethod.TOUCH,
    val selectedDestination: CarePadDestination = CarePadDestination.HOME,
    val observedFocus: CarePadFocusKey? = null,
)

internal sealed interface CarePadFocusEvent {
    data object TouchContext : CarePadFocusEvent
    data class DestinationSelected(val destination: CarePadDestination) : CarePadFocusEvent
    data object ControllerActivity : CarePadFocusEvent
    data class FocusObserved(val key: CarePadFocusKey?) : CarePadFocusEvent
}

internal fun reduceCarePadFocus(
    state: CarePadFocusControllerState,
    event: CarePadFocusEvent,
): CarePadFocusControllerState = when (event) {
    CarePadFocusEvent.TouchContext -> state.copy(
        modality = CarePadInputMethod.TOUCH,
    )

    is CarePadFocusEvent.DestinationSelected -> state.copy(
        selectedDestination = event.destination,
        observedFocus = state.observedFocus?.takeIf { key ->
            key is CarePadFocusKey.Rail ||
                carePadFocusKeyMatchesDestination(key, event.destination)
        },
    )

    CarePadFocusEvent.ControllerActivity -> state.copy(
        modality = CarePadInputMethod.CONTROLLER,
    )

    is CarePadFocusEvent.FocusObserved -> state.copy(
        observedFocus = event.key?.takeIf { key ->
            key is CarePadFocusKey.Rail ||
                carePadFocusKeyMatchesDestination(key, state.selectedDestination)
        },
    )
}

internal fun carePadFocusKeyMatchesDestination(
    key: CarePadFocusKey,
    destination: CarePadDestination,
): Boolean = when (key) {
    is CarePadFocusKey.Rail -> true
    is CarePadFocusKey.Module -> destination == CarePadDestination.MODULES
    is CarePadFocusKey.Theme -> destination == CarePadDestination.SETTINGS
    is CarePadFocusKey.ContentFallback -> key.destination == destination
}

internal fun carePadContentTargets(
    destination: CarePadDestination,
    visiblePackages: Collection<String>,
): Set<CarePadFocusKey> = when (destination) {
    CarePadDestination.HOME -> emptySet()

    CarePadDestination.MODULES -> buildSet {
        visiblePackages.forEach { packageName ->
            add(CarePadFocusKey.Module(packageName))
        }
    }
    CarePadDestination.SETTINGS ->
        AppThemeMode.entries.mapTo(linkedSetOf()) { CarePadFocusKey.Theme(it) }
}

internal fun carePadContentFallback(
    destination: CarePadDestination,
    visiblePackages: Collection<String>,
): CarePadFocusKey = when (destination) {
    CarePadDestination.HOME -> CarePadFocusKey.ContentFallback(CarePadDestination.HOME)

    CarePadDestination.MODULES -> visiblePackages.firstOrNull()
        ?.let(CarePadFocusKey::Module)
        ?: CarePadFocusKey.ContentFallback(CarePadDestination.MODULES)
    CarePadDestination.SETTINGS -> CarePadFocusKey.Theme(AppThemeMode.SYSTEM)
}

internal fun carePadControllerActionTarget(
    state: CarePadFocusControllerState,
    visiblePackages: Collection<String>,
): CarePadFocusKey? = when (val focused = state.observedFocus ?: return null) {
    is CarePadFocusKey.Rail -> focused
    is CarePadFocusKey.Module -> focused.takeIf {
        state.selectedDestination == CarePadDestination.MODULES &&
            it.packageName in visiblePackages
    }
    is CarePadFocusKey.Theme -> focused.takeIf {
        state.selectedDestination == CarePadDestination.SETTINGS
    }
    is CarePadFocusKey.ContentFallback -> focused.takeIf {
        state.selectedDestination == CarePadDestination.HOME &&
            focused.destination == CarePadDestination.HOME
    }
}

internal fun carePadDetailsControllerActionAllowed(
    state: CarePadFocusControllerState,
    visiblePackages: Collection<String>,
): Boolean {
    val packageName = (state.observedFocus as? CarePadFocusKey.Module)?.packageName
        ?: return false
    return state.selectedDestination == CarePadDestination.MODULES &&
        packageName in visiblePackages
}
