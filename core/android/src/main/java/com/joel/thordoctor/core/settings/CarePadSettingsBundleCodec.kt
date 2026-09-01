package com.joel.thordoctor.core.settings

import android.os.Build
import android.os.Bundle
import carepad.contracts.CarePadItemAvailability
import carepad.contracts.CarePadSettingItem
import carepad.contracts.CarePadSettingOption
import carepad.contracts.CarePadSettingResult
import carepad.contracts.CarePadSettingType
import carepad.contracts.CarePadSettingsLimits
import carepad.contracts.CarePadSettingsProtocol
import carepad.contracts.CarePadSettingsSnapshot
import carepad.contracts.CarePadSettingsSnapshotResult

/** Strict Bundle codec for the C0 inline-settings RPC. Malformed payloads fail closed. */
object CarePadSettingsBundleCodec {
    const val KEY_CONTRACT_VERSION = "carepad.settings.req.CONTRACT_VERSION"
    const val KEY_CATALOG_REVISION = "carepad.settings.req.CATALOG_REVISION"
    const val KEY_ITEM_ID = "carepad.settings.req.ITEM_ID"
    const val KEY_VALUE_BOOLEAN = "carepad.settings.req.VALUE_BOOLEAN"
    const val KEY_SELECTED_OPTION_ID = "carepad.settings.req.SELECTED_OPTION_ID"

    const val KEY_RES_STATUS = "carepad.settings.res.STATUS"
    const val KEY_RES_RESULT_CODE = "carepad.settings.res.RESULT_CODE"
    const val KEY_RES_CONTRACT_VERSION = "carepad.settings.res.CONTRACT_VERSION"
    const val KEY_RES_CATALOG_REVISION = "carepad.settings.res.CATALOG_REVISION"
    const val KEY_RES_ITEMS = "carepad.settings.res.ITEMS"
    const val KEY_RES_EFFECTIVE_BOOLEAN = "carepad.settings.res.EFFECTIVE_BOOLEAN"
    const val KEY_RES_EFFECTIVE_OPTION_ID = "carepad.settings.res.EFFECTIVE_OPTION_ID"
    const val KEY_RES_ERROR_MESSAGE = "carepad.settings.res.ERROR_MESSAGE"

    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_UNAVAILABLE = "UNAVAILABLE"
    const val STATUS_INCOMPATIBLE = "INCOMPATIBLE"

    const val CODE_APPLIED = "APPLIED"
    const val CODE_REJECTED = "REJECTED"
    const val CODE_STALE = "STALE"
    const val CODE_UNAVAILABLE = "UNAVAILABLE"
    const val CODE_INCOMPATIBLE = "INCOMPATIBLE"

    private const val ITEM_ID = "id"
    private const val ITEM_TYPE = "type"
    private const val ITEM_TITLE = "title"
    private const val ITEM_DESCRIPTION = "description"
    private const val ITEM_EDITABLE = "editable"
    private const val ITEM_AVAILABILITY = "availability"
    private const val ITEM_ERROR_MESSAGE = "errorMessage"
    private const val ITEM_VALUE_BOOLEAN = "val_bool"
    private const val ITEM_SELECTED_OPTION_ID = "val_selected_opt"
    private const val ITEM_OPTIONS = "options"
    private const val ITEM_VALUE_STRING = "val_str"
    private const val OPTION_ID = "opt_id"
    private const val OPTION_LABEL = "opt_label"

    data class WriteBooleanRequest(
        val contractVersion: Int,
        val catalogRevision: String,
        val itemId: String,
        val value: Boolean
    )

    data class WriteSingleChoiceRequest(
        val contractVersion: Int,
        val catalogRevision: String,
        val itemId: String,
        val selectedOptionId: String
    )

    fun encodeGetSnapshotRequest(
        contractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION
    ): Bundle = Bundle().apply {
        putInt(KEY_CONTRACT_VERSION, contractVersion)
    }

