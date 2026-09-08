package com.joel.thordoctor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joel.thordoctor.R
import com.joel.thordoctor.modules.catalog.distribution.DevelopmentQaCatalogState
import com.joel.thordoctor.modules.catalog.distribution.DevelopmentQaModuleCatalog
import com.joel.thordoctor.modules.catalog.distribution.ModuleDistributionArtifact
import com.joel.thordoctor.modules.host.ModuleManager
import com.joel.thordoctor.modules.host.catalog.CatalogInstallRequestResult
import com.joel.thordoctor.modules.host.catalog.CatalogModuleInstallStage
import com.joel.thordoctor.modules.host.catalog.CatalogModuleInstallState
import com.joel.thordoctor.modules.host.catalog.CatalogModuleInstaller

@Composable
internal fun CarePadAddModulesCatalogContent(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                refreshToken += 1
            }
        }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            packageReceiver,
            packageFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                refreshToken += 1
            }
        }
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            IntentFilter(CatalogModuleInstallState.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        onDispose {
            runCatching { context.unregisterReceiver(packageReceiver) }
            runCatching { context.unregisterReceiver(stateReceiver) }
        }
    }

    val catalogState = remember(refreshToken) { DevelopmentQaModuleCatalog.load(context) }
    val installedPackages = remember(refreshToken) {
        ModuleManager.discover(context).modules.mapTo(hashSetOf()) { it.packageName }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.carepad_nav_add_modules),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )

            when (val state = catalogState) {
                DevelopmentQaCatalogState.NotConnected -> {
                    Text(stringResource(R.string.carepad_add_modules_not_connected))
                }

                is DevelopmentQaCatalogState.Invalid -> {
                    Text(
                        text = stringResource(R.string.carepad_add_modules_invalid_catalog),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is DevelopmentQaCatalogState.Available -> {
                    val targets = state.targets
                        .filter { it.packageName !in installedPackages }
                        .mapNotNull { target ->
                            CarePadModulePresentations.forModuleId(target.moduleId)
                                ?.let { presentation -> CatalogVisibleModule(target, presentation) }
                        }
                        .sortedWith(
                            compareBy<CatalogVisibleModule> { it.presentation.order }
                                .thenBy { it.target.packageName }
                        )

                    if (targets.isEmpty()) {
                        Text(stringResource(R.string.carepad_add_modules_all_installed))
                    } else {
                        Text(
                            text = stringResource(R.string.carepad_add_modules_intro),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = targets,
                                key = { it.target.packageName },
                            ) { item ->
                                CatalogModuleCard(
                                    item = item,
                                    installStage = CatalogModuleInstallState
                                        .read(context, item.target.packageName)
                                        .stage,
                                    onInstall = {
                                        CatalogModuleInstaller.requestInstall(context, item.target)
                                        refreshToken += 1
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class CatalogVisibleModule(
    val target: ModuleDistributionArtifact,
    val presentation: CarePadModulePresentation,
)

@Composable
private fun CatalogModuleCard(
    item: CatalogVisibleModule,
    installStage: CatalogModuleInstallStage,
    onInstall: () -> Unit,
) {
    val busy = installStage == CatalogModuleInstallStage.PREPARING ||
        installStage == CatalogModuleInstallStage.SUBMITTED ||
        installStage == CatalogModuleInstallStage.AWAITING_CONFIRMATION
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = stringResource(item.presentation.nameRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(item.presentation.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.carepad_add_modules_version,
                        item.target.versionName,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                when (installStage) {
                    CatalogModuleInstallStage.PREPARING,
                    CatalogModuleInstallStage.SUBMITTED ->
                        Text(stringResource(R.string.carepad_add_modules_preparing))

                    CatalogModuleInstallStage.AWAITING_CONFIRMATION ->
                        Text(stringResource(R.string.carepad_add_modules_confirm_android))

                    CatalogModuleInstallStage.FAILED ->
                        Text(
                            text = stringResource(R.string.carepad_add_modules_install_failed),
                            color = MaterialTheme.colorScheme.error,
                        )

                    CatalogModuleInstallStage.NONE -> Unit
                }
            }
            OutlinedButton(
                onClick = rememberCozyClick(onInstall),
                enabled = !busy,
            ) {
                Text(stringResource(R.string.carepad_add_modules_install))
            }
        }
    }
}
