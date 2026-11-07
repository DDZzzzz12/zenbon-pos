
package org.example.project.core.domain.enums.setup

import kotlinx.serialization.Serializable

/**
 * Setup step priorities for organizing initialization flow.
 *
 * Enables priority-based navigation and flexible completion order
 * where higher priority steps must be completed before lower priority ones.
 */
@Serializable
enum class SetupPriority(
    val order: Int,
    val title: String,
    val description: String,
) {
    FIRST(1, "Critical Setup", "Essential system components required for basic operation"),
    SECOND(2, "Core Configuration", "Primary business configuration and settings"),
    THIRD(3, "Advanced Setup", "Additional features and optional configurations"),
    FOURTH(4, "Optional Setup", "Optional configurations for advanced users"),
}

/**
 * System setup steps enumeration with priority-based organization and metadata.
 *
 * Provides type-safe representation of initialization steps with
 * sequential ordering, priority classification, display information, and step descriptions.
 */
@Serializable
enum class SetupSteps(
    val stepId: Int,
    val displayName: String,
    val description: String,
    val priority: SetupPriority,
) {
    /** Step 1: Create owner/administrator account - FIRST PRIORITY */
    OWNER_SETUP(
        stepId = 0,
        displayName = "Owner Setup",
        description = "Create the initial owner/administrator account with full permissions",
        priority = SetupPriority.FIRST,
    ),

    /** Step 2: Configure shop information and business details - SECOND PRIORITY */
    SHOP_SETUP(
        stepId = 1,
        displayName = "Shop Setup",
        description = "Configure shop information, address, and business details",
        priority = SetupPriority.SECOND,
    ),

    /** Step 3: Configure TSE mode - SECOND PRIORITY */
    TSE_MODE_SETUP(
        stepId = 2,
        displayName = "TSE Mode Setup",
        description = "Configure TSE mode for transactions",
        priority = SetupPriority.SECOND,
    ),

    /** Step 4: Configure server and PDF storage settings - FIRST PRIORITY */
    SERVER_CONFIG_SETUP(
        stepId = 3,
        displayName = "Server Configuration",
        description = "Configure server port, logging, and PDF storage settings",
        priority = SetupPriority.FIRST,
    ),

    /** Step 5: Configure payment types and methods - THIRD PRIORITY */
    PAYMENT_TYPES_SETUP(
        stepId = 4,
        displayName = "Payment Types Setup",
        description = "Configure available payment methods (cash, cards, etc.)",
        priority = SetupPriority.THIRD,
    ),

    /** Step 6: Configure VAT rates and tax settings - THIRD PRIORITY */
    VAT_SETUP(
        stepId = 5,
        displayName = "VAT Setup",
        description = "Set up tax rates for different product categories",
        priority = SetupPriority.THIRD,
    ),
    ;

    companion object {
        /**
         * Gets all setup steps ordered by stepId.
         *
         * @return List of setup steps in execution order
         */
        fun getAllStepsOrdered(): List<SetupSteps> = entries.sortedBy { it.stepId }

        /**
         * Gets a setup step by its sequential ID.
         *
         * @param stepId The step identifier
         * @return SetupSteps matching the ID, or null if not found
         */
        fun getByStepId(stepId: Int): SetupSteps? = entries.find { it.stepId == stepId }

        /**
         * Gets the next setup step after the given step.
         *
         * @param currentStep The current setup step
         * @return Next step in sequence, or null if this is the last step
         */
        fun getNextStep(currentStep: SetupSteps): SetupSteps? {
            val allSteps = getAllStepsOrdered()
            val currentIndex = allSteps.indexOf(currentStep)
            return if (currentIndex >= 0 && currentIndex < allSteps.size - 1) {
                allSteps[currentIndex + 1]
            } else {
                null
            }
        }

        /**
         * Gets the previous setup step before the given step.
         *
         * @param currentStep The current setup step
         * @return Previous step in sequence, or null if this is the first step
         */
        fun getPreviousStep(currentStep: SetupSteps): SetupSteps? {
            val allSteps = getAllStepsOrdered()
            val currentIndex = allSteps.indexOf(currentStep)
            return if (currentIndex > 0) {
                allSteps[currentIndex - 1]
            } else {
                null
            }
        }

        /**
         * Gets the first step in the setup sequence.
         *
         * @return First setup step
         */
        fun getFirstStep(): SetupSteps = OWNER_SETUP

        /**
         * Gets the last step in the setup sequence.
         *
         * @return Last setup step
         */
        fun getLastStep(): SetupSteps = VAT_SETUP

        /**
         * Calculates progress percentage for given completed steps.
         *
         * @param completedSteps List of completed setup steps
         * @return Progress percentage (0-100)
         */
        fun calculateProgress(completedSteps: List<SetupSteps>): Int {
            val totalSteps = entries.size
            return if (totalSteps == 0) 100 else (completedSteps.size * 100) / totalSteps
        }

        /**
         * Checks if first and second priority steps are completed.
         * These are considered essential for basic system operation.
         *
         * @param completedSteps List of completed setup steps
         * @return True if essential steps are completed
         */
        fun areEssentialStepsCompleted(completedSteps: List<SetupSteps>): Boolean {
            val essentialSteps = getEssentialSteps()
            return essentialSteps.all { it in completedSteps }
        }

        fun areAllStepsCompleted(completedSteps: List<SetupSteps>): Boolean = completedSteps.all { it in entries }

        /**
         * Gets steps that are optional (third priority).
         *
         * @return List of optional setup steps
         */
        fun getOptionalSteps(): List<SetupSteps> = entries.filter { it.priority == SetupPriority.FOURTH }

        /**
         * Gets steps that are essential (first and second priority).
         *
         * @return List of essential setup steps
         */
        fun getEssentialSteps(): List<SetupSteps> = entries.filter { it.priority == SetupPriority.FIRST || it.priority == SetupPriority.SECOND }

        /**
         * Gets all steps for a specific priority level.
         *
         * @param priority The priority level to filter by
         * @return List of setup steps for the given priority
         */
        fun getStepsForPriority(priority: SetupPriority): List<SetupSteps> = entries.filter { it.priority == priority }

        /**
         * Checks if a priority level is completed.
         *
         * @param priority The priority level to check
         * @param completedSteps List of completed setup steps
         * @return True if all steps for the priority are completed
         */
        fun isPriorityCompleted(
            priority: SetupPriority,
            completedSteps: List<SetupSteps>,
        ): Boolean {
            val prioritySteps = getStepsForPriority(priority)
            return prioritySteps.all { it in completedSteps }
        }

        /**
         * Gets the current priority that needs to be completed.
         *
         * @param completedSteps List of completed setup steps
         * @return Current priority level, or null if all are completed
         */
        fun getCurrentPriority(completedSteps: List<SetupSteps>): SetupPriority? =
            SetupPriority.entries.find { priority ->
                !isPriorityCompleted(priority, completedSteps)
            }
    }

    /**
     * Checks if this step is optional (third priority).
     *
     * @return True if this step is optional
     */
    fun isOptional(): Boolean = priority == SetupPriority.FOURTH

    /**
     * Checks if this step is essential (first or second priority).
     *
     * @return True if this step is essential
     */
    fun isEssential(): Boolean = priority == SetupPriority.FIRST || priority == SetupPriority.SECOND
}