    fun decodeGetSnapshotContractVersion(bundle: Bundle?): Int? = decodeOrNull {
        require(bundle != null && bundle.containsKey(KEY_CONTRACT_VERSION))
        val version = bundle.getInt(KEY_CONTRACT_VERSION)
        require(version > 0)
        version
    }

    fun encodeSnapshotSuccess(snapshot: CarePadSettingsSnapshot): Bundle = Bundle().apply {
        putString(KEY_RES_STATUS, STATUS_SUCCESS)
        putInt(KEY_RES_CONTRACT_VERSION, snapshot.contractVersion)
        putString(KEY_RES_CATALOG_REVISION, snapshot.catalogRevision)
        putParcelableArrayList(
            KEY_RES_ITEMS,
            ArrayList(snapshot.items.map(::encodeItem))
        )
    }

    fun encodeSnapshotUnavailable(message: String?): Bundle = Bundle().apply {
        putString(KEY_RES_STATUS, STATUS_UNAVAILABLE)
        sanitizeOutgoingMessage(message)?.let { putString(KEY_RES_ERROR_MESSAGE, it) }
    }

    fun encodeSnapshotIncompatible(
        supportedContractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        message: String? = null
    ): Bundle = Bundle().apply {
        putString(KEY_RES_STATUS, STATUS_INCOMPATIBLE)
        putInt(KEY_RES_CONTRACT_VERSION, supportedContractVersion)
        sanitizeOutgoingMessage(message)?.let { putString(KEY_RES_ERROR_MESSAGE, it) }
    }

    fun decodeSnapshotResult(bundle: Bundle?): CarePadSettingsSnapshotResult = decodeOrUnavailableSnapshot {
        require(bundle != null && bundle.containsKey(KEY_RES_STATUS))
        when (val status = bundle.getString(KEY_RES_STATUS)) {
            STATUS_SUCCESS -> decodeSnapshotSuccess(bundle)
            STATUS_INCOMPATIBLE -> {
                require(bundle.containsKey(KEY_RES_CONTRACT_VERSION))
                val version = bundle.getInt(KEY_RES_CONTRACT_VERSION)
                require(version > 0)
                CarePadSettingsSnapshotResult.Incompatible(
                    supportedContractVersion = version,
                    message = optionalBoundedString(bundle, KEY_RES_ERROR_MESSAGE, CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH)
                )
            }
            STATUS_UNAVAILABLE -> CarePadSettingsSnapshotResult.Unavailable(
                optionalBoundedString(bundle, KEY_RES_ERROR_MESSAGE, CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH)
            )
            else -> error("Unknown snapshot status: $status")
        }
    }

    private fun decodeSnapshotSuccess(bundle: Bundle): CarePadSettingsSnapshotResult {
        require(bundle.containsKey(KEY_RES_CONTRACT_VERSION))
        val version = bundle.getInt(KEY_RES_CONTRACT_VERSION)
        require(version > 0)
        if (version != CarePadSettingsProtocol.CONTRACT_VERSION) {
            return CarePadSettingsSnapshotResult.Incompatible(supportedContractVersion = version)
        }

        val revision = requiredBoundedString(
            bundle,
            KEY_RES_CATALOG_REVISION,
            CarePadSettingsLimits.MAX_REVISION_LENGTH
        )
        val itemBundles = requiredBundleList(bundle, KEY_RES_ITEMS)
        require(itemBundles.size <= CarePadSettingsLimits.MAX_ITEMS_COUNT)
        val items = itemBundles.map(::decodeItemStrict)

        return CarePadSettingsSnapshotResult.Success(
            CarePadSettingsSnapshot(
                contractVersion = version,
                catalogRevision = revision,
                items = items
            )
        )
    }

