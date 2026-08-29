package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadShellNavigationTest {
    private val modulePackage = "dev.carepad.module.performance"
    private val visiblePackages = setOf(modulePackage)

    @Test
    fun railPrimaryActionActivatesFocusedRailDestinationInsteadOfModule() {
        val focusState = CarePadFocusState()
            .onRailFocused(CarePadDestination.ADD_MODULES)

        val target = carePadPrimaryControllerTarget(
            focusState = focusState,
            destination = CarePadDestination.HOME,
            focusedTarget = CarePadPrimaryControllerTarget.Rail(
                CarePadDestination.ADD_MODULES
            ),
            visiblePackages = visiblePackages,
        )

        assertEquals(
            CarePadPrimaryControllerTarget.Rail(CarePadDestination.ADD_MODULES),
            target,
        )
        assertFalse(target is CarePadPrimaryControllerTarget.Module)
    }

    @Test
    fun contentRoundTripPreservesLastActuallyFocusedRailDestination() {
        var focusState = CarePadFocusState()
            .onRailFocused(CarePadDestination.SETTINGS)

        focusState = focusState.onContentFocused()

        assertEquals(CarePadFocusZone.CONTENT, focusState.zone)
        assertEquals(CarePadDestination.SETTINGS, focusState.lastRailDestination)

        val targetAfterReturningToRail = focusState.onRailFocused(
            carePadRailRestoreDestination(
                focusState = focusState,
                selectedDestination = CarePadDestination.HOME,
            )
        )

        assertEquals(CarePadFocusZone.RAIL, targetAfterReturningToRail.zone)
        assertEquals(
            CarePadDestination.SETTINGS,
            targetAfterReturningToRail.lastRailDestination,
        )
    }

    @Test
    fun contentTouchSwitchesImmediatelyAndPreservesPageRailAndContentContext() {
        val rememberedTarget = CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT)
        val before = CarePadInteractionSnapshot(
            inputMethod = CarePadInputMethod.CONTROLLER,
            destination = CarePadDestination.SETTINGS,
            focusState = CarePadFocusState()
                .onRailFocused(CarePadDestination.SETTINGS),
            lastContentTarget = rememberedTarget,
        )

        val after = carePadContentTouchTransition(before)

        assertEquals(CarePadInputMethod.TOUCH, after.inputMethod)
        assertEquals(CarePadDestination.SETTINGS, after.destination)
        assertEquals(CarePadFocusZone.CONTENT, after.focusState.zone)
        assertEquals(CarePadDestination.SETTINGS, after.focusState.lastRailDestination)
        assertEquals(rememberedTarget, after.lastContentTarget)
        assertEquals(
            CarePadRailVisualState.COMPACT,
            carePadRailVisualState(after.focusState),
        )
    }

    @Test
    fun railTouchSwitchesImmediatelyAndPreservesTouchedRailAndContentContext() {
        val rememberedTarget = CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT)
        val before = CarePadInteractionSnapshot(
            inputMethod = CarePadInputMethod.CONTROLLER,
            destination = CarePadDestination.SETTINGS,
            focusState = CarePadFocusState()
                .onRailFocused(CarePadDestination.HOME),
            lastContentTarget = rememberedTarget,
        )

        val after = carePadRailTouchTransition(
            snapshot = before,
            touchedDestination = CarePadDestination.SETTINGS,
        )

        assertEquals(CarePadInputMethod.TOUCH, after.inputMethod)
        assertEquals(CarePadDestination.SETTINGS, after.destination)
        assertEquals(CarePadFocusZone.RAIL, after.focusState.zone)
        assertEquals(CarePadDestination.SETTINGS, after.focusState.lastRailDestination)
        assertEquals(rememberedTarget, after.lastContentTarget)
        assertEquals(
            CarePadRailVisualState.EXPANDED,
            carePadRailVisualState(after.focusState),
        )
    }

    @Test
    fun actionableTouchBecomesRememberedContentTargetWithoutChangingPageOrRail() {
        val before = CarePadInteractionSnapshot(
            inputMethod = CarePadInputMethod.CONTROLLER,
            destination = CarePadDestination.HOME,
            focusState = CarePadFocusState()
                .onRailFocused(CarePadDestination.ADD_MODULES),
            lastContentTarget = CarePadPrimaryControllerTarget.Theme(AppThemeMode.DARK),
        )
        val touchedTarget = CarePadPrimaryControllerTarget.Module(modulePackage)

        val after = carePadContentTouchTransition(before, touchedTarget)

        assertEquals(CarePadInputMethod.TOUCH, after.inputMethod)
        assertEquals(CarePadDestination.HOME, after.destination)
        assertEquals(CarePadFocusZone.CONTENT, after.focusState.zone)
        assertEquals(CarePadDestination.ADD_MODULES, after.focusState.lastRailDestination)
        assertEquals(touchedTarget, after.lastContentTarget)
    }

    @Test
    fun nonActionableTouchDoesNotEraseRememberedContentTarget() {
        val rememberedTarget = CarePadPrimaryControllerTarget.Module(modulePackage)
        val before = CarePadInteractionSnapshot(
            inputMethod = CarePadInputMethod.CONTROLLER,
            destination = CarePadDestination.HOME,
            focusState = CarePadFocusState(),
            lastContentTarget = rememberedTarget,
        )

        val after = carePadContentTouchTransition(before, touchedTarget = null)

        assertEquals(rememberedTarget, after.lastContentTarget)
    }

    @Test
    fun firstControllerInputSynchronizesModeWithoutChangingPageOrRememberedContext() {
        val rememberedTarget = CarePadPrimaryControllerTarget.Theme(AppThemeMode.DARK)
        val touchState = carePadContentTouchTransition(
            CarePadInteractionSnapshot(
                inputMethod = CarePadInputMethod.CONTROLLER,
                destination = CarePadDestination.SETTINGS,
                focusState = CarePadFocusState()
                    .onRailFocused(CarePadDestination.SETTINGS),
                lastContentTarget = rememberedTarget,
            )
        )

        val controllerState = carePadControllerInputTransition(touchState)

        assertEquals(CarePadInputMethod.CONTROLLER, controllerState.inputMethod)
        assertEquals(CarePadDestination.SETTINGS, controllerState.destination)
        assertEquals(CarePadFocusZone.CONTENT, controllerState.focusState.zone)
        assertEquals(
            CarePadDestination.SETTINGS,
            controllerState.focusState.lastRailDestination,
        )
        assertEquals(rememberedTarget, controllerState.lastContentTarget)
    }

    @Test
    fun l1RestoresLastActuallyFocusedRailDestination() {
        val focusState = CarePadFocusState()
            .onRailFocused(CarePadDestination.ADD_MODULES)
            .onContentFocused()

        val destination = carePadRailRestoreDestination(
            focusState = focusState,
            selectedDestination = CarePadDestination.SETTINGS,
        )

        assertEquals(CarePadDestination.ADD_MODULES, destination)
    }

    @Test
    fun l1FallbackIsSelectedDestinationAndThereIsNoImplicitHomeRailTarget() {
        val focusState = CarePadFocusState()

        assertEquals(null, focusState.lastRailDestination)
        assertEquals(
            CarePadDestination.SETTINGS,
            carePadRailRestoreDestination(
                focusState = focusState,
                selectedDestination = CarePadDestination.SETTINGS,
            ),
        )
        assertFalse(
            carePadRailRestoreDestination(
                focusState = focusState,
                selectedDestination = CarePadDestination.SETTINGS,
            ) == CarePadDestination.HOME
        )
    }

    @Test
    fun firstControllerRecoveryUsesRememberedContentTargetForDpadContext() {
        val target = carePadRestoredContentTarget(
            destination = CarePadDestination.SETTINGS,
            lastContentTarget = CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT),
            focusedModulePackage = null,
            visiblePackages = emptyList(),
        )

        assertEquals(CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT), target)
        assertFalse(target is CarePadPrimaryControllerTarget.Rail)
    }

    @Test
    fun dpadContentRecoveryNeverFallsBackToRail() {
        val homeTarget = carePadRestoredContentTarget(
            destination = CarePadDestination.HOME,
            lastContentTarget = CarePadPrimaryControllerTarget.None,
            focusedModulePackage = null,
            visiblePackages = emptyList(),
        )
        val addModulesTarget = carePadRestoredContentTarget(
            destination = CarePadDestination.ADD_MODULES,
            lastContentTarget = CarePadPrimaryControllerTarget.Rail(CarePadDestination.HOME),
            focusedModulePackage = null,
            visiblePackages = emptyList(),
        )

        assertEquals(CarePadPrimaryControllerTarget.None, homeTarget)
        assertEquals(CarePadPrimaryControllerTarget.None, addModulesTarget)
        assertFalse(homeTarget is CarePadPrimaryControllerTarget.Rail)
        assertFalse(addModulesTarget is CarePadPrimaryControllerTarget.Rail)
    }

    @Test
    fun railVisualStateIsExpandedForRealRailFocus() {
        val focusState = CarePadFocusState()
            .onRailFocused(CarePadDestination.SETTINGS)

        assertEquals(
            CarePadRailVisualState.EXPANDED,
            carePadRailVisualState(focusState),
        )
    }

    @Test
    fun railVisualStateIsCompactForRealContentFocus() {
        val focusState = CarePadFocusState()
            .onRailFocused(CarePadDestination.SETTINGS)
            .onContentFocused()

        assertEquals(
            CarePadRailVisualState.COMPACT,
            carePadRailVisualState(focusState),
        )
    }

    @Test
    fun contentToRailRoundTripExpandsWithoutChangingRememberedContentTarget() {
        val rememberedContentTarget =
            CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT)
        var primaryTarget: CarePadPrimaryControllerTarget = rememberedContentTarget
        val contentState = CarePadFocusState(
            zone = CarePadFocusZone.CONTENT,
            lastRailDestination = CarePadDestination.SETTINGS,
        )

        val railDestination = carePadRailRestoreDestination(
            focusState = contentState,
            selectedDestination = CarePadDestination.SETTINGS,
        )
        val railState = contentState.onRailFocused(railDestination)
        primaryTarget = CarePadPrimaryControllerTarget.Rail(railDestination)

        assertEquals(CarePadFocusZone.RAIL, railState.zone)
        assertEquals(CarePadRailVisualState.EXPANDED, carePadRailVisualState(railState))
        assertEquals(CarePadDestination.SETTINGS, railState.lastRailDestination)
        assertEquals(
            CarePadPrimaryControllerTarget.Rail(CarePadDestination.SETTINGS),
            primaryTarget,
        )
        assertEquals(
            CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT),
            rememberedContentTarget,
        )
    }

    @Test
    fun railToContentRoundTripCompactsWithoutChangingControllerTargets() {
        val rememberedContentTarget =
            CarePadPrimaryControllerTarget.Theme(AppThemeMode.DARK)
        val primaryTarget = rememberedContentTarget
        val railState = CarePadFocusState()
            .onRailFocused(CarePadDestination.SETTINGS)

        val contentState = railState.onContentFocused()
        val visualState = carePadRailVisualState(contentState)

        assertEquals(CarePadFocusZone.CONTENT, contentState.zone)
        assertEquals(CarePadRailVisualState.COMPACT, visualState)
        assertEquals(CarePadDestination.SETTINGS, contentState.lastRailDestination)
        assertEquals(
            CarePadPrimaryControllerTarget.Theme(AppThemeMode.DARK),
            rememberedContentTarget,
        )
        assertEquals(
            CarePadPrimaryControllerTarget.Theme(AppThemeMode.DARK),
            primaryTarget,
        )
    }

    @Test
    fun compactRailKeepsSelectedDestinationIdentifiable() {
        val focusState = CarePadFocusState().onContentFocused()

        assertEquals(CarePadRailVisualState.COMPACT, carePadRailVisualState(focusState))
        assertTrue(
            carePadRailItemSelected(
                selected = CarePadDestination.SETTINGS,
                candidate = CarePadDestination.SETTINGS,
            )
        )
        assertFalse(
            carePadRailItemSelected(
                selected = CarePadDestination.SETTINGS,
                candidate = CarePadDestination.HOME,
            )
        )
    }

    @Test
    fun contentHomePrimaryActionStillTargetsFocusedModule() {
        val target = carePadPrimaryControllerTarget(
            focusState = CarePadFocusState(),
            destination = CarePadDestination.HOME,
            focusedTarget = CarePadPrimaryControllerTarget.Module(modulePackage),
            visiblePackages = visiblePackages,
        )

        assertEquals(CarePadPrimaryControllerTarget.Module(modulePackage), target)
    }

    @Test
    fun uninstallFocusInOpenDetailsTargetsUninstallNotModule() {
        val target = carePadPrimaryControllerTarget(
            focusState = CarePadFocusState(),
            destination = CarePadDestination.HOME,
            focusedTarget = CarePadPrimaryControllerTarget.Uninstall(modulePackage),
            visiblePackages = visiblePackages,
            expandedPackage = modulePackage,
        )

        assertEquals(CarePadPrimaryControllerTarget.Uninstall(modulePackage), target)
        assertFalse(target is CarePadPrimaryControllerTarget.Module)
    }

    @Test
    fun themeFocusTargetsExactlyFocusedThemeOption() {
        listOf(
            AppThemeMode.SYSTEM,
            AppThemeMode.LIGHT,
            AppThemeMode.DARK,
        ).forEach { mode ->
            val target = carePadPrimaryControllerTarget(
                focusState = CarePadFocusState(),
                destination = CarePadDestination.SETTINGS,
                focusedTarget = CarePadPrimaryControllerTarget.Theme(mode),
                visiblePackages = visiblePackages,
            )

            assertEquals(CarePadPrimaryControllerTarget.Theme(mode), target)
        }
    }

    @Test
    fun staleDetailsChildTargetCannotOpenModuleAfterDetailsClose() {
        val target = carePadPrimaryControllerTarget(
            focusState = CarePadFocusState(),
            destination = CarePadDestination.HOME,
            focusedTarget = CarePadPrimaryControllerTarget.Uninstall(modulePackage),
            visiblePackages = visiblePackages,
            expandedPackage = null,
        )

        assertEquals(CarePadPrimaryControllerTarget.None, target)
        assertFalse(target is CarePadPrimaryControllerTarget.Module)
    }

    @Test
    fun settingsRoundTripRestoresLastFocusedThemeWithoutPrimaryAction() {
        val target = carePadRestoredContentTarget(
            destination = CarePadDestination.SETTINGS,
            lastContentTarget = CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT),
            focusedModulePackage = modulePackage,
            visiblePackages = visiblePackages,
        )

        assertEquals(CarePadPrimaryControllerTarget.Theme(AppThemeMode.LIGHT), target)
    }

    @Test
    fun settingsWithoutPreviousContentTargetFallsBackToSystem() {
        val target = carePadRestoredContentTarget(
            destination = CarePadDestination.SETTINGS,
            lastContentTarget = CarePadPrimaryControllerTarget.None,
            focusedModulePackage = null,
            visiblePackages = emptyList(),
        )

        assertEquals(CarePadPrimaryControllerTarget.Theme(AppThemeMode.SYSTEM), target)
    }

    @Test
    fun homeRoundTripRestoresRememberedVisibleModule() {
        val target = carePadRestoredContentTarget(
            destination = CarePadDestination.HOME,
            lastContentTarget = CarePadPrimaryControllerTarget.Module(modulePackage),
            focusedModulePackage = modulePackage,
            visiblePackages = visiblePackages,
        )

        assertEquals(CarePadPrimaryControllerTarget.Module(modulePackage), target)
    }

    @Test
    fun staleHomeTargetUsesRememberedVisibleModuleInstead() {
        val replacementPackage = "dev.carepad.module.gamesbios"
        val target = carePadRestoredContentTarget(
            destination = CarePadDestination.HOME,
            lastContentTarget = CarePadPrimaryControllerTarget.Module("removed.package"),
            focusedModulePackage = replacementPackage,
            visiblePackages = listOf(replacementPackage),
        )

        assertEquals(CarePadPrimaryControllerTarget.Module(replacementPackage), target)
    }

    @Test
    fun addModulesUsesSafeContentFallbackWhenNoActionableTargetExists() {
        val target = carePadRestoredContentTarget(
            destination = CarePadDestination.ADD_MODULES,
            lastContentTarget = CarePadPrimaryControllerTarget.Theme(AppThemeMode.DARK),
            focusedModulePackage = modulePackage,
            visiblePackages = visiblePackages,
        )

        assertEquals(CarePadPrimaryControllerTarget.None, target)
    }

    @Test
    fun detailsActionIsLimitedToHomeContent() {
        val contentHome = CarePadFocusState()
        val railHome = contentHome.onRailFocused(CarePadDestination.HOME)

        assertTrue(
            carePadDetailsControllerActionAllowed(
                focusState = contentHome,
                destination = CarePadDestination.HOME,
                focusedModulePackage = modulePackage,
                visiblePackages = visiblePackages,
            )
        )
        assertFalse(
            carePadDetailsControllerActionAllowed(
                focusState = railHome,
                destination = CarePadDestination.HOME,
                focusedModulePackage = modulePackage,
                visiblePackages = visiblePackages,
            )
        )
        assertFalse(
            carePadDetailsControllerActionAllowed(
                focusState = contentHome,
                destination = CarePadDestination.SETTINGS,
                focusedModulePackage = modulePackage,
                visiblePackages = visiblePackages,
            )
        )
    }
}
