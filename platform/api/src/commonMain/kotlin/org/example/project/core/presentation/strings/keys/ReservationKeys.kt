package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object ReservationKeys {
    // List screen
    val title = StringKey("reservation.title")
    val rules = StringKey("reservation.rules")
    val addReservation = StringKey("reservation.add_reservation")
    val noReservationsForDate = StringKey("reservation.no_reservations_for_date")
    val previousDay = StringKey("reservation.previous_day")
    val nextDay = StringKey("reservation.next_day")
    val dateFormat = StringKey("reservation.date_format") // {0}=shortDayOfWeek, {1}=shortMonth, {2}=dayOfMonth, {3}=year

    // Short day names
    val dayMon = StringKey("reservation.day_mon")
    val dayTue = StringKey("reservation.day_tue")
    val dayWed = StringKey("reservation.day_wed")
    val dayThu = StringKey("reservation.day_thu")
    val dayFri = StringKey("reservation.day_fri")
    val daySat = StringKey("reservation.day_sat")
    val daySun = StringKey("reservation.day_sun")

    // Short month names
    val monthJan = StringKey("reservation.month_jan")
    val monthFeb = StringKey("reservation.month_feb")
    val monthMar = StringKey("reservation.month_mar")
    val monthApr = StringKey("reservation.month_apr")
    val monthMay = StringKey("reservation.month_may")
    val monthJun = StringKey("reservation.month_jun")
    val monthJul = StringKey("reservation.month_jul")
    val monthAug = StringKey("reservation.month_aug")
    val monthSep = StringKey("reservation.month_sep")
    val monthOct = StringKey("reservation.month_oct")
    val monthNov = StringKey("reservation.month_nov")
    val monthDec = StringKey("reservation.month_dec")
    val partySize = StringKey("reservation.party_size")
    val durationMinutes = StringKey("reservation.duration_minutes") // {0}min
    val markSeated = StringKey("reservation.mark_seated")
    val markNoShow = StringKey("reservation.mark_no_show")
    val markCompleted = StringKey("reservation.mark_completed")
    val statusConfirmed = StringKey("reservation.status_confirmed")
    val statusSeated = StringKey("reservation.status_seated")
    val statusCompleted = StringKey("reservation.status_completed")
    val statusCancelled = StringKey("reservation.status_cancelled")
    val statusNoShow = StringKey("reservation.status_no_show")

    // Form screen
    val newReservation = StringKey("reservation.new_reservation")
    val editReservation = StringKey("reservation.edit_reservation")
    val guestName = StringKey("reservation.guest_name")
    val phone = StringKey("reservation.phone")
    val partySizeLabel = StringKey("reservation.party_size_label")
    val decrease = StringKey("reservation.decrease")
    val increase = StringKey("reservation.increase")
    val dateRequired = StringKey("reservation.date_required")
    val timeRequired = StringKey("reservation.time_required")
    val timeSlotRequired = StringKey("reservation.time_slot_required")
    val duration = StringKey("reservation.duration")
    val durationFromRule = StringKey("reservation.duration_from_rule") // {0} min (from rule)
    val available = StringKey("reservation.available")
    val reserved = StringKey("reservation.reserved")
    val selectTime = StringKey("reservation.select_time")

    // Rules screen
    val rulesTitle = StringKey("reservation.rules_title")
    val addRule = StringKey("reservation.add_rule")
    val noRulesConfigured = StringKey("reservation.no_rules_configured")
    val newRule = StringKey("reservation.new_rule")
    val editRule = StringKey("reservation.edit_rule")
    val fixedDuration = StringKey("reservation.fixed_duration")
    val timeSlots = StringKey("reservation.time_slots")
    val durationMinutesLabel = StringKey("reservation.duration_minutes_label")
    val startTime = StringKey("reservation.start_time")
    val endTime = StringKey("reservation.end_time")
    val addTimeSlot = StringKey("reservation.add_time_slot")
    val minuteBlocks = StringKey("reservation.minute_blocks") // {0}min blocks
    val slotsPrefix = StringKey("reservation.slots_prefix")

    // Customer
    val customerFound = StringKey("reservation.customer_found") // "Customer found: {0}"

    // Search
    val searchReservations = StringKey("reservation.search_reservations")
    val searchHint = StringKey("reservation.search_hint")
    val noSearchResults = StringKey("reservation.no_search_results")

    // Table Assignment
    val noTablesAssigned = StringKey("reservation.no_tables_assigned")
    val assignTables = StringKey("reservation.assign_tables")
    val autoAssign = StringKey("reservation.auto_assign")
    val unassignAll = StringKey("reservation.unassign_all")
    val manualAssign = StringKey("reservation.manual_assign")
    val group = StringKey("reservation.group")
    val addGroup = StringKey("reservation.add_group")
    val assignSelected = StringKey("reservation.assign_selected")
    val currentAssignments = StringKey("reservation.current_assignments")
    val confirmUnassign = StringKey("reservation.confirm_unassign")
    val seatsOf = StringKey("reservation.seats_of")
    val noAvailableTables = StringKey("reservation.no_available_tables")

    // Day status + time slots
    val allDay = StringKey("reservation.all_day")
    val closedDay = StringKey("reservation.closed_day")
    val closedDayWithReason = StringKey("reservation.closed_day_with_reason") // "Closed: {0}"
    val bulkAutoAssign = StringKey("reservation.bulk_auto_assign")
    val noReservationsInSlot = StringKey("reservation.no_reservations_in_slot")
    val overrideBadge = StringKey("reservation.override_badge") // "Override: {0}"
    val reservationCount = StringKey("reservation.reservation_count") // "{0} reservations"
    val reservationCountOne = StringKey("reservation.reservation_count_one") // "1 reservation"

    // Success toasts
    val reservationCreated = StringKey("reservation.reservation_created")
    val reservationUpdated = StringKey("reservation.reservation_updated")
    val ruleCreated = StringKey("reservation.rule_created")
    val ruleUpdated = StringKey("reservation.rule_updated")
    val ruleDeleted = StringKey("reservation.rule_deleted")
    val reservationStatusUpdated = StringKey("reservation.reservation_status_updated")
    val reservationDeleted = StringKey("reservation.reservation_deleted")
}
