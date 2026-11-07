package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object EmployeeHubKeys {
    // CashierProfileView
    val myProfile = StringKey("employee_hub.my_profile")

    // ManagerHubView
    val hubTitle = StringKey("employee_hub.hub_title")
    val newEmployee = StringKey("employee_hub.new_employee")
    val allEmployees = StringKey("employee_hub.all_employees")

    // EmployeeDetailTabs
    val tabOverview = StringKey("employee_hub.tab_overview")
    val tabSchedule = StringKey("employee_hub.tab_schedule")
    val tabTimeEntries = StringKey("employee_hub.tab_time_entries")
    val tabSettings = StringKey("employee_hub.tab_settings")

    // Aliases for adminApp StaffScreen (ListDetailPaneScaffold usage). The tab* targets are
    // referenced ONLY through these aliases, so the orphan survey flags them — deliberately kept.
    val overviewTab = tabOverview
    val scheduleTab = tabSchedule
    val timeEntriesTab = tabTimeEntries
    val settingsTab = tabSettings

    // OverviewTab
    val personalInformation = StringKey("employee_hub.personal_information")
    val username = StringKey("employee_hub.username")
    val firstName = StringKey("employee_hub.first_name")
    val lastName = StringKey("employee_hub.last_name")
    val email = StringKey("employee_hub.email")
    val phone = StringKey("employee_hub.phone")
    val dateOfBirth = StringKey("employee_hub.date_of_birth")
    val completeProfilePersonal = StringKey("employee_hub.complete_profile_personal")
    val address = StringKey("employee_hub.address")
    val street = StringKey("employee_hub.street")
    val city = StringKey("employee_hub.city")
    val postalCode = StringKey("employee_hub.postal_code")
    val country = StringKey("employee_hub.country")
    val state = StringKey("employee_hub.state")
    val completeProfileAddress = StringKey("employee_hub.complete_profile_address")
    val employment = StringKey("employee_hub.employment")
    val role = StringKey("employee_hub.role")
    val jobTitle = StringKey("employee_hub.job_title")
    val hireDate = StringKey("employee_hub.hire_date")
    val hourlyRate = StringKey("employee_hub.hourly_rate")
    val salary = StringKey("employee_hub.salary")
    val completeProfileEmployment = StringKey("employee_hub.complete_profile_employment")

    // ScheduleTab
    val breakDuration = StringKey("employee_hub.break_duration")
    val monShort = StringKey("employee_hub.mon_short")
    val tueShort = StringKey("employee_hub.tue_short")
    val wedShort = StringKey("employee_hub.wed_short")
    val thuShort = StringKey("employee_hub.thu_short")
    val friShort = StringKey("employee_hub.fri_short")
    val satShort = StringKey("employee_hub.sat_short")
    val sunShort = StringKey("employee_hub.sun_short")

    // EditDayScheduleDialog
    val cancel = StringKey("employee_hub.cancel")
    val save = StringKey("employee_hub.save")

    // TimeEntriesTab
    val previousMonth = StringKey("employee_hub.previous_month")
    val nextMonth = StringKey("employee_hub.next_month")
    val noTimeEntries = StringKey("employee_hub.no_time_entries")
    val timeEntries = StringKey("employee_hub.time_entries")
    val monthlyOverview = StringKey("employee_hub.monthly_overview")
    val target = StringKey("employee_hub.target")
    val actual = StringKey("employee_hub.actual")
    val days = StringKey("employee_hub.days")

    // EmployeeSettingsTab
    val resetPassword = StringKey("employee_hub.reset_password")
    val pinAccessCode = StringKey("employee_hub.pin_access_code")
    val resetPin = StringKey("employee_hub.reset_pin")
    val newPassword = StringKey("employee_hub.new_password")
    val confirmPassword = StringKey("employee_hub.confirm_password")
    val newPin = StringKey("employee_hub.new_pin")
    val confirmPin = StringKey("employee_hub.confirm_pin")
    val language = StringKey("employee_hub.language")

    // CashierProfileView
    val editProfile = StringKey("employee_hub.edit_profile")
    val changePin = StringKey("employee_hub.change_pin")
    val mySchedule = StringKey("employee_hub.my_schedule")
    val noScheduleConfigured = StringKey("employee_hub.no_schedule_configured")
    val off = StringKey("employee_hub.off")
    val contractedHours = StringKey("employee_hub.contracted_hours")
    val hoursPerWeek = StringKey("employee_hub.hours_per_week")
    val noMonthlyData = StringKey("employee_hub.no_monthly_data")
    val remaining = StringKey("employee_hub.remaining")
    val viewEntries = StringKey("employee_hub.view_entries")
    val submitMonth = StringKey("employee_hub.submit_month")
    val submitted = StringKey("employee_hub.submitted")
    val preferences = StringKey("employee_hub.preferences")
    val readOnly = StringKey("employee_hub.read_only")
    val profileUpdated = StringKey("employee_hub.profile_updated")
    val passwordChanged = StringKey("employee_hub.password_changed")
    val pinChanged = StringKey("employee_hub.pin_changed")
    val monthSubmitted = StringKey("employee_hub.month_submitted")
    val languageUpdated = StringKey("employee_hub.language_updated")

    // EditProfileDialog
    val editProfileTitle = StringKey("employee_hub.edit_profile_title")
    val fullName = StringKey("employee_hub.full_name")
    val contactInformation = StringKey("employee_hub.contact_information")
    val phoneNumber = StringKey("employee_hub.phone_number")
    val personalDetails = StringKey("employee_hub.personal_details")
    val dateOfBirthFormat = StringKey("employee_hub.date_of_birth_format")
    val dateOfBirthPlaceholder = StringKey("employee_hub.date_of_birth_placeholder")

    // ApprovalsView
    val hideDetails = StringKey("employee_hub.hide_details")
    val approvedStatus = StringKey("employee_hub.approved_status")
    val rejectedStatus = StringKey("employee_hub.rejected_status")
    val draftStatus = StringKey("employee_hub.draft_status")

    // CreateEmployeeDialog

    // Month names (full)
    val january = StringKey("employee_hub.january")
    val february = StringKey("employee_hub.february")
    val march = StringKey("employee_hub.march")
    val april = StringKey("employee_hub.april")
    val may = StringKey("employee_hub.may")
    val june = StringKey("employee_hub.june")
    val july = StringKey("employee_hub.july")
    val august = StringKey("employee_hub.august")
    val september = StringKey("employee_hub.september")
    val october = StringKey("employee_hub.october")
    val november = StringKey("employee_hub.november")
    val december = StringKey("employee_hub.december")

    // Month names (short)
    val janShort = StringKey("employee_hub.jan_short")
    val febShort = StringKey("employee_hub.feb_short")
    val marShort = StringKey("employee_hub.mar_short")
    val aprShort = StringKey("employee_hub.apr_short")
    val mayShort = StringKey("employee_hub.may_short")
    val junShort = StringKey("employee_hub.jun_short")
    val julShort = StringKey("employee_hub.jul_short")
    val augShort = StringKey("employee_hub.aug_short")
    val sepShort = StringKey("employee_hub.sep_short")
    val octShort = StringKey("employee_hub.oct_short")
    val novShort = StringKey("employee_hub.nov_short")
    val decShort = StringKey("employee_hub.dec_short")
}
