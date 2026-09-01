package com.joel.thordoctor.modulelab

import carepad.contracts.CarePadItemAvailability
import carepad.contracts.CarePadSettingItem
import carepad.contracts.CarePadSettingOption
import carepad.contracts.CarePadSettingResult
import carepad.contracts.CarePadSettingsProtocol
import carepad.contracts.CarePadSettingsSnapshot
import carepad.contracts.CarePadSettingsSnapshotResult
import com.joel.thordoctor.core.settings.CarePadSettingsProvider

/**
 * Fixture ContentProvider in module-lab exposing the 3 approved inline settings types
 * and handling request/response lifecycle.
 */
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

        fun advanceRevision(): String {
            revisionCounter++
            revision = revisionCounter.toString()
            return revision
        }
    }

    override fun onGetSnapshot(): CarePadSettingsSnapshotResult {
        return when (State.mode) {
            SimulationMode.FORCE_INCOMPATIBLE -> {
                CarePadSettingsSnapshotResult.Incompatible(
                    supportedContractVersion = 999,
                    message = "Simulated incompatible snapshot"
                )
            }

            SimulationMode.FORCE_UNAVAILABLE -> {
                CarePadSettingsSnapshotResult.Unavailable("Simulated unavailable snapshot")
            }

            else -> {
                val items = listOf(
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

                CarePadSettingsSnapshotResult.Success(
                    CarePadSettingsSnapshot(
                        contractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
                        catalogRevision = State.revision,
                        items = items
                    )
                )
            }
        }
    }

    override fun onWriteBoolean(
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): CarePadSettingResult {
        return when (State.mode) {
            SimulationMode.FORCE_INCOMPATIBLE -> {
                CarePadSettingResult.Incompatible(
                    supportedContractVersion = 999,
                    message = "Simulated incompatible write"
                )
            }

            SimulationMode.FORCE_UNAVAILABLE -> {
                CarePadSettingResult.Unavailable("Simulated unavailable write")
            }

            SimulationMode.FORCE_STALE -> {
                CarePadSettingResult.Stale(
                    currentCatalogRevision = State.revision,
                    message = "Simulated stale revision"
                )
            }

            SimulationMode.FORCE_REJECT -> {
                CarePadSettingResult.Rejected(
                    catalogRevision = State.revision,
                    effectiveValueBoolean = State.booleanValue,
                    message = "Simulated write rejection"
                )
            }

            SimulationMode.NORMAL -> {
                if (catalogRevision != State.revision) {
                    return CarePadSettingResult.Stale(
                        currentCatalogRevision = State.revision,
                        message = "Revision mismatch: expected $catalogRevision but current is ${State.revision}"
                    )
                }

                if (itemId == "lab.boolean_setting") {
                    State.booleanValue = value
                    val newRev = State.advanceRevision()
                    CarePadSettingResult.Applied(
                        catalogRevision = newRev,
                        effectiveValueBoolean = State.booleanValue
                    )
                } else {
                    CarePadSettingResult.Rejected(
                        catalogRevision = State.revision,
                        message = "Unknown boolean setting ID '$itemId'"
                    )
                }
            }
        }
    }

    override fun onWriteSingleChoice(
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): CarePadSettingResult {
        return when (State.mode) {
            SimulationMode.FORCE_INCOMPATIBLE -> {
                CarePadSettingResult.Incompatible(
                    supportedContractVersion = 999,
                    message = "Simulated incompatible write"
                )
            }

            SimulationMode.FORCE_UNAVAILABLE -> {
                CarePadSettingResult.Unavailable("Simulated unavailable write")
            }

            SimulationMode.FORCE_STALE -> {
                CarePadSettingResult.Stale(
                    currentCatalogRevision = State.revision,
                    message = "Simulated stale revision"
                )
            }

            SimulationMode.FORCE_REJECT -> {
                CarePadSettingResult.Rejected(
                    catalogRevision = State.revision,
                    effectiveSelectedOptionId = State.choiceValue,
                    message = "Simulated write rejection"
                )
            }

            SimulationMode.NORMAL -> {
                if (catalogRevision != State.revision) {
                    return CarePadSettingResult.Stale(
                        currentCatalogRevision = State.revision,
                        message = "Revision mismatch: expected $catalogRevision but current is ${State.revision}"
                    )
                }

                if (itemId == "lab.choice_setting") {
                    val validOptions = setOf("vulkan", "opengl", "sw")
                    if (selectedOptionId !in validOptions) {
                        return CarePadSettingResult.Rejected(
                            catalogRevision = State.revision,
                            effectiveSelectedOptionId = State.choiceValue,
                            message = "Invalid option '$selectedOptionId'"
                        )
                    }

                    State.choiceValue = selectedOptionId
                    val newRev = State.advanceRevision()
                    CarePadSettingResult.Applied(
                        catalogRevision = newRev,
                        effectiveSelectedOptionId = State.choiceValue
                    )
                } else {
                    CarePadSettingResult.Rejected(
                        catalogRevision = State.revision,
                        message = "Unknown single-choice setting ID '$itemId'"
                    )
                }
            }
        }
    }
}