    fun encodeWriteBooleanRequest(
        contractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): Bundle = Bundle().apply {
        putInt(KEY_CONTRACT_VERSION, contractVersion)
        putString(KEY_CATALOG_REVISION, catalogRevision)
        putString(KEY_ITEM_ID, itemId)
        putBoolean(KEY_VALUE_BOOLEAN, value)
    }

    fun decodeWriteBooleanRequest(bundle: Bundle?): WriteBooleanRequest? = decodeOrNull {
        require(bundle != null)
        require(bundle.containsKey(KEY_CONTRACT_VERSION))
        require(bundle.containsKey(KEY_VALUE_BOOLEAN))
        require(!bundle.containsKey(KEY_SELECTED_OPTION_ID))
        val version = bundle.getInt(KEY_CONTRACT_VERSION)
        require(version > 0)
        val revision = requiredBoundedString(bundle, KEY_CATALOG_REVISION, CarePadSettingsLimits.MAX_REVISION_LENGTH)
        val itemId = requiredBoundedString(bundle, KEY_ITEM_ID, CarePadSettingsLimits.MAX_ID_LENGTH)
        val value = bundle.getBoolean(KEY_VALUE_BOOLEAN)
        WriteBooleanRequest(version, revision, itemId, value)
    }

    fun encodeWriteSingleChoiceRequest(
        contractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): Bundle = Bundle().apply {
        putInt(KEY_CONTRACT_VERSION, contractVersion)
        putString(KEY_CATALOG_REVISION, catalogRevision)
        putString(KEY_ITEM_ID, itemId)
        putString(KEY_SELECTED_OPTION_ID, selectedOptionId)
    }

    fun decodeWriteSingleChoiceRequest(bundle: Bundle?): WriteSingleChoiceRequest? = decodeOrNull {
        require(bundle != null)
        require(bundle.containsKey(KEY_CONTRACT_VERSION))
        require(!bundle.containsKey(KEY_VALUE_BOOLEAN))
        val version = bundle.getInt(KEY_CONTRACT_VERSION)
        require(version > 0)
        val revision = requiredBoundedString(bundle, KEY_CATALOG_REVISION, CarePadSettingsLimits.MAX_REVISION_LENGTH)
        val itemId = requiredBoundedString(bundle, KEY_ITEM_ID, CarePadSettingsLimits.MAX_ID_LENGTH)
        val optionId = requiredBoundedString(bundle, KEY_SELECTED_OPTION_ID, CarePadSettingsLimits.MAX_ID_LENGTH)
        WriteSingleChoiceRequest(version, revision, itemId, optionId)
    }

    fun encodeSettingResult(result: CarePadSettingResult): Bundle = Bundle().apply {
        when (result) {
            is CarePadSettingResult.Applied -> {
                putString(KEY_RES_RESULT_CODE, CODE_APPLIED)
                putString(KEY_RES_CATALOG_REVISION, result.catalogRevision)
                result.effectiveValueBoolean?.let { putBoolean(KEY_RES_EFFECTIVE_BOOLEAN, it) }
                result.effectiveSelectedOptionId?.let { putString(KEY_RES_EFFECTIVE_OPTION_ID, it) }
            }
            is CarePadSettingResult.Rejected -> {
                putString(KEY_RES_RESULT_CODE, CODE_REJECTED)
                putString(KEY_RES_CATALOG_REVISION, result.catalogRevision)
                result.effectiveValueBoolean?.let { putBoolean(KEY_RES_EFFECTIVE_BOOLEAN, it) }
                result.effectiveSelectedOptionId?.let { putString(KEY_RES_EFFECTIVE_OPTION_ID, it) }
                sanitizeOutgoingMessage(result.message)?.let { putString(KEY_RES_ERROR_MESSAGE, it) }
            }
            is CarePadSettingResult.Stale -> {
                putString(KEY_RES_RESULT_CODE, CODE_STALE)
                putString(KEY_RES_CATALOG_REVISION, result.currentCatalogRevision)
                sanitizeOutgoingMessage(result.message)?.let { putString(KEY_RES_ERROR_MESSAGE, it) }
            }
            is CarePadSettingResult.Unavailable -> {
                putString(KEY_RES_RESULT_CODE, CODE_UNAVAILABLE)
                sanitizeOutgoingMessage(result.message)?.let { putString(KEY_RES_ERROR_MESSAGE, it) }
            }
            is CarePadSettingResult.Incompatible -> {
                putString(KEY_RES_RESULT_CODE, CODE_INCOMPATIBLE)
                putInt(KEY_RES_CONTRACT_VERSION, result.supportedContractVersion)
                sanitizeOutgoingMessage(result.message)?.let { putString(KEY_RES_ERROR_MESSAGE, it) }
            }
        }
    }

