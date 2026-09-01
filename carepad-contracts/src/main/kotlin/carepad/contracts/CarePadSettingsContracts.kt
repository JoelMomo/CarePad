package carepad.contracts

/** Version of the inline settings wire contract. */
object CarePadSettingsProtocol {
    const val CONTRACT_VERSION = 1
}

/** Methods exposed via ContentProvider.call() for the inline settings RPC. */
object CarePadSettingsMethods {
    const val GET_SNAPSHOT = "dev.carepad.settings.GET_SNAPSHOT"
    const val WRITE_BOOLEAN = "dev.carepad.settings.WRITE_BOOLEAN"
    const val WRITE_SINGLE_CHOICE = "dev.carepad.settings.WRITE_SINGLE_CHOICE"
}

/** Authority naming helpers for module settings ContentProviders. */
object CarePadSettingsAuthorities {
    const val SUFFIX = ".carepad.settings"

    fun forPackage(packageName: String): String {
        require(packageName.isNotBlank()) { "Package name must not be blank" }
        return "$packageName$SUFFIX"
    }
}

/** Defensive transport limits protecting the Binder buffer and excessive allocations. */
object CarePadSettingsLimits {
    const val MAX_ITEMS_COUNT = 50
    const val MAX_OPTIONS_PER_CHOICE = 20
    const val MAX_ID_LENGTH = 64
    const val MAX_TITLE_LENGTH = 128
    const val MAX_DESCRIPTION_LENGTH = 512
    const val MAX_VALUE_LENGTH = 512
    const val MAX_ERROR_MESSAGE_LENGTH = 120
    const val MAX_REVISION_LENGTH = 64
}

/** Closed set of inline setting item types supported by CarePad C0. */
enum class CarePadSettingType {
    BOOLEAN,
    SINGLE_CHOICE,
    READ_ONLY_INFO
}

/** Availability status for an individual setting item. */
enum class CarePadItemAvailability {
    AVAILABLE,
    DISABLED,
    UNSUPPORTED
}

/** An option inside a SINGLE_CHOICE setting. */
data class CarePadSettingOption(
    val optionId: String,
    val label: String
) {
    init {
        requireValidId(optionId, "Option ID")
        require(label.isNotBlank()) { "Option label must not be blank" }
        require(label.length <= CarePadSettingsLimits.MAX_TITLE_LENGTH) {
            "Option label exceeds max length ${CarePadSettingsLimits.MAX_TITLE_LENGTH}"
        }
    }
}

sealed class CarePadSettingItem {
    abstract val id: String
    abstract val type: CarePadSettingType
    abstract val title: String
    abstract val description: String?
    abstract val editable: Boolean
    abstract val availability: CarePadItemAvailability
    abstract val errorMessage: String?

    protected fun validateCommon() {
        requireValidId(id, "Setting ID")
        require(title.isNotBlank()) { "Title must not be blank" }
        require(title.length <= CarePadSettingsLimits.MAX_TITLE_LENGTH) {
            "Title exceeds max length ${CarePadSettingsLimits.MAX_TITLE_LENGTH}"
        }
        description?.let {
            require(it.length <= CarePadSettingsLimits.MAX_DESCRIPTION_LENGTH) {
                "Description exceeds max length ${CarePadSettingsLimits.MAX_DESCRIPTION_LENGTH}"
            }
        }
        requireValidOptionalMessage(errorMessage)
    }

    data class BooleanItem(
        override val id: String,
        override val title: String,
        override val description: String? = null,
        override val editable: Boolean = true,
        override val availability: CarePadItemAvailability = CarePadItemAvailability.AVAILABLE,
        override val errorMessage: String? = null,
        val value: Boolean
    ) : CarePadSettingItem() {
        override val type: CarePadSettingType = CarePadSettingType.BOOLEAN
        init { validateCommon() }
    }

    data class SingleChoiceItem(
        override val id: String,
        override val title: String,
        override val description: String? = null,
        override val editable: Boolean = true,
        override val availability: CarePadItemAvailability = CarePadItemAvailability.AVAILABLE,
        override val errorMessage: String? = null,
        val selectedOptionId: String,
        val options: List<CarePadSettingOption>
    ) : CarePadSettingItem() {
        override val type: CarePadSettingType = CarePadSettingType.SINGLE_CHOICE
        init {
            validateCommon()
            requireValidId(selectedOptionId, "Selected option ID")
            require(options.isNotEmpty()) { "Single choice setting must have at least one option" }
            require(options.size <= CarePadSettingsLimits.MAX_OPTIONS_PER_CHOICE) {
                "Single choice exceeds max options count ${CarePadSettingsLimits.MAX_OPTIONS_PER_CHOICE}"
            }
            require(options.any { it.optionId == selectedOptionId }) {
                "Selected option ID '$selectedOptionId' must match one of the available options"
            }
            require(options.map { it.optionId }.toSet().size == options.size) {
                "Option IDs must be unique"
            }
        }
    }

