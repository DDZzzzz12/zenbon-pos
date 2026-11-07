-- Release-marker migration for v1.0.10 — consolidates the release notes previously
-- maintained in the root CHANGELOG.md into the first row of changelog_release
-- (spec 2026-08-02-changelog-release-notes-table-design.md §5).
-- This row consolidates the v1.0.5 → v1.0.10 release cycle plus the Unreleased work
-- that landed in dev after v1.0.10 shipped.
--
-- Future releases: each new release tag cuts one V{N}__release_x_y_z.sql that INSERTs
-- a fresh row with full EN/DE/ZH translations authored at release-cut time. The root
-- CHANGELOG.md remains as development-side history; changelog_release is the single
-- source of truth for the user-facing "What's new" release notes.
--
-- This migration is write-only by design: application code only ever SELECTs from
-- changelog_release (the table is created in V79).

INSERT INTO changelog_release (
    release_version,
    release_date,
    title_en, title_de, title_zh,
    summary_en, summary_de, summary_zh,
    highlights_en, highlights_de, highlights_zh,
    known_issues_en, known_issues_de, known_issues_zh
) VALUES (
    '1.0.10',
    '2026-05-11',
    'Pre-1.0.11 changelog backfill',
    'Changelog-Backfill vor 1.0.11',
    '1.0.11 之前的变更日志回填',
    'Consolidated release notes for versions v1.0.5 through v1.0.10 plus Unreleased work. The changelog_release table is new in this release and is the single source of truth for user-facing release notes; future releases each ship a dedicated release-marker migration.',
    'Zusammengefasste Versionshinweise für v1.0.5 bis v1.0.10 sowie unveröffentlichte Änderungen. Die Tabelle changelog_release ist neu in dieser Version und ist die einzige Quelle für die nutzerseitigen Versionshinweise; künftige Versionen liefern jeweils eine dedizierte Release-Marker-Migration.',
    'v1.0.5 至 v1.0.10 的版本说明合并，外加未发布的工作。changelog_release 表于本版本首次引入，是面向用户的版本说明的唯一来源；后续版本将每次附送一条专用的发布标记迁移。',
    '["Unreleased — Swissbit SDK v6 upgrade + DSFinV-K round 5-7 audits + TSE fixes","v1.0.10 (2026-05-11) — Pos* family consolidation + Monthly Close DATEV tightening + round-robin assignment","v1.0.9 (2026-05-09) — Auto-update + Monthly Close panel + DATEV cadence + Buchungsstapel CSV + PosException + UiError + JSON logging + signed APK + ZVT card terminal + scheduler + backups + transfer timeline","v1.0.8 (2026-04-18) — WebSocket presence grace period + audit logging for table ops + RouteAuthorizationPlugin temp-session elevation fix","v1.0.7 (2026-04-16) — Promotional vouchers (Aktionsgutscheine) + DSFinV-K promotion export + VAT breakdown table in bill preview","v1.0.6 (2026-04-13) — Critical RouteAuthorizationPlugin bypass fix + Kassenbuch document replace + transfer history timeline + UI restructure into ui/screens/","v1.0.5 (2026-04-10) — see CHANGELOG.md history"]'::jsonb,
    '["Unreleased — Swissbit SDK v6 Upgrade + DSFinV-K Audits Runde 5-7 + TSE-Fixes","v1.0.10 (2026-05-11) — Pos*-Familie Konsolidierung + Monthly Close DATEV-Schärfung + Round-Robin-Zuweisung","v1.0.9 (2026-05-09) — Auto-Update + Monthly-Close-Panel + DATEV-Cadence + Buchungsstapel-CSV + PosException + UiError + JSON-Logging + signiertes APK + ZVT-Kartenterminal + Scheduler + Backups + Transfer-Timeline","v1.0.8 (2026-04-18) — WebSocket-Presence-Grace-Period + Audit-Logging für Tisch-Operationen + RouteAuthorizationPlugin Temp-Session-Elevation-Fix","v1.0.7 (2026-04-16) — Aktionsgutscheine + DSFinV-K Aktions-Export + MwSt-Aufschlüsselungstabelle in Belegvorschau","v1.0.6 (2026-04-13) — Kritischer RouteAuthorizationPlugin-Bypass-Fix + Kassenbuch-Beleg-Ersetzung + Transfer-Historie-Timeline + UI-Restrukturierung in ui/screens/","v1.0.5 (2026-04-10) — siehe CHANGELOG.md-Verlauf"]'::jsonb,
    '["Unreleased — Swissbit SDK v6 升级 + DSFinV-K 第 5-7 轮审计 + TSE 修复","v1.0.10 (2026-05-11) — Pos* 系列组件整合 + Monthly Close DATEV 收紧 + 轮询分配","v1.0.9 (2026-05-09) — 自动更新 + Monthly Close 面板 + DATEV 节奏 + Buchungsstapel CSV + PosException + UiError + JSON 日志 + 签名 APK + ZVT 卡终端 + 调度器 + 备份 + 调拨时间线","v1.0.8 (2026-04-18) — WebSocket 在线宽限期 + 桌位操作审计 + RouteAuthorizationPlugin 临时会话提权修复","v1.0.7 (2026-04-16) — 促销券 + DSFinV-K 促销导出 + 账单预览 VAT 明细表","v1.0.6 (2026-04-13) — 关键 RouteAuthorizationPlugin 绕过修复 + Kassenbuch 单据替换 + 调拨历史时间线 + UI 重构至 ui/screens/","v1.0.5 (2026-04-10) — 见 CHANGELOG.md 历史"]'::jsonb,
    '["Drain (or verify empty) the KASSENBUCH TSE signing queue before deploying. Queue entries replay their stored process_data verbatim, so any entry queued before this release is signed with the old positive Bar tender — permanently and invisibly. SELECT COUNT(*) FROM tse_signing_queue WHERE entity_type = ''KASSENBUCH'' AND signing_status IN (''QUEUED'',''PENDING'',''PROCESSING'',''FAILED'') must return 0. Full detail in docs/dsfinvk_tse_release_audit.md Part 0.","Human review of the KABS-2 Stammdaten backfill required. V95 backfills per-closing shop master data from the live row, guarded by the absence of a logged shop-info edit. Confirm the audit trail was active across the whole backfilled period before trusting that absence."]'::jsonb,
    '["Die KASSENBUCH TSE-Signing-Queue muss vor dem Deployment geleert oder als leer verifiziert werden. Queue-Einträge replayen ihr gespeichertes process_data wörtlich, daher wird jeder vor diesem Release gequeuete Eintrag dauerhaft und unsichtbar mit dem alten positiven Bar-Tender signiert. SELECT COUNT(*) FROM tse_signing_queue WHERE entity_type = ''KASSENBUCH'' AND signing_status IN (''QUEUED'',''PENDING'',''PROCESSING'',''FAILED'') muss 0 zurückgeben. Vollständige Details in docs/dsfinvk_tse_release_audit.md Teil 0.","Menschliche Überprüfung des KABS-2 Stammdaten-Backfills erforderlich. V95 backfilled Per-Closing-Shop-Masterdaten aus der Live-Zeile, bewacht durch das Fehlen einer protokollierten Shop-Info-Bearbeitung. Bestätigen Sie, dass die Audit-Trails über die gesamte backfilled Periode aktiv war, bevor Sie dieses Fehlen vertrauen."]'::jsonb,
    '["部署前必须排空（或验证为空）KASSENBUCH TSE 签名队列。队列条目会逐字回放其存储的 process_data，因此本版本之前入队的任何条目都将使用旧的正面 Bar tender 永久且不可见地被签名。SELECT COUNT(*) FROM tse_signing_queue WHERE entity_type = ''KASSENBUCH'' AND signing_status IN (''QUEUED'',''PENDING'',''PROCESSING'',''FAILED'') 必须返回 0。完整细节见 docs/dsfinvk_tse_release_audit.md Part 0。","需要人工核对 KABS-2 基础数据回填。V95 从实际行回填每个结账的门店基础数据，以无 shop-info 编辑日志为守卫。在信任该缺失之前，请确认回填整个期间内审计轨迹确实处于激活状态。"]'::jsonb
);
