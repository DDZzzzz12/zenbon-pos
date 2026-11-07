package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object ChoiceGroupKeys {
    val required = StringKey("choice_group.required")
    val optional = StringKey("choice_group.optional")
    val pickOne = StringKey("choice_group.pick_one")
    val pickUpTo = StringKey("choice_group.pick_up_to") // {0} = max
    val pickAny = StringKey("choice_group.pick_any")
    val discardItemTitle = StringKey("choice_group.discard_title")
    val discardItemMessage = StringKey("choice_group.discard_message")
    val customizable = StringKey("choice_group.customizable")
    val choiceGroups = StringKey("choice_group.groups")
    val addGroup = StringKey("choice_group.add_group")
    val minSelections = StringKey("choice_group.min_selections")
    val maxSelections = StringKey("choice_group.max_selections")

    // Admin manager
    val manageGroupsTitle = StringKey("choice_group.manage_title")
    val createGroupTitle = StringKey("choice_group.create_title")
    val editGroupTitle = StringKey("choice_group.edit_title")
    val deleteGroupTitle = StringKey("choice_group.delete_title")
    val deleteGroupConfirmation = StringKey("choice_group.delete_confirmation") // {0} = name
    val groupName = StringKey("choice_group.name")
    val noGroups = StringKey("choice_group.no_groups")
    val active = StringKey("choice_group.active")
    val optionCount = StringKey("choice_group.option_count") // {0} = count

    // Options editor
    val options = StringKey("choice_group.options")
    val addOption = StringKey("choice_group.add_option")
    val noOptionsSelected = StringKey("choice_group.no_options_selected")
    val noExtrasAvailable = StringKey("choice_group.no_extras_available")
    val priceDelta = StringKey("choice_group.price_delta")
    val isDefault = StringKey("choice_group.is_default")
    val invalidSelectionRange = StringKey("choice_group.invalid_selection_range")

    // Product editor attach section
    val attachedGroups = StringKey("choice_group.attached_groups")
    val attachGroups = StringKey("choice_group.attach_groups")
    val noAttachedGroups = StringKey("choice_group.no_attached_groups")
    val saveProductFirst = StringKey("choice_group.save_product_first")
    val moveUp = StringKey("choice_group.move_up")
    val moveDown = StringKey("choice_group.move_down")
}
