package com.joel.thordoctor.core.settings

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

/**
 * Encodes and decodes settings RPC payloads into compact, narrow Android Bundles.
 *
 * Implements strict defensive bounds to protect the Android Binder buffer and
 * avoid TransactionTooLargeException or memory exhaustion.
 */
object CarePadSettingsBundleCodec {

    // Request bundle keys
    const val KEY_CONTRACT_VERSION = "carepad.settings.req.CONTRACT_VERSION"
    const val KEY_CATALOG_REVISION = "carepad.settings.req.CATALOG_REVISION"
    const val KEY_ITEM_ID = "carepad.settings.req.ITEM_ID"
    const val KEY_VALUE_BOOLEAN = "carepad.settings.req.VALUE_BOOLEAN"
    const val KEY_SELECTED_OPTION_ID = "carepad.settings.req.SELECTED_OPTION_ID"

    // Response bundle keys
    const val KEY_RES_STATUS = "carepad.settings.res.STATUS"
    const val KEY_RES_RESULT_CODE = "carepad.settings.res.RESULT_CODE"
    const val KEY_RES_CONTRACT_VERSION = "carepad.settings.res.CONTRACT_VERSION"
    const val KEY_RES_CATALOG_REVISION = "carepad.settings.res.CATALOG_REVISION"
    const val KEY_RES_ITEMS = "carepad.settings.res.ITEMS"
    const val KEY_RES_EFFECTIVE_BOOLEAN = "carepad.settings.res.EFFECTIVE_BOOLEAN"
    const val KEY_RES_EFFECTIVE_OPTION_ID = "carepad.settings.res.EFFECTIVE_OPTION_ID"
    const val KEY_RES_ERROR_MESSAGE = "carepad.settings.res.ERROR_MESSAGE"

    // Status strings
    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_UNAVAILABLE = "UNAVAILABLE"
    const val STATUS_INCOMPATIBLE = "INCOMPATIBLE"

    // Result code strings
    const val CODE_APPLIED = "APPLIED"
    const val CODE_REJECTED = "REJECTED"
    const val CODE_STALE = "STALE"
    const val CODE_UNAVAILABLE = "UNAVAILABLE"
    const val CODE_INCOMPATIBLE = "INCOMPATIBLE"

    // Item bundle keys
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

    // --- Snapshot encoding & decoding ---

    fun encodeSnapshotSuccess(snapshot: CarePadSettingsSnapshot): Bundle {
        val bundle = Bundle()
        bundle.putString(KEY_RES_STATUS, STATUS_SUCCESS)
        bundle.putInt(KEY_RES_CONTRACT_VERSION, snapshot.contractVersion)
        bundle.putString(KEY_RES_CATALOG_REVISION, snapshot.catalogRevision)

        val itemsBundles = ArrayList<Bundle>(snapshot.items.size)
        snapshot.items.take(CarePadSettingsLimits.MAX_ITEMS_COUNT).forEach { item ->
            itemsBundles.add(encodeItem(item))
        }
        bundle.putParcelableArrayList(KEY_RES_ITEMS, itemsBundles)
        return bundle
    }

    fun encodeSnapshotUnavailable(message: String?): Bundle {
        val bundle = Bundle()
        bundle.putString(KEY_RES_STATUS, STATUS_UNAVAILABLE)
        message?.let { bundle.putString(KEY_RES_ERROR_MESSAGE, sanitizeErrorMessage(it)) }
        return bundle
    }

    fun encodeSnapshotIncompatible(
        supportedContractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        message: String? = null
    ): Bundle {
        val bundle = Bundle()
        bundle.putString(KEY_RES_STATUS, STATUS_INCOMPATIBLE)
        bundle.putInt(KEY_RES_CONTRACT_VERSION, supportedContractVersion)
        message?.let { bundle.putString(KEY_RES_ERROR_MESSAGE, sanitizeErrorMessage(it)) }
        return bundle
    }

