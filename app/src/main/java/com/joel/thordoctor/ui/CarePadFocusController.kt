package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode

internal sealed interface CarePadFocusKey {
    data class Rail(val destination: CarePadDestination) : CarePadFocusKey
    data class Module(val packageName: String) : CarePadFocusKey
    data class Uninstall(val packageName: String) : CarePadFocusKey
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
    is CarePadFocusKey.Module,
    is CarePadFocusKey.Uninstall -> destination == CarePadDestination.HOME
    is CarePadFocusKey.Theme -> destination == CarePadDestination.SETTINGS
    is CarePadFocusKey.ContentFallback -> key.destination == destination
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

internal fun carePadControllerActionTarget(
    state: CarePadFocusControllerState,
    visiblePackages: Collection<String>,
    expandedPackage: String? = null,
): CarePadFocusKey? = when (val focused = state.observedFocus ?: return null) {
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

internal fun carePadDetailsControllerActionAllowed(
    state: CarePadFocusControllerState,
    visiblePackages: Collection<String>,
): Boolean {
    val packageName = when (val focused = state.observedFocus) {
        is CarePadFocusKey.Module -> focused.packageName
        is CarePadFocusKey.Uninstall -> focused.packageName
        else -> return false
    }
    return state.selectedDestination == CarePadDestination.HOME &&
        packageName in visiblePackages
}
