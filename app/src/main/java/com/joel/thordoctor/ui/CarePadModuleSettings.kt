package com.joel.thordoctor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import carepad.contracts.CarePadItemAvailability
import carepad.contracts.CarePadModuleCapabilities
import carepad.contracts.CarePadSettingItem
import carepad.contracts.CarePadSettingResult
import carepad.contracts.CarePadSettingsSnapshot
import carepad.contracts.CarePadSettingsSnapshotResult
import com.joel.thordoctor.R
import com.joel.thordoctor.core.settings.CarePadSettingsClient
import com.joel.thordoctor.modules.host.DiscoveredCarePadModule
import com.joel.thordoctor.modules.host.ModuleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DELEGATED_FOCUS_ID = "__delegated__"

private data class ModuleSettingFocusKey(
    val packageName: String,
    val itemId: String,
)

private data class SettingsModulePresentation(
    val module: DiscoveredCarePadModule,
    val name: String,
    val delegatedAvailable: Boolean,
)

private sealed interface InlineModuleState {
    data object Loading : InlineModuleState
    data object Empty : InlineModuleState
    data class Ready(val snapshot: CarePadSettingsSnapshot) : InlineModuleState
    data class Unavailable(val message: String?) : InlineModuleState
    data class Incompatible(val message: String?) : InlineModuleState
}

private data class ChoiceDialogState(
    val module: DiscoveredCarePadModule,
    val catalogRevision: String,
    val item: CarePadSettingItem.SingleChoiceItem,
)

