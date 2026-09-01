package carepad.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CarePadSettingsContractsTest {

    @Test
    fun constantsMatchTechnicalDecisions() {
        assertEquals("settings_inline", CarePadModuleCapabilities.SETTINGS_INLINE)
        assertEquals("settings_delegated", CarePadModuleCapabilities.SETTINGS_DELEGATED)
        assertEquals("dev.carepad.action.OPEN_MODULE_SETTINGS", CarePadModuleActions.OPEN_MODULE_SETTINGS)
        assertEquals("dev.carepad.settings.GET_SNAPSHOT", CarePadSettingsMethods.GET_SNAPSHOT)
        assertEquals("dev.carepad.settings.WRITE_BOOLEAN", CarePadSettingsMethods.WRITE_BOOLEAN)
        assertEquals("dev.carepad.settings.WRITE_SINGLE_CHOICE", CarePadSettingsMethods.WRITE_SINGLE_CHOICE)
        assertEquals(1, CarePadSettingsProtocol.CONTRACT_VERSION)
        assertEquals("com.example.module.carepad.settings", CarePadSettingsAuthorities.forPackage("com.example.module"))
    }

    @Test
    fun booleanItemConstructsWithValidData() {
        val item = CarePadSettingItem.BooleanItem(
            id = "test.bool",
            title = "Test Boolean",
            description = "Optional description",
            value = true
        )
        assertEquals("test.bool", item.id)
        assertEquals(CarePadSettingType.BOOLEAN, item.type)
        assertEquals("Test Boolean", item.title)
        assertEquals("Optional description", item.description)
        assertTrue(item.value)
        assertTrue(item.editable)
        assertEquals(CarePadItemAvailability.AVAILABLE, item.availability)
    }

    @Test
    fun singleChoiceItemValidatesOptionsAndSelection() {
        val options = listOf(
            CarePadSettingOption(optionId = "opt1", label = "Option 1"),
            CarePadSettingOption(optionId = "opt2", label = "Option 2")
        )
        val item = CarePadSettingItem.SingleChoiceItem(
            id = "test.choice",
            title = "Test Choice",
            selectedOptionId = "opt1",
            options = options
        )
        assertEquals(CarePadSettingType.SINGLE_CHOICE, item.type)
        assertEquals("opt1", item.selectedOptionId)
        assertEquals(2, item.options.size)

        expectIllegalArgument {
            CarePadSettingItem.SingleChoiceItem(
                id = "test.choice",
                title = "Test Choice",
                selectedOptionId = "unknown_opt",
                options = options
            )
        }

        expectIllegalArgument {
            CarePadSettingItem.SingleChoiceItem(
                id = "test.choice",
                title = "Test Choice",
                selectedOptionId = "opt1",
                options = listOf(
                    CarePadSettingOption(optionId = "opt1", label = "Option 1"),
                    CarePadSettingOption(optionId = "opt1", label = "Duplicate")
                )
            )
        }
    }

    @Test
    fun readOnlyInfoItemIsNeverEditable() {
        val item = CarePadSettingItem.ReadOnlyInfoItem(
            id = "test.info",
            title = "Version Info",
            value = "1.0.0-beta"
        )
        assertEquals(CarePadSettingType.READ_ONLY_INFO, item.type)
        assertFalse(item.editable)
        assertEquals("1.0.0-beta", item.value)
    }

    @Test
    fun snapshotRequiresUniqueItemIds() {
        val items = listOf(
            CarePadSettingItem.BooleanItem(id = "item1", title = "Item 1", value = true),
            CarePadSettingItem.BooleanItem(id = "item1", title = "Duplicate ID", value = false)
        )
        expectIllegalArgument {
            CarePadSettingsSnapshot(catalogRevision = "rev1", items = items)
        }
    }

    @Test
    fun snapshotConstructsWithValidData() {
        val items = listOf(
            CarePadSettingItem.BooleanItem(id = "item1", title = "Item 1", value = true),
            CarePadSettingItem.ReadOnlyInfoItem(id = "item2", title = "Item 2", value = "val")
        )
        val snapshot = CarePadSettingsSnapshot(
            contractVersion = 1,
            catalogRevision = "rev100",
            items = items
        )
        assertEquals(1, snapshot.contractVersion)
        assertEquals("rev100", snapshot.catalogRevision)
        assertEquals(2, snapshot.items.size)
    }

    @Test
    fun appliedRequiresExactlyOneEffectiveValue() {
        val booleanApplied = CarePadSettingResult.Applied(
            catalogRevision = "rev2",
            effectiveValueBoolean = true
        )
        assertEquals(true, booleanApplied.effectiveValueBoolean)

        val choiceApplied = CarePadSettingResult.Applied(
            catalogRevision = "rev2",
            effectiveSelectedOptionId = "vulkan"
        )
        assertEquals("vulkan", choiceApplied.effectiveSelectedOptionId)

        expectIllegalArgument {
            CarePadSettingResult.Applied(catalogRevision = "rev2")
        }
        expectIllegalArgument {
            CarePadSettingResult.Applied(
                catalogRevision = "rev2",
                effectiveValueBoolean = true,
                effectiveSelectedOptionId = "vulkan"
            )
        }
    }

    @Test
    fun rejectedCannotContainAmbiguousEffectiveValues() {
        CarePadSettingResult.Rejected(catalogRevision = "rev1")
        CarePadSettingResult.Rejected(catalogRevision = "rev1", effectiveValueBoolean = false)
        CarePadSettingResult.Rejected(catalogRevision = "rev1", effectiveSelectedOptionId = "opengl")

        expectIllegalArgument {
            CarePadSettingResult.Rejected(
                catalogRevision = "rev1",
                effectiveValueBoolean = false,
                effectiveSelectedOptionId = "opengl"
            )
        }
    }

    @Test
    fun settingResultsEnforceErrorBounds() {
        val rejected = CarePadSettingResult.Rejected(
            catalogRevision = "rev1",
            message = "Value rejected"
        )
        assertEquals("Value rejected", rejected.message)

        val stale = CarePadSettingResult.Stale(
            currentCatalogRevision = "rev3",
            message = "Catalog changed"
        )
        assertEquals("rev3", stale.currentCatalogRevision)

        val unavailable = CarePadSettingResult.Unavailable(message = "Unavailable")
        assertEquals("Unavailable", unavailable.message)

        val incompatible = CarePadSettingResult.Incompatible(
            supportedContractVersion = 1,
            message = "Unsupported"
        )
        assertEquals(1, incompatible.supportedContractVersion)

        val longError = "a".repeat(CarePadSettingsLimits.MAX_ERROR_MESSAGE_LENGTH + 1)
        expectIllegalArgument {
            CarePadSettingResult.Rejected(catalogRevision = "rev1", message = longError)
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