    fun decodeBooleanSettingResult(bundle: Bundle?): CarePadSettingResult =
        decodeSettingResult(bundle, ExpectedValue.BOOLEAN)

    fun decodeSingleChoiceSettingResult(bundle: Bundle?): CarePadSettingResult =
        decodeSettingResult(bundle, ExpectedValue.SINGLE_CHOICE)

    private fun decodeSettingResult(
        bundle: Bundle?,
        expectedValue: ExpectedValue
    ): CarePadSettingResult = decodeOrUnavailableSetting {
        require(bundle != null && bundle.containsKey(KEY_RES_RESULT_CODE))
        val code = bundle.getString(KEY_RES_RESULT_CODE) ?: error("Missing result code")
        val message = optionalBoundedString(
            bundle,
            KEY_RES_ERROR_MESSAGE,
            CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH
        )
        val hasBoolean = bundle.containsKey(KEY_RES_EFFECTIVE_BOOLEAN)
        val hasOption = bundle.containsKey(KEY_RES_EFFECTIVE_OPTION_ID)

        fun revision(): String = requiredBoundedString(
            bundle,
            KEY_RES_CATALOG_REVISION,
            CarePadSettingsLimits.MAX_REVISION_LENGTH
        )

        fun expectedBoolean(): Boolean {
            require(expectedValue == ExpectedValue.BOOLEAN && hasBoolean && !hasOption)
            return bundle.getBoolean(KEY_RES_EFFECTIVE_BOOLEAN)
        }

        fun expectedOption(): String {
            require(expectedValue == ExpectedValue.SINGLE_CHOICE && hasOption && !hasBoolean)
            return requiredBoundedString(bundle, KEY_RES_EFFECTIVE_OPTION_ID, CarePadSettingsLimits.MAX_ID_LENGTH)
        }

        when (code) {
            CODE_APPLIED -> when (expectedValue) {
                ExpectedValue.BOOLEAN -> CarePadSettingResult.Applied(
                    catalogRevision = revision(),
                    effectiveValueBoolean = expectedBoolean()
                )
                ExpectedValue.SINGLE_CHOICE -> CarePadSettingResult.Applied(
                    catalogRevision = revision(),
                    effectiveSelectedOptionId = expectedOption()
                )
            }
            CODE_REJECTED -> {
                require(!(hasBoolean && hasOption))
                when {
                    hasBoolean -> {
                        require(expectedValue == ExpectedValue.BOOLEAN)
                        CarePadSettingResult.Rejected(
                            catalogRevision = revision(),
                            effectiveValueBoolean = bundle.getBoolean(KEY_RES_EFFECTIVE_BOOLEAN),
                            message = message
                        )
                    }
                    hasOption -> {
                        require(expectedValue == ExpectedValue.SINGLE_CHOICE)
                        CarePadSettingResult.Rejected(
                            catalogRevision = revision(),
                            effectiveSelectedOptionId = requiredBoundedString(
                                bundle,
                                KEY_RES_EFFECTIVE_OPTION_ID,
                                CarePadSettingsLimits.MAX_ID_LENGTH
                            ),
                            message = message
                        )
                    }
                    else -> CarePadSettingResult.Rejected(
                        catalogRevision = revision(),
                        message = message
                    )
                }
            }
            CODE_STALE -> {
                require(!hasBoolean && !hasOption)
                CarePadSettingResult.Stale(revision(), message)
            }
            CODE_UNAVAILABLE -> {
                require(!hasBoolean && !hasOption)
                CarePadSettingResult.Unavailable(message)
            }
            CODE_INCOMPATIBLE -> {
                require(!hasBoolean && !hasOption)
                require(bundle.containsKey(KEY_RES_CONTRACT_VERSION))
                val version = bundle.getInt(KEY_RES_CONTRACT_VERSION)
                require(version > 0)
                CarePadSettingResult.Incompatible(version, message)
            }
            else -> error("Unknown result code: $code")
        }
    }