@Composable
internal fun CarePadModuleSettingsSections() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var discovery by remember { mutableStateOf(ModuleManager.discover(context)) }
    var refreshRevision by remember { mutableStateOf(0) }
    var inlineStates by remember { mutableStateOf<Map<String, InlineModuleState>>(emptyMap()) }
    var pendingItems by remember { mutableStateOf<Set<ModuleSettingFocusKey>>(emptySet()) }
    var itemFeedback by remember { mutableStateOf<Map<ModuleSettingFocusKey, String>>(emptyMap()) }
    var delegatedFeedback by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var choiceDialog by remember { mutableStateOf<ChoiceDialogState?>(null) }
    var focusedKey by remember { mutableStateOf<ModuleSettingFocusKey?>(null) }
    var focusRestoreKey by remember { mutableStateOf<ModuleSettingFocusKey?>(null) }
    var previousInteractiveKeys by remember { mutableStateOf<List<ModuleSettingFocusKey>>(emptyList()) }
    val focusRequesters = remember { mutableMapOf<ModuleSettingFocusKey, FocusRequester>() }

    fun refreshDiscovery() {
        discovery = ModuleManager.discover(context)
        refreshRevision++
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                refreshDiscovery()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    DisposableEffect(context) {
        val lifecycleOwner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshRevision++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val settingsModules = remember(discovery.modules, refreshRevision) {
        discovery.modules
            .mapNotNull { module ->
                val hasInline = CarePadModuleCapabilities.SETTINGS_INLINE in module.metadata.capabilities
                val delegatedAvailable = ModuleManager.canOpenSettings(context, module)
                if (!hasInline && !delegatedAvailable) {
                    null
                } else {
                    SettingsModulePresentation(
                        module = module,
                        name = moduleDisplayName(context, module),
                        delegatedAvailable = delegatedAvailable,
                    )
                }
            }
            .sortedWith(
                compareBy<SettingsModulePresentation> { it.name.lowercase() }
                    .thenBy { it.module.packageName }
            )
    }
    val settingsPackages = settingsModules.map { it.module.packageName }.toSet()

    suspend fun loadInlineModule(module: DiscoveredCarePadModule, showLoading: Boolean) {
        if (showLoading) {
            inlineStates = inlineStates + (module.packageName to InlineModuleState.Loading)
        }
        val result = withContext(Dispatchers.IO) {
            CarePadSettingsClient.getSnapshot(context, module.packageName)
        }
        inlineStates = inlineStates + (
            module.packageName to when (result) {
                is CarePadSettingsSnapshotResult.Success -> {
                    if (result.snapshot.items.isEmpty()) {
                        InlineModuleState.Empty
                    } else {
                        InlineModuleState.Ready(result.snapshot)
                    }
                }
                is CarePadSettingsSnapshotResult.Unavailable ->
                    InlineModuleState.Unavailable(result.message)
                is CarePadSettingsSnapshotResult.Incompatible ->
                    InlineModuleState.Incompatible(result.message)
            }
        )
    }

    LaunchedEffect(settingsModules, refreshRevision) {
        inlineStates = inlineStates.filterKeys { it in settingsPackages }
        pendingItems = pendingItems.filter { it.packageName in settingsPackages }.toSet()
        itemFeedback = itemFeedback.filterKeys { it.packageName in settingsPackages }
        delegatedFeedback = delegatedFeedback.filterKeys { it in settingsPackages }
        if (choiceDialog?.module?.packageName !in settingsPackages) {
            choiceDialog = null
        }

        settingsModules.forEach { presentation ->
            val module = presentation.module
            if (CarePadModuleCapabilities.SETTINGS_INLINE in module.metadata.capabilities) {
                launch {
                    loadInlineModule(
                        module = module,
                        showLoading = inlineStates[module.packageName] == null,
                    )
                }
            }
        }
    }

    fun markPending(key: ModuleSettingFocusKey, pending: Boolean) {
        pendingItems = if (pending) pendingItems + key else pendingItems - key
    }

    fun requestBooleanWrite(
        module: DiscoveredCarePadModule,
        snapshot: CarePadSettingsSnapshot,
        item: CarePadSettingItem.BooleanItem,
        requestedValue: Boolean,
    ) {
        val itemKey = ModuleSettingFocusKey(module.packageName, item.id)
        if (itemKey in pendingItems) return
        focusRestoreKey = itemKey
        markPending(itemKey, true)
        itemFeedback = itemFeedback - itemKey
        scope.launch {
            try {
                when (
                    val result = withContext(Dispatchers.IO) {
                        CarePadSettingsClient.writeBoolean(
                            context = context,
                            modulePackageName = module.packageName,
                            catalogRevision = snapshot.catalogRevision,
                            itemId = item.id,
                            value = requestedValue,
                        )
                    }
                ) {
                    is CarePadSettingResult.Applied -> loadInlineModule(module, showLoading = false)
                    is CarePadSettingResult.Rejected -> {
                        itemFeedback = itemFeedback + (
                            itemKey to (
                                result.message
                                    ?: context.getString(R.string.carepad_module_setting_change_failed)
                            )
                        )
                        loadInlineModule(module, showLoading = false)
                    }
                    is CarePadSettingResult.Stale -> {
                        itemFeedback = itemFeedback + (
                            itemKey to context.getString(R.string.carepad_module_setting_stale)
                        )
                        loadInlineModule(module, showLoading = false)
                    }
                    is CarePadSettingResult.Unavailable -> {
                        inlineStates = inlineStates + (
                            module.packageName to InlineModuleState.Unavailable(result.message)
                        )
                    }
                    is CarePadSettingResult.Incompatible -> {
                        inlineStates = inlineStates + (
                            module.packageName to InlineModuleState.Incompatible(result.message)
                        )
                    }
                }
            } finally {
                markPending(itemKey, false)
            }
        }
    }

    fun requestChoiceWrite(
        module: DiscoveredCarePadModule,
        catalogRevision: String,
        item: CarePadSettingItem.SingleChoiceItem,
        selectedOptionId: String,
    ) {
        val itemKey = ModuleSettingFocusKey(module.packageName, item.id)
        if (itemKey in pendingItems) return
        focusRestoreKey = itemKey
        markPending(itemKey, true)
        itemFeedback = itemFeedback - itemKey
        scope.launch {
            try {
                when (
                    val result = withContext(Dispatchers.IO) {
                        CarePadSettingsClient.writeSingleChoice(
                            context = context,
                            modulePackageName = module.packageName,
                            catalogRevision = catalogRevision,
                            itemId = item.id,
                            selectedOptionId = selectedOptionId,
                        )
                    }
                ) {
                    is CarePadSettingResult.Applied -> loadInlineModule(module, showLoading = false)
                    is CarePadSettingResult.Rejected -> {
                        itemFeedback = itemFeedback + (
                            itemKey to (
                                result.message
                                    ?: context.getString(R.string.carepad_module_setting_change_failed)
                            )
                        )
                        loadInlineModule(module, showLoading = false)
                    }
                    is CarePadSettingResult.Stale -> {
                        itemFeedback = itemFeedback + (
                            itemKey to context.getString(R.string.carepad_module_setting_stale)
                        )
                        loadInlineModule(module, showLoading = false)
                    }
                    is CarePadSettingResult.Unavailable -> {
                        inlineStates = inlineStates + (
                            module.packageName to InlineModuleState.Unavailable(result.message)
                        )
                    }
                    is CarePadSettingResult.Incompatible -> {
                        inlineStates = inlineStates + (
                            module.packageName to InlineModuleState.Incompatible(result.message)
                        )
                    }
                }
            } finally {
                markPending(itemKey, false)
            }
        }
    }

    val interactiveKeys = buildList {
        settingsModules.forEach { presentation ->
            val packageName = presentation.module.packageName
            val state = inlineStates[packageName]
            if (state is InlineModuleState.Ready) {
                state.snapshot.items.forEach { item ->
                    if (
                        item is CarePadSettingItem.BooleanItem ||
                        item is CarePadSettingItem.SingleChoiceItem
                    ) {
                        if (item.editable && item.availability == CarePadItemAvailability.AVAILABLE) {
                            add(ModuleSettingFocusKey(packageName, item.id))
                        }
                    }
                }
            }
            if (presentation.delegatedAvailable) {
                add(ModuleSettingFocusKey(packageName, DELEGATED_FOCUS_ID))
            }
        }
    }

    LaunchedEffect(interactiveKeys) {
        val missing = focusedKey?.takeIf { it !in interactiveKeys }
        if (missing != null) {
            val oldIndex = previousInteractiveKeys.indexOf(missing).coerceAtLeast(0)
            val fallback = if (interactiveKeys.isEmpty()) {
                null
            } else {
                interactiveKeys[oldIndex.coerceAtMost(interactiveKeys.lastIndex)]
            }
            focusedKey = fallback
            if (fallback != null) {
                focusRequesters[fallback]?.requestFocus()
            } else {
                focusManager.moveFocus(FocusDirection.Up)
            }
        }
        previousInteractiveKeys = interactiveKeys
    }

    LaunchedEffect(focusRestoreKey, pendingItems, interactiveKeys, choiceDialog) {
        val target = focusRestoreKey ?: return@LaunchedEffect
        if (choiceDialog != null || target in pendingItems || target !in interactiveKeys) {
            return@LaunchedEffect
        }
        focusRequesters[target]?.let { requester ->
            requester.requestFocus()
            focusRestoreKey = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        settingsModules.forEach { presentation ->
            val module = presentation.module
            val state = inlineStates[module.packageName]
            val hasInline = CarePadModuleCapabilities.SETTINGS_INLINE in module.metadata.capabilities
            val shouldRender = when {
                !hasInline -> presentation.delegatedAvailable
                state is InlineModuleState.Empty -> presentation.delegatedAvailable
                else -> true
            }
            if (!shouldRender) return@forEach

            key(module.packageName) {
                ModuleSettingsSection(
                    presentation = presentation,
                    state = if (hasInline) {
                        state ?: InlineModuleState.Loading
                    } else {
                        InlineModuleState.Empty
                    },
                    pendingItems = pendingItems,
                    itemFeedback = itemFeedback,
                    delegatedFeedback = delegatedFeedback[module.packageName],
                    focusRequesterFor = { itemId ->
                        focusRequesters.getOrPut(
                            ModuleSettingFocusKey(module.packageName, itemId)
                        ) { FocusRequester() }
                    },
                    onFocused = { itemId, focused ->
                        val target = ModuleSettingFocusKey(module.packageName, itemId)
                        if (focused) {
                            focusedKey = target
                        } else if (focusedKey == target) {
                            focusedKey = null
                        }
                    },
                    onBooleanWrite = { snapshot, item, value ->
                        requestBooleanWrite(module, snapshot, item, value)
                    },
                    onChoiceOpen = { snapshot, item ->
                        focusRestoreKey = ModuleSettingFocusKey(module.packageName, item.id)
                        choiceDialog = ChoiceDialogState(
                            module = module,
                            catalogRevision = snapshot.catalogRevision,
                            item = item,
                        )
                    },
                    onOpenDelegated = {
                        delegatedFeedback = delegatedFeedback - module.packageName
                        if (!ModuleManager.openSettings(context, module)) {
                            delegatedFeedback = delegatedFeedback + (
                                module.packageName to context.getString(
                                    R.string.carepad_module_settings_delegated_unavailable
                                )
                            )
                        }
                    },
                )
            }
        }
    }

    choiceDialog?.let { dialog ->
        SingleChoiceDialog(
            dialog = dialog,
            onDismiss = { choiceDialog = null },
            onApply = { selectedOptionId ->
                choiceDialog = null
                requestChoiceWrite(
                    module = dialog.module,
                    catalogRevision = dialog.catalogRevision,
                    item = dialog.item,
                    selectedOptionId = selectedOptionId,
                )
            },
        )
    }
}

@Composable
private fun ModuleSettingsSection(
    presentation: SettingsModulePresentation,
    state: InlineModuleState,
    pendingItems: Set<ModuleSettingFocusKey>,
    itemFeedback: Map<ModuleSettingFocusKey, String>,
    delegatedFeedback: String?,
    focusRequesterFor: (String) -> FocusRequester,
    onFocused: (String, Boolean) -> Unit,
    onBooleanWrite: (
        CarePadSettingsSnapshot,
        CarePadSettingItem.BooleanItem,
        Boolean,
    ) -> Unit,
    onChoiceOpen: (CarePadSettingsSnapshot, CarePadSettingItem.SingleChoiceItem) -> Unit,
    onOpenDelegated: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = presentation.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (state) {
                    InlineModuleState.Loading -> StatusRow(
                        text = stringResource(R.string.carepad_module_settings_loading),
                        loading = true,
                    )
                    InlineModuleState.Empty -> Unit
                    is InlineModuleState.Unavailable -> StatusRow(
                        text = stringResource(R.string.carepad_module_settings_unavailable),
                    )
                    is InlineModuleState.Incompatible -> StatusRow(
                        text = stringResource(R.string.carepad_module_settings_incompatible),
                    )
                    is InlineModuleState.Ready -> {
                        state.snapshot.items.forEach { item ->
                            key(item.id) {
                                val itemKey = ModuleSettingFocusKey(
                                    presentation.module.packageName,
                                    item.id,
                                )
                                when (item) {
                                    is CarePadSettingItem.BooleanItem -> BooleanSettingRow(
                                        item = item,
                                        pending = itemKey in pendingItems,
                                        feedback = itemFeedback[itemKey],
                                        focusRequester = focusRequesterFor(item.id),
                                        onFocused = { onFocused(item.id, it) },
                                        onToggle = { value ->
                                            onBooleanWrite(state.snapshot, item, value)
                                        },
                                    )
                                    is CarePadSettingItem.SingleChoiceItem -> SingleChoiceSettingRow(
                                        item = item,
                                        pending = itemKey in pendingItems,
                                        feedback = itemFeedback[itemKey],
                                        focusRequester = focusRequesterFor(item.id),
                                        onFocused = { onFocused(item.id, it) },
                                        onOpen = { onChoiceOpen(state.snapshot, item) },
                                    )
                                    is CarePadSettingItem.ReadOnlyInfoItem -> ReadOnlySettingRow(item)
                                }
                            }
                        }
                    }
                }

                if (presentation.delegatedAvailable) {
                    DelegatedSettingsRow(
                        feedback = delegatedFeedback,
                        focusRequester = focusRequesterFor(DELEGATED_FOCUS_ID),
                        onFocused = { onFocused(DELEGATED_FOCUS_ID, it) },
                        onOpen = onOpenDelegated,
                    )
                }
            }
        }
    }
}