    fun decodeSnapshotResult(bundle: Bundle?): CarePadSettingsSnapshotResult {
        if (bundle == null) {
            return CarePadSettingsSnapshotResult.Unavailable("Empty response bundle from provider")
        }

        return when (bundle.getString(KEY_RES_STATUS)) {
            STATUS_SUCCESS -> {
                val version = bundle.getInt(KEY_RES_CONTRACT_VERSION, 0)
                if (version != CarePadSettingsProtocol.CONTRACT_VERSION) {
                    return CarePadSettingsSnapshotResult.Incompatible(
                        supportedContractVersion = version,
                        message = "Unsupported contract version $version"
                    )
                }

                val revision = bundle.getString(KEY_RES_CATALOG_REVISION).orEmpty()
                if (revision.isBlank()) {
                    return CarePadSettingsSnapshotResult.Unavailable("Missing catalog revision in snapshot")
                }

                val itemBundles = getParcelableBundleArrayList(bundle, KEY_RES_ITEMS)
                val items = itemBundles.mapNotNull { decodeItem(it) }

                try {
                    CarePadSettingsSnapshotResult.Success(
                        CarePadSettingsSnapshot(
                            contractVersion = version,
                            catalogRevision = revision,
                            items = items
                        )
                    )
                } catch (e: Exception) {
                    CarePadSettingsSnapshotResult.Unavailable("Invalid snapshot structure: ${e.message}")
                }
            }

            STATUS_INCOMPATIBLE -> {
                val version = bundle.getInt(KEY_RES_CONTRACT_VERSION, CarePadSettingsProtocol.CONTRACT_VERSION)
                val message = bundle.getString(KEY_RES_ERROR_MESSAGE)
                CarePadSettingsSnapshotResult.Incompatible(version, message)
            }

            STATUS_UNAVAILABLE -> {
                val message = bundle.getString(KEY_RES_ERROR_MESSAGE)
                CarePadSettingsSnapshotResult.Unavailable(message)
            }

            else -> CarePadSettingsSnapshotResult.Unavailable("Unknown response status from settings provider")
        }
    }

    // --- Write requests encoding & decoding ---

    fun encodeWriteBooleanRequest(
        contractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): Bundle {
        val bundle = Bundle()
        bundle.putInt(KEY_CONTRACT_VERSION, contractVersion)
        bundle.putString(KEY_CATALOG_REVISION, catalogRevision)
        bundle.putString(KEY_ITEM_ID, itemId)
        bundle.putBoolean(KEY_VALUE_BOOLEAN, value)
        return bundle
    }

    fun decodeWriteBooleanRequest(bundle: Bundle?): WriteBooleanRequest? {
        if (bundle == null) return null
        val version = bundle.getInt(KEY_CONTRACT_VERSION, 0)
        val revision = bundle.getString(KEY_CATALOG_REVISION) ?: return null
        val itemId = bundle.getString(KEY_ITEM_ID) ?: return null
        val value = bundle.getBoolean(KEY_VALUE_BOOLEAN)
        if (version <= 0 || revision.isBlank() || itemId.isBlank()) return null
        return WriteBooleanRequest(version, revision, itemId, value)
    }

    fun encodeWriteSingleChoiceRequest(
        contractVersion: Int = CarePadSettingsProtocol.CONTRACT_VERSION,
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): Bundle {
        val bundle = Bundle()
        bundle.putInt(KEY_CONTRACT_VERSION, contractVersion)
        bundle.putString(KEY_CATALOG_REVISION, catalogRevision)
        bundle.putString(KEY_ITEM_ID, itemId)
        bundle.putString(KEY_SELECTED_OPTION_ID, selectedOptionId)
        return bundle
    }

    fun decodeWriteSingleChoiceRequest(bundle: Bundle?): WriteSingleChoiceRequest? {
        if (bundle == null) return null
        val version = bundle.getInt(KEY_CONTRACT_VERSION, 0)
        val revision = bundle.getString(KEY_CATALOG_REVISION) ?: return null
        val itemId = bundle.getString(KEY_ITEM_ID) ?: return null
        val selectedOptionId = bundle.getString(KEY_SELECTED_OPTION_ID) ?: return null
        if (version <= 0 || revision.isBlank() || itemId.isBlank() || selectedOptionId.isBlank()) return null
        return WriteSingleChoiceRequest(version, revision, itemId, selectedOptionId)
    }

    // --- SettingResult encoding & decoding ---