    data class ReadOnlyInfoItem(
        override val id: String,
        override val title: String,
        override val description: String? = null,
        override val availability: CarePadItemAvailability = CarePadItemAvailability.AVAILABLE,
        override val errorMessage: String? = null,
        val value: String
    ) : CarePadSettingItem() {
        override val type: CarePadSettingType = CarePadSettingType.READ_ONLY_INFO
        override val editable: Boolean = false
        init {
            validateCommon()
            require(value.length <= CarePadSettingsLimits.MAX_VALUE_LENGTH) {
                "Read-only value exceeds max length ${CarePadSettingsLimits.MAX_VALUE_LENGTH}"
            }
        }
    }
}

/**
 * Snapshot of inline settings returned by a module.
 *
 * [catalogRevision] is an opaque equality token for catalog/semantic changes that can make a
 * previously prepared write obsolete. Ordinary value changes do not, by themselves, require a
 * new revision.
 */
data class CarePadSettingsSnapshot(
    val contractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
    val catalogRevision: String,
    val items: List<CarePadSettingItem>
) {
    init {
        require(contractVersion > 0) { "Contract version must be positive" }
        requireValidRevision(catalogRevision)
        require(items.size <= CarePadSettingsLimits.MAX_ITEMS_COUNT) {
            "Items count exceeds max limit ${CarePadSettingsLimits.MAX_ITEMS_COUNT}"
        }
        require(items.map { it.id }.toSet().size == items.size) {
            "Setting item IDs must be unique within a snapshot"
        }
    }
}

/** Result of an operation to write/update a setting item. */
sealed class CarePadSettingResult {
    data class Applied(
        val catalogRevision: String,
        val effectiveValueBoolean: Boolean? = null,
        val effectiveSelectedOptionId: String? = null
    ) : CarePadSettingResult() {
        init {
            requireValidRevision(catalogRevision)
            require((effectiveValueBoolean != null) xor (effectiveSelectedOptionId != null)) {
                "APPLIED must contain exactly one effective value"
            }
            effectiveSelectedOptionId?.let { requireValidId(it, "Effective option ID") }
        }
    }

    data class Rejected(
        val catalogRevision: String,
        val effectiveValueBoolean: Boolean? = null,
        val effectiveSelectedOptionId: String? = null,
        val message: String? = null
    ) : CarePadSettingResult() {
        init {
            requireValidRevision(catalogRevision)
            require(!(effectiveValueBoolean != null && effectiveSelectedOptionId != null)) {
                "REJECTED cannot contain multiple effective value representations"
            }
            effectiveSelectedOptionId?.let { requireValidId(it, "Effective option ID") }
            requireValidOptionalMessage(message)
        }
    }

    data class Stale(
        val currentCatalogRevision: String,
        val message: String? = null
    ) : CarePadSettingResult() {
        init {
            requireValidRevision(currentCatalogRevision)
            requireValidOptionalMessage(message)
        }
    }

    data class Unavailable(val message: String? = null) : CarePadSettingResult() {
        init { requireValidOptionalMessage(message) }
    }

    data class Incompatible(
        val supportedContractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        val message: String? = null
    ) : CarePadSettingResult() {
        init {
            require(supportedContractVersion > 0) { "Supported contract version must be positive" }
            requireValidOptionalMessage(message)
        }
    }
}

/** Result of querying a module's settings snapshot. */
sealed class CarePadSettingsSnapshotResult {
    data class Success(val snapshot: CarePadSettingsSnapshot) : CarePadSettingsSnapshotResult()

    data class Unavailable(val message: String? = null) : CarePadSettingsSnapshotResult() {
        init { requireValidOptionalMessage(message) }
    }

    data class Incompatible(
        val supportedContractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        val message: String? = null
    ) : CarePadSettingsSnapshotResult() {
        init {
            require(supportedContractVersion > 0) { "Supported contract version must be positive" }
            requireValidOptionalMessage(message)
        }
    }
}

private fun requireValidId(value: String, label: String) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= CarePadSettingsLimits.MAX_ID_LENGTH) {
        "$label exceeds max length ${CarePadSettingsLimits.MAX_ID_LENGTH}"
    }
}

private fun requireValidRevision(value: String) {
    require(value.isNotBlank()) { "Catalog revision must not be blank" }
    require(value.length <= CarePadSettingsLimits.MAX_REVISION_LENGTH) {
        "Catalog revision exceeds max length ${CarePadSettingsLimits.MAX_REVISION_LENGTH}"
    }
}

private fun requireValidOptionalMessage(message: String?) {
    message?.let {
        require(it.length <= CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH) {
            "Error message exceeds max length ${CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH}"
        }
    }
}