    private fun encodeItem(item: CarePadSettingItem): Bundle = Bundle().apply {
        putString(ITEM_ID, item.id)
        putString(ITEM_TYPE, item.type.name)
        putString(ITEM_TITLE, item.title)
        item.description?.let { putString(ITEM_DESCRIPTION, it) }
        putBoolean(ITEM_EDITABLE, item.editable)
        putString(ITEM_AVAILABILITY, item.availability.name)
        sanitizeOutgoingMessage(item.errorMessage)?.let { putString(ITEM_ERROR_MESSAGE, it) }

        when (item) {
            is CarePadSettingItem.BooleanItem -> putBoolean(ITEM_VALUE_BOOLEAN, item.value)
            is CarePadSettingItem.SingleChoiceItem -> {
                putString(ITEM_SELECTED_OPTION_ID, item.selectedOptionId)
                putParcelableArrayList(
                    ITEM_OPTIONS,
                    ArrayList(item.options.map { option ->
                        Bundle().apply {
                            putString(OPTION_ID, option.optionId)
                            putString(OPTION_LABEL, option.label)
                        }
                    })
                )
            }
            is CarePadSettingItem.ReadOnlyInfoItem -> putString(ITEM_VALUE_STRING, item.value)
        }
    }

    private fun decodeItemStrict(bundle: Bundle): CarePadSettingItem {
        val id = requiredBoundedString(bundle, ITEM_ID, CarePadSettingsLimits.MAX_ID_LENGTH)
        val type = CarePadSettingType.valueOf(requiredBoundedString(bundle, ITEM_TYPE, 32))
        val title = requiredBoundedString(bundle, ITEM_TITLE, CarePadSettingsLimits.MAX_TITLE_LENGTH)
        val description = optionalBoundedString(bundle, ITEM_DESCRIPTION, CarePadSettingsLimits.MAX_DESCRIPTION_LENGTH)
        require(bundle.containsKey(ITEM_EDITABLE))
        val editable = bundle.getBoolean(ITEM_EDITABLE)
        val availability = CarePadItemAvailability.valueOf(
            requiredBoundedString(bundle, ITEM_AVAILABILITY, 32)
        )
        val errorMessage = optionalBoundedString(
            bundle,
            ITEM_ERROR_MESSAGE,
            CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH
        )

        return when (type) {
            CarePadSettingType.BOOLEAN -> {
                require(bundle.containsKey(ITEM_VALUE_BOOLEAN))
                require(!bundle.containsKey(ITEM_SELECTED_OPTION_ID))
                require(!bundle.containsKey(ITEM_OPTIONS))
                require(!bundle.containsKey(ITEM_VALUE_STRING))
                CarePadSettingItem.BooleanItem(
                    id = id,
                    title = title,
                    description = description,
                    editable = editable,
                    availability = availability,
                    errorMessage = errorMessage,
                    value = bundle.getBoolean(ITEM_VALUE_BOOLEAN)
                )
            }
            CarePadSettingType.SINGLE_CHOICE -> {
                require(!bundle.containsKey(ITEM_VALUE_BOOLEAN))
                require(!bundle.containsKey(ITEM_VALUE_STRING))
                val selected = requiredBoundedString(
                    bundle,
                    ITEM_SELECTED_OPTION_ID,
                    CarePadSettingsLimits.MAX_ID_LENGTH
                )
                val optionBundles = requiredBundleList(bundle, ITEM_OPTIONS)
                require(optionBundles.isNotEmpty())
                require(optionBundles.size <= CarePadSettingsLimits.MAX_OPTIONS_PER_CHOICE)
                val options = optionBundles.map { optionBundle ->
                    CarePadSettingOption(
                        optionId = requiredBoundedString(
                            optionBundle,
                            OPTION_ID,
                            CarePadSettingsLimits.MAX_ID_LENGTH
                        ),
                        label = requiredBoundedString(
                            optionBundle,
                            OPTION_LABEL,
                            CarePadSettingsLimits.MAX_TITLE_LENGTH
                        )
                    )
                }
                CarePadSettingItem.SingleChoiceItem(
                    id = id,
                    title = title,
                    description = description,
                    editable = editable,
                    availability = availability,
                    errorMessage = errorMessage,
                    selectedOptionId = selected,
                    options = options
                )
            }
            CarePadSettingType.READ_ONLY_INFO -> {
                require(!editable)
                require(!bundle.containsKey(ITEM_VALUE_BOOLEAN))
                require(!bundle.containsKey(ITEM_SELECTED_OPTION_ID))
                require(!bundle.containsKey(ITEM_OPTIONS))
                val value = requiredStringAllowEmpty(
                    bundle,
                    ITEM_VALUE_STRING,
                    CarePadSettingsLimits.MAX_VALUE_LENGTH
                )
                CarePadSettingItem.ReadOnlyInfoItem(
                    id = id,
                    title = title,
                    description = description,
                    availability = availability,
                    errorMessage = errorMessage,
                    value = value
                )
            }
        }
    }

