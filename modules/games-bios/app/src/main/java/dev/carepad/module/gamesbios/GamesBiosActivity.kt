package dev.carepad.module.gamesbios

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinSafEvaluationResult
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinSafEvidenceAcquirer
import com.joel.thordoctor.modules.gamesbios.diagnostics.GameLibraryPlatformDiagnosticEvaluator
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryRuntime
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryScanResult
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryService
import com.joel.thordoctor.modules.gamesbios.organization.RomPlatform
import com.joel.thordoctor.modules.gamesbios.organization.RomPlatformClassifier

class GamesBiosActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var resultsText: TextView
    private lateinit var selectFolderButton: Button
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        renderExistingState()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            renderExistingState()
        }
    }

    @Deprecated("Deprecated in Android; retained to avoid adding an activity-result dependency for this small module surface.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val authorized = GameLibraryService.setRootFolder(this, uri)
        if (!authorized) {
            statusText.text = getString(R.string.folder_authorization_failed)
            renderActions(rootConfigured = false)
            return
        }

        val folderName = GameLibraryService.folderDisplayName(this).orEmpty()
        statusText.text = getString(R.string.folder_authorized, folderName)
        resultsText.text = ""
        renderActions(rootConfigured = true)
    }

    private fun buildContent(): ScrollView {
        val padding = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.games_intro)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        })

        selectFolderButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { openFolderPicker() }
        }
        root.addView(
            selectFolderButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        scanButton = Button(this).apply {
            text = getString(R.string.scan_library)
            isAllCaps = false
            setOnClickListener { scanLibrary() }
        }
        root.addView(
            scanButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(statusText)

        resultsText = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(resultsText)

        return ScrollView(this).apply { addView(root) }
    }

    private fun renderExistingState() {
        val storedUri = GameLibraryService.rootFolderUri(this)
        val validRoot = GameLibraryService.hasValidRootFolder(this)

        when {
            storedUri == null -> {
                statusText.text = getString(R.string.folder_not_configured)
                resultsText.text = ""
                renderActions(rootConfigured = false)
            }

            !validRoot -> {
                statusText.text = getString(R.string.folder_needs_reauthorization)
                resultsText.text = ""
                renderActions(rootConfigured = false)
            }

            else -> {
                val folderName = GameLibraryService.folderDisplayName(this).orEmpty()
                statusText.text = getString(R.string.folder_ready, folderName)
                renderActions(rootConfigured = true)
                val cached = GameLibraryRuntime.readCachedScan(this)
                if (cached != null) {
                    renderScan(cached, cueEvaluation = null)
                } else {
                    resultsText.text = ""
                }
            }
        }
    }

    private fun renderActions(rootConfigured: Boolean) {
        selectFolderButton.text = getString(
            if (GameLibraryService.rootFolderUri(this) == null) {
                R.string.select_folder
            } else {
                R.string.change_folder
            },
        )
        selectFolderButton.isEnabled = true
        scanButton.isEnabled = rootConfigured
    }

    @Suppress("DEPRECATION")
    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    private fun scanLibrary() {
        if (!GameLibraryService.hasValidRootFolder(this)) {
            renderExistingState()
            return
        }

        selectFolderButton.isEnabled = false
        scanButton.isEnabled = false
        statusText.text = getString(R.string.scan_in_progress)
        resultsText.text = ""

        Thread {
            val result = runCatching {
                val execution = GameLibraryService.scan(this)
                val rootUri = GameLibraryService.rootFolderUri(this)
                val root = rootUri?.let { DocumentFile.fromTreeUri(this, it) }
                val cueEvaluation = root?.let {
                    CueBinSafEvidenceAcquirer.evaluateWithStatus(this, it)
                }
                ScanUiResult(execution.result, cueEvaluation)
            }

            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread

                result.fold(
                    onSuccess = { scan ->
                        val folderName = GameLibraryService.folderDisplayName(this).orEmpty()
                        statusText.text = getString(R.string.folder_ready, folderName)
                        renderScan(scan.library, scan.cueEvaluation)
                        renderActions(rootConfigured = true)
                    },
                    onFailure = {
                        statusText.text = getString(R.string.scan_failed)
                        renderActions(rootConfigured = GameLibraryService.hasValidRootFolder(this))
                    },
                )
            }
        }.start()
    }

    private fun renderScan(
        scan: GameLibraryScanResult,
        cueEvaluation: CueBinSafEvaluationResult?,
    ) {
        val unresolved = GameLibraryPlatformDiagnosticEvaluator.evaluate(scan)
        resultsText.text = buildString {
            appendLine(getString(R.string.scan_summary, scan.gameCount))
            appendLine(getString(R.string.scan_unresolved, unresolved.size))

            if (cueEvaluation != null) {
                if (cueEvaluation.complete) {
                    appendLine(getString(R.string.scan_cue_issues, cueEvaluation.diagnostics.size))
                    cueEvaluation.diagnostics.forEach { issue ->
                        appendLine(
                            getString(
                                R.string.cue_issue_line,
                                issue.cueEntry.relativePath,
                                issue.referencedBinPath,
                            ),
                        )
                    }
                } else {
                    appendLine(getString(R.string.scan_cue_incomplete))
                }
            }

            if (scan.games.isEmpty()) {
                appendLine()
                appendLine(getString(R.string.no_games))
            } else {
                appendLine()
                scan.games.forEach { game ->
                    appendLine(getString(R.string.game_line, game.relativePath, platformLabel(game)))
                }
            }
        }.trimEnd()
    }

    private fun platformLabel(game: GameLibraryEntry): String {
        val classification = RomPlatformClassifier.classify(game.name)
        if (!classification.isUnambiguous) {
            return getString(R.string.platform_unresolved)
        }

        return when (classification.candidates.single().platform) {
            RomPlatform.GAME_BOY -> "Game Boy"
            RomPlatform.GAME_BOY_COLOR -> "Game Boy Color"
            RomPlatform.GAME_BOY_ADVANCE -> "Game Boy Advance"
            RomPlatform.NINTENDO_DS -> "Nintendo DS"
            RomPlatform.NINTENDO_3DS -> "Nintendo 3DS"
            RomPlatform.NINTENDO_64 -> "Nintendo 64"
            RomPlatform.GAMECUBE -> "Nintendo GameCube"
            RomPlatform.WII -> "Nintendo Wii"
            RomPlatform.NES -> "NES"
            RomPlatform.SNES -> "SNES"
            RomPlatform.PLAYSTATION -> "PlayStation"
            RomPlatform.PLAYSTATION_2 -> "PlayStation 2"
            RomPlatform.PSP -> "PSP"
            RomPlatform.SEGA_GENESIS -> "Sega Mega Drive / Genesis"
            RomPlatform.SEGA_CD -> "Sega CD / Mega-CD"
            RomPlatform.DREAMCAST -> "Dreamcast"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class ScanUiResult(
        val library: GameLibraryScanResult,
        val cueEvaluation: CueBinSafEvaluationResult?,
    )

    companion object {
        private const val REQUEST_FOLDER = 1001
    }
}
