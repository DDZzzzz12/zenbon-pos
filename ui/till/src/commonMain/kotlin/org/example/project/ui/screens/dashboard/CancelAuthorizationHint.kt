package org.example.project.ui.screens.dashboard

/**
 * The line under the item count in the Storno dialog (QA emu22 NOTE-ee).
 *
 * It used to say „Erfordert Manager-Autorisierung.“ unconditionally — also on an order station
 * where a manager had just signed in and the Storno executes at once, attributed to that manager.
 *
 * - [AUTHORIZED_BY]: a manager's temporary session is active — name who authorizes it.
 * - [NONE]: the person holds `ORDER_ITEM_CANCEL` themselves — nothing waits for anyone.
 * - [REQUIRED]: neither — at a till the cancel becomes an approval request.
 */
enum class CancelAuthorizationHint {
    REQUIRED,
    AUTHORIZED_BY,
    NONE,
    ;

    companion object {
        /**
         * @param activeManagerName the manager of the active temporary session, null without one.
         * @param holdsItemCancel whether the session holds `ORDER_ITEM_CANCEL` right now (own or lent).
         */
        fun of(
            activeManagerName: String?,
            holdsItemCancel: Boolean,
        ): CancelAuthorizationHint =
            when {
                !activeManagerName.isNullOrBlank() -> AUTHORIZED_BY
                holdsItemCancel -> NONE
                else -> REQUIRED
            }
    }
}
