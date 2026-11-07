package org.example.project.features.main.presentation.screens

import org.example.project.core.domain.enums.setup.Language

/**
 * Multilingual string resources for MainScreen.
 * Provides translations for navigation items, headers, and UI elements.
 */
object MainScreenStrings {
    fun getAppTitle(language: Language): String =
        when (language) {
            Language.GERMAN -> "POS System"
            Language.ENGLISH -> "POS System"
            Language.CHINESE -> "POS 系统"
        }

    // Navigation Items
    fun getDashboard(language: Language): String =
        when (language) {
            Language.GERMAN -> "Dashboard"
            Language.ENGLISH -> "Dashboard"
            Language.CHINESE -> "仪表板"
        }

    fun getInventory(language: Language): String =
        when (language) {
            Language.GERMAN -> "Lager"
            Language.ENGLISH -> "Inventory"
            Language.CHINESE -> "库存"
        }

    fun getEmployee(language: Language): String =
        when (language) {
            Language.GERMAN -> "Personal"
            Language.ENGLISH -> "Staff"
            Language.CHINESE -> "员工"
        }

    fun getSetting(language: Language): String =
        when (language) {
            Language.GERMAN -> "Einstellungen"
            Language.ENGLISH -> "Settings"
            Language.CHINESE -> "设置"
        }

    fun getReports(language: Language): String =
        when (language) {
            Language.GERMAN -> "Berichte"
            Language.ENGLISH -> "Reports"
            Language.CHINESE -> "报告"
        }

    fun getVouchers(language: Language): String =
        when (language) {
            Language.GERMAN -> "Gutscheine"
            Language.ENGLISH -> "Vouchers"
            Language.CHINESE -> "代金券"
        }

    fun getFinancialDocuments(language: Language): String =
        when (language) {
            Language.GERMAN -> "Finanzen"
            Language.ENGLISH -> "Financial"
            Language.CHINESE -> "财务文档"
        }

    fun getAuditLog(language: Language): String =
        when (language) {
            Language.GERMAN -> "Protokoll"
            Language.ENGLISH -> "Audit Log"
            Language.CHINESE -> "审计日志"
        }

    fun getTimeTracking(language: Language): String =
        when (language) {
            Language.GERMAN -> "Zeiterfassung"
            Language.ENGLISH -> "Time Tracking"
            Language.CHINESE -> "考勤"
        }

    fun getLogout(language: Language): String =
        when (language) {
            Language.GERMAN -> "Abmelden"
            Language.ENGLISH -> "Logout"
            Language.CHINESE -> "登出"
        }

    // Employee Info Labels
    fun getEmployeeName(language: Language): String =
        when (language) {
            Language.GERMAN -> "Name:"
            Language.ENGLISH -> "Name:"
            Language.CHINESE -> "姓名:"
        }

    fun getEmployeeNumber(language: Language): String =
        when (language) {
            Language.GERMAN -> "Mitarbeiter-Nr.:"
            Language.ENGLISH -> "Employee No.:"
            Language.CHINESE -> "员工号:"
        }

    fun getEmployeeRole(language: Language): String =
        when (language) {
            Language.GERMAN -> "Rolle:"
            Language.ENGLISH -> "Role:"
            Language.CHINESE -> "角色:"
        }

    // Exit Dialog
    fun getExitDialogTitle(language: Language): String =
        when (language) {
            Language.GERMAN -> "Anwendung beenden"
            Language.ENGLISH -> "Exit Application"
            Language.CHINESE -> "退出应用"
        }

    fun getExitDialogMessage(language: Language): String =
        when (language) {
            Language.GERMAN -> "Möchten Sie das POS-System wirklich beenden?"
            Language.ENGLISH -> "Are you sure you want to exit the POS System?"
            Language.CHINESE -> "您确定要退出 POS 系统吗？"
        }

    fun getExitConfirmButton(language: Language): String =
        when (language) {
            Language.GERMAN -> "Beenden"
            Language.ENGLISH -> "Exit"
            Language.CHINESE -> "退出"
        }

    // Sync Loading
    fun getSyncTitle(language: Language): String =
        when (language) {
            Language.GERMAN -> "Synchronisierung"
            Language.ENGLISH -> "Synchronizing"
            Language.CHINESE -> "同步中"
        }

    fun getSyncMessage(language: Language): String =
        when (language) {
            Language.GERMAN -> "Daten werden mit dem Server synchronisiert, bitte warten..."
            Language.ENGLISH -> "Syncing data with server, please wait..."
            Language.CHINESE -> "正在与服务器同步数据，请稍候..."
        }

