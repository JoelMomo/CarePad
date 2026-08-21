package com.joel.thordoctor.core.emulator

object EmulatorRegistry {

    val definitions: List<EmulatorDefinition> = listOf(
        EmulatorDefinition(
            id = "retroarch",
            displayName = "RetroArch",
            packageNames = listOf("com.retroarch.aarch64"),
            platforms = listOf("Multi-system")
        ),
        EmulatorDefinition(
            id = "myboy",
            displayName = "My Boy!",
            packageNames = listOf(
                "com.fastemulator.gba",
                "com.fastemulator.gbafree"
            ),
            platforms = listOf("Game Boy Advance")
        ),
        EmulatorDefinition(
            id = "duckstation",
            displayName = "DuckStation",
            packageNames = listOf("com.github.stenzek.duckstation"),
            platforms = listOf("PlayStation")
        ),
        EmulatorDefinition(
            id = "nethersx2",
            displayName = "NetherSX2",
            packageNames = listOf("xyz.aethersx2.android"),
            platforms = listOf("PlayStation 2")
        ),
        EmulatorDefinition(
            id = "ppsspp",
            displayName = "PPSSPP",
            packageNames = listOf("org.ppsspp.ppsspp"),
            platforms = listOf("PSP")
        ),
        EmulatorDefinition(
            id = "melonds",
            displayName = "melonDS",
            packageNames = listOf("me.magnum.melondualds"),
            platforms = listOf("Nintendo DS")
        ),
        EmulatorDefinition(
            id = "dolphin",
            displayName = "Dolphin",
            packageNames = listOf("org.dolphinemu.dolphinemu"),
            platforms = listOf("GameCube", "Wii")
        ),
        EmulatorDefinition(
            id = "azahar",
            displayName = "Azahar",
            packageNames = listOf("org.azahar_emu.azahar"),
            platforms = listOf("Nintendo 3DS")
        ),
        EmulatorDefinition(
            id = "azahar_plus",
            displayName = "Azahar Plus",
            packageNames = listOf("io.github.azaharplus.android"),
            platforms = listOf("Nintendo 3DS")
        ),
        EmulatorDefinition(
            id = "vita3k",
            displayName = "Vita3K",
            packageNames = listOf("org.vita3k.emulator"),
            platforms = listOf("PlayStation Vita")
        ),
        EmulatorDefinition(
            id = "eden",
            displayName = "Eden",
            packageNames = listOf("dev.eden.eden_emulator"),
            platforms = listOf("Nintendo Switch")
        ),
        EmulatorDefinition(
            id = "cemu",
            displayName = "Cemu",
            packageNames = listOf("info.cemu.cemu"),
            platforms = listOf("Wii U")
        ),
        EmulatorDefinition(
            id = "rpcsx",
            displayName = "RPCSX",
            packageNames = listOf("net.rpcsx"),
            platforms = listOf("PlayStation 4")
        )
    )

    private val byPackage: Map<String, EmulatorDefinition> =
        definitions
            .flatMap { definition ->
                definition.packageNames.map { packageName ->
                    packageName to definition
                }
            }
            .toMap()

    fun findByPackage(packageName: String): EmulatorDefinition? =
        byPackage[packageName]
}
