package com.joel.thordoctor.modulelab

import carepad.contracts.CarePadItemAvailability
import carepad.contracts.CarePadSettingItem
import carepad.contracts.CarePadSettingOption
import carepad.contracts.CarePadSettingResult
import carepad.contracts.CarePadSettingsProtocol
import carepad.contracts.CarePadSettingsSnapshot
import carepad.contracts.CarePadSettingsSnapshotResult
import com.joel.thordoctor.core.settings.CarePadSettingsProvider

/** Debug fixture exposing the three C0 inline setting types. */
class LabSettingsProvider : CarePadSettingsProvider() {
    enum class SimulationMode {
        NORMAL,
        FORCE_REJECT,
        FORCE_STALE,
        FORCE_INCOMPATIBLE,
        FORCE_UNAVAILABLE
    }

    object State {
        var mode: SimulationMode = SimulationMode.NORMAL
        var revisionCounter: Long = 1L
        var revision: String = "1"
        var booleanValue: Boolean = false
        var choiceValue: String = "opengl"
        const val INFO_VALUE: String = "Lab-Fixture v0.1 (Debug)"

        fun reset() {
            mode = SimulationMode.NORMAL
            revisionCounter = 1L
            revision = "1"
            booleanValue = false
            choiceValue = "opengl"
        }

        /** Simulates an external catalog/semantic change, not an ordinary value write. */
        fun advanceRevision(): String {
            revisionCounter++
            revision = revisionCounter.toString()
            return revision
        }
    }

    override fun onGetSnapshot(): CarePadSettingsSnapshotResult = when (State.mode) {
        SimulationMode.FORCE_INCOMPATIBLE -> CarePadSettingsSnapshotResult.Incompatible(
            supportedContractVersion = 999,
            message = "Settings contract is not supported."
        )
        SimulationMode.FORCE_UNAVAILABLE -> CarePadSettingsSnapshotResult.Unavailable(
            "Settings are temporarily unavailable."
        )
        else -> CarePadSettingsSnapshotResult.Success(
            CarePadSettingsSnapshot(
                contractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
                catalogRevision = State.revision,
                items = listOf(
                    CarePadSettingItem.BooleanItem(
                        id = "lab.boolean_setting",
                        title = "Lab Turbo Mode",
                        description = "Enables turbo performance in module lab",
                        editable = true,
                        availability = CarePadItemAvailability.AVAILABLE,
                        value = State.booleanValue
                    ),
                    CarePadSettingItem.SingleChoiceItem(
                        id = "lab.choice_setting",
                        title = "Render Backend",
                        description = "Selects active graphics API backend",
                        editable = true,
                        availability = CarePadItemAvailability.AVAILABLE,
                        selectedOptionId = State.choiceValue,
                        options = listOf(
                            CarePadSettingOption("vulkan", "Vulkan"),
                            CarePadSettingOption("opengl", "OpenGL ES"),
                            CarePadSettingOption("sw", "Software")
                        )
                    ),
                    CarePadSettingItem.ReadOnlyInfoItem(
                        id = "lab.info_setting",
                        title = "Module Build",
                        description = "Fixture build information",
                        availability = CarePadItemAvailability.AVAILABLE,
                        value = State.INFO_VALUE
                    )
                )
            )
        )
    }

    override fun onWriteBoolean(
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): CarePadSettingResult = when (State.mode) {
        SimulationMode.FORCE_INCOMPATIBLE -> CarePadSettingResult.Incompatible(
            supportedContractVersion = 999,
            message = "Settings contract is not supported."
        )
        SimulationMode.FORCE_UNAVAILABLE -> CarePadSettingResult.Unavailable(
            "Settings are temporarily unavailable."
        )
        SimulationMode.FORCE_STALE -> CarePadSettingResult.Stale(
            currentCatalogRevision = State.revision,
            message = "Settings catalog changed."
        )
        SimulationMode.FORCE_REJECT -> CarePadSettingResult.Rejected(
            catalogRevision = State.revision,
            effectiveValueBoolean = State.booleanValue,
            message = "The requested value was rejected."
        )
        SimulationMode.NORMAL -> {
            if (catalogRevision != State.revision) {
                CarePadSettingResult.Stale(
                    currentCatalogRevision = State.revision,
                    message = "Settings catalog changed."
                )
            } else if (itemId != "lab.boolean_setting") {
                CarePadSettingResult.Rejected(
                    catalogRevision = State.revision,
                    message = "The requested setting is unavailable."
                )
            } else {
                State.booleanValue = value
                CarePadSettingResult.Applied(
                    catalogRevision = State.revision,
                    effectiveValueBoolean = State.booleanValue
                )
            }
        }
    }

    override fun onWriteSingleChoice(
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): CarePadSettingResult = when (State.mode) {
        SimulationMode.FORCE_INCOMPATIBLE -> CarePadSettingResult.Incompatible(
            supportedContractVersion = 999,
            message = "Settings contract is not supported."
        )
        SimulationMode.FORCE_UNAVAILABLE -> CarePadSettingResult.Unavailable(
            "Settings are temporarily unavailable."
        )
        SimulationMode.FORCE_STALE -> CarePadSettingResult.Stale(
            currentCatalogRevision = State.revision,
            message = "Settings catalog changed."
        )
        SimulationMode.FORCE_REJECT -> CarePadSettingResult.Rejected(
            catalogRevision = State.revision,
            effectiveSelectedOptionId = State.choiceValue,
            message = "The requested option was rejected."
        )
        SimulationMode.NORMAL -> {
            if (catalogRevision != State.revision) {
                CarePadSettingResult.Stale(
                    currentCatalogRevision = State.revision,
                    message = "Settings catalog changed."
                )
            } else if (itemId != "lab.choice_setting") {
                CarePadSettingResult.Rejected(
                    catalogRevision = State.revision,
                    message = "The requested setting is unavailable."
                )
            } else if (selectedOptionId !in setOf("vulkan", "opengl", "sw")) {
                CarePadSettingResult.Rejected(
                    catalogRevision = State.revision,
                    effectiveSelectedOptionId = State.choiceValue,
                    message = "The requested option is unavailable."
                )
            } else {
                State.choiceValue = selectedOptionId
                CarePadSettingResult.Applied(
                    catalogRevision = State.revision,
                    effectiveSelectedOptionId = State.choiceValue
                )
            }
        }
    }
}