    // Shutdown System
    fun getShutdown(language: Language): String =
        when (language) {
            Language.GERMAN -> "Herunterfahren"
            Language.ENGLISH -> "Shutdown"
            Language.CHINESE -> "关机"
        }

    fun getShutdownDialogTitle(language: Language): String =
        when (language) {
            Language.GERMAN -> "System herunterfahren"
            Language.ENGLISH -> "Shutdown System"
            Language.CHINESE -> "关闭系统"
        }

    fun getShutdownDialogMessage(language: Language): String =
        when (language) {
            Language.GERMAN -> "Möchten Sie das System wirklich herunterfahren?"
            Language.ENGLISH -> "Are you sure you want to shut down the system?"
            Language.CHINESE -> "您确定要关闭系统吗？"
        }

    fun getShutdownConfirmButton(language: Language): String =
        when (language) {
            Language.GERMAN -> "Herunterfahren"
            Language.ENGLISH -> "Shutdown"
            Language.CHINESE -> "关机"
        }

    fun getCancel(language: Language): String =
        when (language) {
            Language.GERMAN -> "Abbrechen"
            Language.ENGLISH -> "Cancel"
            Language.CHINESE -> "取消"
        }

    // Status Bar
    fun getTerminalLabel(language: Language): String =
        when (language) {
            Language.GERMAN -> "Terminal"
            Language.ENGLISH -> "Terminal"
            Language.CHINESE -> "终端"
        }

    fun getConnected(language: Language): String =
        when (language) {
            Language.GERMAN -> "Verbunden"
            Language.ENGLISH -> "Connected"
            Language.CHINESE -> "已连接"
        }

    fun getDisconnected(language: Language): String =
        when (language) {
            Language.GERMAN -> "Getrennt"
            Language.ENGLISH -> "Disconnected"
            Language.CHINESE -> "已断开"
        }

    fun getConnecting(language: Language): String =
        when (language) {
            Language.GERMAN -> "Verbinde..."
            Language.ENGLISH -> "Connecting..."
            Language.CHINESE -> "连接中..."
        }

    fun getServerShort(language: Language): String =
        when (language) {
            Language.GERMAN -> "Server"
            Language.ENGLISH -> "Server"
            Language.CHINESE -> "服务器"
        }

    fun getServerRunning(language: Language): String =
        when (language) {
            Language.GERMAN -> "Server läuft"
            Language.ENGLISH -> "Server Running"
            Language.CHINESE -> "服务器运行中"
        }

    fun getServerStarting(language: Language): String =
        when (language) {
            Language.GERMAN -> "Server startet..."
            Language.ENGLISH -> "Server Starting..."
            Language.CHINESE -> "服务器启动中..."
        }

    fun getServerStopped(language: Language): String =
        when (language) {
            Language.GERMAN -> "Server gestoppt"
            Language.ENGLISH -> "Server Stopped"
            Language.CHINESE -> "服务器已停止"
        }

    fun getServerError(language: Language): String =
        when (language) {
            Language.GERMAN -> "Serverfehler"
            Language.ENGLISH -> "Server Error"
            Language.CHINESE -> "服务器错误"
        }

    // TSE Status Bar
    fun getTseConnected(language: Language): String =
        when (language) {
            Language.GERMAN -> "TSE Verbunden"
            Language.ENGLISH -> "TSE Connected"
            Language.CHINESE -> "TSE 已连接"
        }

    fun getTseDisconnected(language: Language): String =
        when (language) {
            Language.GERMAN -> "TSE Getrennt"
            Language.ENGLISH -> "TSE Disconnected"
            Language.CHINESE -> "TSE 已断开"
        }

    fun getTseDisabled(language: Language): String =
        when (language) {
            Language.GERMAN -> "TSE Deaktiviert"
            Language.ENGLISH -> "TSE Disabled"
            Language.CHINESE -> "TSE 已禁用"
        }

    fun getTseUnknown(language: Language): String =
        when (language) {
            Language.GERMAN -> "TSE Unbekannt"
            Language.ENGLISH -> "TSE Unknown"
            Language.CHINESE -> "TSE 未知"
        }

    // Temporary Session Authentication
    fun getAuthenticatingTitle(language: Language): String =
        when (language) {
            Language.GERMAN -> "Authentifizierung"
            Language.ENGLISH -> "Authenticating"
            Language.CHINESE -> "认证中"
        }

    fun getAuthenticatingMessage(language: Language): String =
        when (language) {
            Language.GERMAN -> "Anmeldedaten werden überprüft..."
            Language.ENGLISH -> "Verifying credentials..."
            Language.CHINESE -> "正在验证凭据..."
        }
}