    fun encodeSettingResult(result: CarePadSettingResult): Bundle {
        val bundle = Bundle()
        when (result) {
            is CarePadSettingResult.Applied -> {
                bundle.putString(KEY_RES_RESULT_CODE, CODE_APPLIED)
                bundle.putString(KEY_RES_CATALOG_REVISION, result.catalogRevision)
                result.effectiveValueBoolean?.let { bundle.putBoolean(KEY_RES_EFFECTIVE_BOOLEAN, it) }
                result.effectiveSelectedOptionId?.let { bundle.putString(KEY_RES_EFFECTIVE_OPTION_ID, it) }
            }

            is CarePadSettingResult.Rejected -> {
                bundle.putString(KEY_RES_RESULT_CODE, CODE_REJECTED)
                bundle.putString(KEY_RES_CATALOG_REVISION, result.catalogRevision)
                result.effectiveValueBoolean?.let { bundle.putBoolean(KEY_RES_EFFECTIVE_BOOLEAN, it) }
                result.effectiveSelectedOptionId?.let { bundle.putString(KEY_RES_EFFECTIVE_OPTION_ID, it) }
                result.message?.let { bundle.putString(KEY_RES_ERROR_MESSAGE, sanitizeErrorMessage(it)) }
            }

            is CarePadSettingResult.Stale -> {
                bundle.putString(KEY_RES_RESULT_CODE, CODE_STALE)
                bundle.putString(KEY_RES_CATALOG_REVISION, result.currentCatalogRevision)
                result.message?.let { bundle.putString(KEY_RES_ERROR_MESSAGE, sanitizeErrorMessage(it)) }
            }

            is CarePadSettingResult.Unavailable -> {
                bundle.putString(KEY_RES_RESULT_CODE, CODE_UNAVAILABLE)
                result.message?.let { bundle.putString(KEY_RES_ERROR_MESSAGE, sanitizeErrorMessage(it)) }
            }

            is CarePadSettingResult.Incompatible -> {
                bundle.putString(KEY_RES_RESULT_CODE, CODE_INCOMPATIBLE)
                bundle.putInt(KEY_RES_CONTRACT_VERSION, result.supportedContractVersion)
                result.message?.let { bundle.putString(KEY_RES_ERROR_MESSAGE, sanitizeErrorMessage(it)) }
            }
        }
        return bundle
    }

    fun decodeSettingResult(bundle: Bundle?): CarePadSettingResult {
        if (bundle == null) {
            return CarePadSettingResult.Unavailable("Null response from provider")
        }

        val code = bundle.getString(KEY_RES_RESULT_CODE) ?: return CarePadSettingResult.Unavailable("Missing result code")
        val revision = bundle.getString(KEY_RES_CATALOG_REVISION).orEmpty()
        val errorMsg = bundle.getString(KEY_RES_ERROR_MESSAGE)

        val hasBool = bundle.containsKey(KEY_RES_EFFECTIVE_BOOLEAN)
        val boolVal = if (hasBool) bundle.getBoolean(KEY_RES_EFFECTIVE_BOOLEAN) else null
        val optVal = bundle.getString(KEY_RES_EFFECTIVE_OPTION_ID)

        return when (code) {
            CODE_APPLIED -> {
                if (revision.isBlank()) CarePadSettingResult.Unavailable("Missing revision in APPLIED result")
                else CarePadSettingResult.Applied(revision, boolVal, optVal)
            }

            CODE_REJECTED -> {
                if (revision.isBlank()) CarePadSettingResult.Unavailable("Missing revision in REJECTED result")
                else CarePadSettingResult.Rejected(revision, boolVal, optVal, errorMsg)
            }

            CODE_STALE -> {
                if (revision.isBlank()) CarePadSettingResult.Unavailable("Missing current revision in STALE result")
                else CarePadSettingResult.Stale(revision, errorMsg)
            }

            CODE_INCOMPATIBLE -> {
                val version = bundle.getInt(KEY_RES_CONTRACT_VERSION, CarePadSettingsProtocol.CONTRACT_VERSION)
                CarePadSettingResult.Incompatible(version, errorMsg)
            }

            CODE_UNAVAILABLE -> CarePadSettingResult.Unavailable(errorMsg)
            else -> CarePadSettingResult.Unavailable("Unknown result code '$code'")
        }
    }