    private fun requiredBoundedString(bundle: Bundle, key: String, maxLength: Int): String {
        require(bundle.containsKey(key))
        val value = bundle.getString(key) ?: error("Missing string")
        require(value.isNotBlank() && value.length <= maxLength)
        return value
    }

    private fun requiredStringAllowEmpty(bundle: Bundle, key: String, maxLength: Int): String {
        require(bundle.containsKey(key))
        val value = bundle.getString(key) ?: error("Missing string")
        require(value.length <= maxLength)
        return value
    }

    private fun optionalBoundedString(bundle: Bundle, key: String, maxLength: Int): String? {
        if (!bundle.containsKey(key)) return null
        val value = bundle.getString(key) ?: error("Invalid optional string")
        require(value.length <= maxLength)
        return value
    }

    private fun requiredBundleList(bundle: Bundle, key: String): List<Bundle> {
        require(bundle.containsKey(key))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelableArrayList(key, Bundle::class.java)
                ?: error("Missing bundle list")
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelableArrayList<Bundle>(key)
                ?: error("Missing bundle list")
        }
    }

    private fun sanitizeOutgoingMessage(raw: String?): String? = raw?.let {
        val sanitized = it.replace('\n', ' ').replace('\r', ' ').trim()
        sanitized.take(CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH)
    }

    private inline fun <T> decodeOrNull(block: () -> T): T? =
        runCatching(block).getOrNull()

    private inline fun decodeOrUnavailableSnapshot(
        block: () -> CarePadSettingsSnapshotResult
    ): CarePadSettingsSnapshotResult = runCatching(block).getOrElse {
        CarePadSettingsSnapshotResult.Unavailable()
    }

    private inline fun decodeOrUnavailableSetting(
        block: () -> CarePadSettingResult
    ): CarePadSettingResult = runCatching(block).getOrElse {
        CarePadSettingResult.Unavailable()
    }

    private enum class ExpectedValue {
        BOOLEAN,
        SINGLE_CHOICE
    }
}