@Composable
private fun BooleanSettingRow(
    item: CarePadSettingItem.BooleanItem,
    pending: Boolean,
    feedback: String?,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val focusable = item.editable && item.availability == CarePadItemAvailability.AVAILABLE
    val enabled = focusable && !pending
    val supporting = feedback ?: item.errorMessage ?: if (
        item.availability != CarePadItemAvailability.AVAILABLE
    ) {
        stringResource(R.string.carepad_module_setting_not_available)
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties { canFocus = focusable }
            .onFocusChanged { onFocused(it.isFocused) }
            .controllerActivation(enabled) { onToggle(!item.value) }
            .clickable(enabled = enabled) {
                focusRequester.requestFocus()
                onToggle(!item.value)
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(item.title, fontWeight = FontWeight.Medium)
                item.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Switch(checked = item.value, onCheckedChange = null, enabled = enabled)
            }
        }
    }
}

@Composable
private fun SingleChoiceSettingRow(
    item: CarePadSettingItem.SingleChoiceItem,
    pending: Boolean,
    feedback: String?,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val focusable = item.editable && item.availability == CarePadItemAvailability.AVAILABLE
    val enabled = focusable && !pending
    val effectiveLabel = item.options.firstOrNull { it.optionId == item.selectedOptionId }?.label
        ?: stringResource(R.string.carepad_module_setting_not_available)
    val supporting = feedback ?: item.errorMessage ?: if (
        item.availability != CarePadItemAvailability.AVAILABLE
    ) {
        stringResource(R.string.carepad_module_setting_not_available)
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties { canFocus = focusable }
            .onFocusChanged { onFocused(it.isFocused) }
            .controllerActivation(enabled, onOpen)
            .clickable(enabled = enabled) {
                focusRequester.requestFocus()
                onOpen()
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(item.title, fontWeight = FontWeight.Medium)
                item.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = "$effectiveLabel  ›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReadOnlySettingRow(item: CarePadSettingItem.ReadOnlyInfoItem) {
    val value = if (item.availability == CarePadItemAvailability.AVAILABLE) {
        item.value
    } else {
        stringResource(R.string.carepad_module_setting_not_available)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(item.title, fontWeight = FontWeight.Medium)
                item.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DelegatedSettingsRow(
    feedback: String?,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties { canFocus = true }
            .onFocusChanged { onFocused(it.isFocused) }
            .controllerActivation(enabled = true, onActivate = onOpen)
            .clickable {
                focusRequester.requestFocus()
                onOpen()
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.carepad_module_settings_more),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.carepad_module_settings_more_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                feedback?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = stringResource(R.string.carepad_module_settings_configure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusRow(text: String, loading: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SingleChoiceDialog(
    dialog: ChoiceDialogState,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var candidate by remember(dialog.item.id, dialog.catalogRevision) {
        mutableStateOf(dialog.item.selectedOptionId)
    }
    val requesters = remember(dialog.item.id, dialog.catalogRevision) {
        dialog.item.options.associate { it.optionId to FocusRequester() }
    }
    LaunchedEffect(dialog.item.id, dialog.catalogRevision) {
        requesters[dialog.item.selectedOptionId]?.requestFocus()
    }

    AlertDialog(
        modifier = Modifier.controllerBack(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(dialog.item.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                dialog.item.options.forEach { option ->
                    val selected = candidate == option.optionId
                    val optionFocusRequester = requesters.getValue(option.optionId)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(optionFocusRequester)
                            .focusProperties { canFocus = true }
                            .controllerActivation(enabled = true) {
                                candidate = option.optionId
                            }
                            .clickable {
                                optionFocusRequester.requestFocus()
                                candidate = option.optionId
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option.label, modifier = Modifier.weight(1f))
                            RadioButton(selected = selected, onClick = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val changed = candidate != dialog.item.selectedOptionId
            TextButton(
                modifier = Modifier.controllerActivation(enabled = changed) {
                    onApply(candidate)
                },
                enabled = changed,
                onClick = { onApply(candidate) },
            ) {
                Text(stringResource(R.string.carepad_module_settings_apply))
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.controllerActivation(enabled = true, onActivate = onDismiss),
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.carepad_cancel))
            }
        },
    )
}

private fun Modifier.controllerActivation(
    enabled: Boolean,
    onActivate: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (
        enabled &&
        event.type == KeyEventType.KeyDown &&
        native.repeatCount == 0 &&
        native.keyCode in setOf(
            AndroidKeyEvent.KEYCODE_BUTTON_A,
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        )
    ) {
        onActivate()
        true
    } else {
        false
    }
}

private fun Modifier.controllerBack(onBack: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (
            event.type == KeyEventType.KeyDown &&
            native.repeatCount == 0 &&
            native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_B
        ) {
            onBack()
            true
        } else {
            false
        }
    }

private fun moduleDisplayName(
    context: Context,
    module: DiscoveredCarePadModule,
): String = runCatching {
    val packageManager = context.packageManager
    val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(
            module.packageName,
            PackageManager.ApplicationInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(module.packageName, 0)
    }
    packageManager.getApplicationLabel(applicationInfo).toString().takeIf { it.isNotBlank() }
}.getOrNull() ?: context.getString(R.string.carepad_module_settings_fallback_name)