    // --- Private item encoding helpers ---

    private fun encodeItem(item: CarePadSettingItem): Bundle {
        val bundle = Bundle()
        bundle.putString(ITEM_ID, item.id)
        bundle.putString(ITEM_TYPE, item.type.name)
        bundle.putString(ITEM_TITLE, item.title)
        item.description?.let { bundle.putString(ITEM_DESCRIPTION, it) }
        bundle.putBoolean(ITEM_EDITABLE, item.editable)
        bundle.putString(ITEM_AVAILABILITY, item.availability.name)
        item.errorMessage?.let { bundle.putString(ITEM_ERROR_MESSAGE, sanitizeErrorMessage(it)) }

        when (item) {
            is CarePadSettingItem.BooleanItem -> {
                bundle.putBoolean(ITEM_VALUE_BOOLEAN, item.value)
            }

            is CarePadSettingItem.SingleChoiceItem -> {
                bundle.putString(ITEM_SELECTED_OPTION_ID, item.selectedOptionId)
                val optBundles = ArrayList<Bundle>(item.options.size)
                item.options.take(CarePadSettingsLimits.MAX_OPTIONS_PER_CHOICE).forEach { opt ->
                    val optBundle = Bundle()
                    optBundle.putString(OPTION_ID, opt.optionId)
                    optBundle.putString(OPTION_LABEL, opt.label)
                    optBundles.add(optBundle)
                }
                bundle.putParcelableArrayList(ITEM_OPTIONS, optBundles)
            }

            is CarePadSettingItem.ReadOnlyInfoItem -> {
                bundle.putString(ITEM_VALUE_STRING, item.value)
            }
        }
        return bundle
    }

    private fun decodeItem(bundle: Bundle): CarePadSettingItem? {
        val id = bundle.getString(ITEM_ID) ?: return null
        val typeStr = bundle.getString(ITEM_TYPE) ?: return null
        val title = bundle.getString(ITEM_TITLE) ?: return null
        val description = bundle.getString(ITEM_DESCRIPTION)
        val editable = bundle.getBoolean(ITEM_EDITABLE, true)
        val availStr = bundle.getString(ITEM_AVAILABILITY) ?: CarePadItemAvailability.AVAILABLE.name
        val availability = runCatching { CarePadItemAvailability.valueOf(availStr) }.getOrDefault(CarePadItemAvailability.AVAILABLE)
        val errorMessage = bundle.getString(ITEM_ERROR_MESSAGE)

        return runCatching {
            when (typeStr) {
                CarePadSettingType.BOOLEAN.name -> {
                    val value = bundle.getBoolean(ITEM_VALUE_BOOLEAN)
                    CarePadSettingItem.BooleanItem(
                        id = id,
                        title = title,
                        description = description,
                        editable = editable,
                        availability = availability,
                        errorMessage = errorMessage,
                        value = value
                    )
                }

                CarePadSettingType.SINGLE_CHOICE.name -> {
                    val selected = bundle.getString(ITEM_SELECTED_OPTION_ID) ?: return null
                    val optBundles = getParcelableBundleArrayList(bundle, ITEM_OPTIONS)
                    val options = optBundles.mapNotNull { optB ->
                        val optId = optB.getString(OPTION_ID) ?: return@mapNotNull null
                        val optLabel = optB.getString(OPTION_LABEL) ?: return@mapNotNull null
                        CarePadSettingOption(optId, optLabel)
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

                CarePadSettingType.READ_ONLY_INFO.name -> {
                    val value = bundle.getString(ITEM_VALUE_STRING) ?: ""
                    CarePadSettingItem.ReadOnlyInfoItem(
                        id = id,
                        title = title,
                        description = description,
                        availability = availability,
                        errorMessage = errorMessage,
                        value = value
                    )
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun getParcelableBundleArrayList(bundle: Bundle, key: String): List<Bundle> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelableArrayList(key, Bundle::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelableArrayList<Bundle>(key).orEmpty()
        }
    }

    private fun sanitizeErrorMessage(raw: String): String {
        val sanitized = raw.replace('\n', ' ').replace('\r', ' ').trim()
        return if (sanitized.length > CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH) {
            sanitized.take(CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH)
        } else {
            sanitized
        }
    }
}

