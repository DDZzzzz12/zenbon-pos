-- Release-marker migration for v1.1.1.
--
-- `changelog_release` (created in V79) is the single source of truth for the user-facing
-- release notes (CLAUDE.md rule 13). Write-only by design. Highlights are a curated subset:
-- operator-visible changes only, grouped under `## ` section headings (see V90 and
-- `ChangelogSection` in `shared`). Scope was taken from the TREE diff against v1.1.0.
--
-- release_date MUST match the date of the git tag on `master`; correct it before tagging if the
-- tag slips. Validate an edit twice: an SQL-quoting walk ('' is one escaped quote) AND a JSON parse
-- of every array with equal en/de/zh item counts. This file must stay the LAST migration; a fresh
-- install seeds it through SchemaManager.seedReleaseNotes. After the tag, late findings go in the
-- NEXT marker, never here.

INSERT INTO changelog_release (
    release_version,
    release_date,
    title_en, title_de, title_zh,
    summary_en, summary_de, summary_zh,
    highlights_en, highlights_de, highlights_zh,
    known_issues_en, known_issues_de, known_issues_zh
) VALUES (
    '1.1.1',
    '2026-10-10',

    'Closing the day from the admin panel, Android self-update and server-log search',
    'Tagesabschluss aus dem Admin-Panel, Android-Selbstupdate und Server-Log-Suche',
    '从网页后台日结、安卓自更新与服务器日志搜索',

    'This release lets you close the day from the admin panel through a designated signing till, stops signing the Z-Bon aggregate (it restates sales each bill already signed), lets Android tills update themselves from your server, adds an opt-in maintainer account that searches the server logs by traceId, and adds an Ubuntu POS-appliance setup. Read the known issues before closing from the admin panel on a shop with several tills.',
    'Diese Version erlaubt den Tagesabschluss aus dem Admin-Panel über eine bestimmte signierende Kasse, signiert das Z-Bon-Aggregat nicht mehr (es wiederholt Umsätze, die jeder Beleg bereits signiert hat), lässt Android-Kassen sich vom eigenen Server aktualisieren, bringt ein optionales Wartungskonto zur Suche in den Server-Logs per traceId und eine Einrichtung als POS-Gerät unter Ubuntu. Bei mehreren Kassen bitte vor dem Abschluss im Admin-Panel die bekannten Einschränkungen lesen.',
    '本版本支持通过指定的签名收银台从网页后台日结，不再对 Z-Bon 汇总进行 TSE 签名（它只是重述每张小票已签名的销售额），让安卓收银台可从自己的服务器更新，新增可选的维护账号以按 traceId 搜索服务器日志，并新增 Ubuntu 收银设备一键配置。多收银台的门店在后台日结前，请先阅读已知事项。',

    '[
"## New and updated: closing the day",
"Close the day from the admin panel. Z-Bon erstellen is now on the dashboard and in the Z-Bon list of the admin panel, not only at the till. The dialog names the till that will sign and says why when none can, and the summary after closing also shows the Kassenbestand. New route GET /api/z-bon/closing-readiness reports the signer and whether the closing may proceed (permission ZBON_CLOSE).",
"Signing till on the Ladenkasse. A browser has no TSE client of its own, so the Ladenkasse can name the till whose TSE client signs a closing''s cash movements (Kassendifferenz, tip payouts in cash, top-up, daily Geldtransit). Set it under Kassenbuch, Kassen verwalten, Ladenkasse, Signierende Kasse; it needs the permission TERMINAL_MANAGE, the server checks the same, and every change is audited (SET_CASH_BOOK_SIGNING_TERMINAL). Migration V91 adds cash_book.signing_terminal_id; nothing is designated after the upgrade, so tills keep closing exactly as before. The DSFinV-K export shows the designated till as TERMINAL_ID of those bookings.",
"The Z-Bon aggregate is no longer signed in the TSE. It restates sales that each bill already signed, so it is stored as NOT_APPLICABLE without a TSE transaction (AEAO zu § 146a Nr. 1.11.2). The cash movements of a closing are still signed.",
"With the TSE disabled nothing is signed, so a closing and a manual Kassenbuch entry need no signing till and work from the admin panel too. A designated till that has been deactivated is still refused.",
"Bank or payroll tip payouts no longer need a signing till. When no till can sign, the closing dialog now offers them instead of hiding the whole tip section; they book no Kassenbuch entry. Count and top-up stay hidden in that case.",
"## New and updated: everything else",
"Android tills update themselves from your server. When the server installs a release, it also stores the Android app (checked against the published SHA-256 sums first) and serves it to the tills; no internet access or token is needed at the till. The login screen shows App-Update verfügbar, and Nach Updates suchen checks on demand. The till downloads and re-verifies the file, then hands it to Android, which asks for its own confirmation.",
"Maintainer account and server-log search (off by default). With MAINTAINER_USERNAME and MAINTAINER_PASSWORD_HASH set, a maintainer who is no employee can sign in at /maintenance/ and search the rejected, error, client-error and application logs by the traceId shown in an error dialog, without SSH. Create the hash with java -jar pos-server.jar --hash-maintainer-password. The login is rate-limited and the page shows scrubbed messages without employee or device identifiers.",
"Every rejected request is logged. Each 4xx answer is written to a new rejected.log (30 days, 200 MB) with traceId, method, path and reason, so the traceId of a refusal now leads somewhere. It stays out of error.log, which is for server faults.",
"Ubuntu POS-appliance setup. On a standard Ubuntu desktop, install-service.sh can optionally turn the machine into a dedicated POS device (provision-node.sh): boot to a Chromium kiosk for the till or admin app, TSE and backup sticks mounted by label, firewall, no suspend, clean power-off on a flat battery. A kiosk System tab shows battery, server and network state with restart and shut-down buttons and warns on low battery; closing the lid closes the kiosk screen while the server keeps running; the kiosk uses the system keyboard layout. --check is a read-only preflight and --undo reverts every change.",
"## Fixed",
"File dialogs opened nothing in the kiosk. Upload buttons and Ctrl+O silently did nothing because the file portal refused the browser; the kiosk now starts it without ambient capabilities and with a GTK portal.",
"Pulling the TSE or backup stick no longer hangs the server. A stick removed without unmounting left a dead mount; the server hung validating the TSE through it and restarted in a loop with a black kiosk screen. The mount now goes away with the device.",
"install-service.sh failures on a fresh Ubuntu desktop. systemd refused to start the unit because /media/pos did not exist, and every start was judged failed because curl was missing for the health probe.",
"Clearer messages and labels. An unregistered device that tries to sign is told it is not a registered POS terminal; the signer errors name the till and the reason; the footer of Letzte Kassenbuch-Einträge on the dashboard is labelled as the cash-book balance, not a sum; closing-dialog amounts use the shop currency; German and English quotes no longer render with a gap in the admin panel."
]'::jsonb,
    '[
"## Neu und geändert: Tagesabschluss",
"Tagesabschluss aus dem Admin-Panel. Z-Bon erstellen gibt es jetzt auf dem Dashboard und in der Z-Bon-Liste des Admin-Panels, nicht nur an der Kasse. Der Dialog nennt die Kasse, die signiert, und erklärt den Grund, wenn keine signieren kann; die Zusammenfassung nach dem Abschluss zeigt auch den Kassenbestand. Die neue Route GET /api/z-bon/closing-readiness meldet den Signierer und ob der Abschluss möglich ist (Berechtigung ZBON_CLOSE).",
"Signierende Kasse der Ladenkasse. Ein Browser hat keinen eigenen TSE-Client; deshalb kann die Ladenkasse die Kasse bestimmen, deren TSE-Client die Bargeldbewegungen eines Abschlusses signiert (Kassendifferenz, Trinkgeld-Auszahlung bar, Aufstockung, täglicher Geldtransit). Einstellen unter Kassenbuch, Kassen verwalten, Ladenkasse, Signierende Kasse; dafür ist die Berechtigung TERMINAL_MANAGE nötig, der Server prüft dasselbe, und jede Änderung steht im Audit-Protokoll (SET_CASH_BOOK_SIGNING_TERMINAL). Migration V91 ergänzt cash_book.signing_terminal_id; nach dem Update ist nichts bestimmt, die Kassen schließen also genau wie bisher ab. Der DSFinV-K-Export zeigt die bestimmte Kasse als TERMINAL_ID dieser Buchungen.",
"Das Z-Bon-Aggregat wird nicht mehr in die TSE signiert. Es wiederholt Umsätze, die jeder Beleg bereits signiert hat, und wird deshalb als NOT_APPLICABLE ohne TSE-Transaktion gespeichert (AEAO zu § 146a Nr. 1.11.2). Die Bargeldbewegungen eines Abschlusses werden weiterhin signiert.",
"Bei deaktivierter TSE wird nichts signiert, deshalb brauchen Abschluss und manueller Kassenbucheintrag keine signierende Kasse und funktionieren auch aus dem Admin-Panel. Eine bestimmte, aber deaktivierte Kasse wird weiterhin abgelehnt.",
"Trinkgeld-Auszahlung per Bank oder Lohn braucht keine signierende Kasse mehr. Kann keine Kasse signieren, bietet der Abschlussdialog sie jetzt an, statt den ganzen Trinkgeld-Bereich auszublenden; sie bucht keinen Kassenbucheintrag. Zählung und Aufstockung bleiben dann ausgeblendet.",
"## Neu und geändert: alles Weitere",
"Android-Kassen aktualisieren sich über Ihren Server. Installiert der Server eine Version, legt er auch die Android-App ab (vorher gegen die veröffentlichten SHA-256-Summen geprüft) und liefert sie an die Kassen aus; an der Kasse ist weder Internet noch Token nötig. Der Anmeldebildschirm zeigt App-Update verfügbar, Nach Updates suchen prüft auf Wunsch. Die Kasse lädt die Datei, prüft sie erneut und übergibt sie an Android, das selbst um Bestätigung bittet.",
"Wartungskonto und Server-Log-Suche (standardmäßig aus). Sind MAINTAINER_USERNAME und MAINTAINER_PASSWORD_HASH gesetzt, kann sich ein Wartungskonto, das kein Mitarbeiter ist, unter /maintenance/ anmelden und die Logs rejected, error, client-error und application anhand der traceId aus einem Fehlerdialog durchsuchen, ohne SSH. Den Hash erzeugt java -jar pos-server.jar --hash-maintainer-password. Die Anmeldung ist gedrosselt, die Seite zeigt bereinigte Meldungen ohne Mitarbeiter- oder Geräte-Kennungen.",
"Jede abgewiesene Anfrage wird protokolliert. Jede 4xx-Antwort wird mit traceId, Methode, Pfad und Grund in die neue rejected.log geschrieben (30 Tage, 200 MB); die traceId einer Abweisung führt damit zu einem Eintrag. Die error.log bleibt Serverfehlern vorbehalten.",
"Einrichtung als POS-Gerät unter Ubuntu. Auf einem normalen Ubuntu-Desktop kann install-service.sh den Rechner optional zu einem reinen POS-Gerät machen (provision-node.sh): Start in einen Chromium-Kiosk für Kasse oder Admin-App, TSE- und Backup-Sticks per Label eingehängt, Firewall, kein Suspend, sauberes Herunterfahren bei leerem Akku. Ein Kiosk-Reiter System zeigt Akku-, Server- und Netzwerkstatus mit Neustart und Herunterfahren und warnt bei niedrigem Akkustand; Deckel zu schließt den Kiosk-Bildschirm, der Server läuft weiter; der Kiosk nutzt das Tastaturlayout des Systems. --check ist eine reine Vorprüfung, --undo macht jede Änderung rückgängig.",
"## Behoben",
"Dateidialoge öffneten im Kiosk nichts. Upload-Schaltflächen und Strg+O taten still nichts, weil das Datei-Portal den Browser abwies; der Kiosk startet ihn jetzt ohne Ambient-Capabilities und mit GTK-Portal.",
"Das Abziehen des TSE- oder Backup-Sticks hängt den Server nicht mehr auf. Ein ohne Aushängen entfernter Stick hinterließ einen toten Mount; der Server hing bei der TSE-Prüfung darüber und startete in Schleife neu, der Kiosk blieb schwarz. Der Mount verschwindet jetzt mit dem Gerät.",
"Fehler von install-service.sh auf einem frischen Ubuntu-Desktop. systemd verweigerte den Start, weil /media/pos nicht existierte, und jeder Start galt als fehlgeschlagen, weil curl für die Health-Prüfung fehlte.",
"Klarere Meldungen und Beschriftungen. Ein nicht registriertes Gerät, das zu signieren versucht, erfährt, dass es kein registriertes Kassenterminal ist; die Signierer-Fehler nennen Kasse und Grund; die Fußzeile von Letzte Kassenbuch-Einträge auf dem Dashboard ist als Kassenbuch-Saldo beschriftet, nicht als Summe; Beträge im Abschlussdialog nutzen die Währung des Geschäfts; deutsche und englische Anführungszeichen erscheinen im Admin-Panel nicht mehr mit Lücke."
]'::jsonb,
    '[
"## 新增与更新：日结",
"可从网页后台日结。创建 Z-Bon 现在位于后台仪表盘和 Z-Bon 列表中，不再仅限收银台。对话框会显示将进行签名的收银台，无法签名时说明原因；日结完成后的摘要也显示现金余额。新增接口 GET /api/z-bon/closing-readiness 返回签名方以及是否可以日结（权限 ZBON_CLOSE）。",
"Ladenkasse 的签名收银台。浏览器没有自己的 TSE 客户端，因此 Ladenkasse 可指定一台收银台，由其 TSE 客户端对日结的现金变动（现金差额、现金小费支付、补充备用金、每日 Geldtransit）签名。设置路径：Kassenbuch → Kassen verwalten → Ladenkasse → Signierende Kasse；需要权限 TERMINAL_MANAGE，服务器同样校验，每次更改均记入审计日志（SET_CASH_BOOK_SIGNING_TERMINAL）。迁移 V91 新增 cash_book.signing_terminal_id；升级后默认未指定，各收银台的日结方式与之前完全一致。DSFinV-K 导出会把指定的收银台作为这些记账的 TERMINAL_ID。",
"Z-Bon 汇总不再进行 TSE 签名。它只是重述每张小票已签名的销售额，因此以 NOT_APPLICABLE 保存、不产生 TSE 交易（AEAO zu § 146a Nr. 1.11.2）。日结中的现金变动仍会签名。",
"TSE 停用时不签名任何内容，因此日结和手工现金账条目无需签名收银台，也可在网页后台完成。已被停用的指定收银台仍会被拒绝。",
"银行或工资发放的小费支付不再需要签名收银台。当没有收银台可以签名时，日结对话框现在会提供这种方式，而不是隐藏整个小费区域；它不产生现金账条目。此时清点和补充备用金仍保持隐藏。",
"## 新增与更新：其他",
"安卓收银台通过您的服务器自行更新。服务器安装新版本时，也会保存安卓应用（先对照已发布的 SHA-256 校验和验证）并提供给收银台；收银台无需联网或令牌。登录界面显示“App-Update verfügbar”，也可点击“Nach Updates suchen”主动检查。收银台下载并再次校验文件后交给安卓，由安卓自行请求确认安装。",
"维护账号与服务器日志搜索（默认关闭）。设置 MAINTAINER_USERNAME 和 MAINTAINER_PASSWORD_HASH 后，非员工的维护人员可登录 /maintenance/，用错误对话框中显示的 traceId 搜索 rejected、error、client-error 和 application 日志，无需 SSH。哈希用 java -jar pos-server.jar --hash-maintainer-password 生成。登录有频率限制，页面显示的是经过清理、不含员工或设备标识的消息。",
"每个被拒绝的请求都会记录。每个 4xx 响应都会连同 traceId、方法、路径和原因写入新的 rejected.log（保留 30 天，200 MB），因此拒绝请求的 traceId 现在能查到记录。它不会写入用于服务器故障的 error.log。",
"Ubuntu 收银设备配置。在标准 Ubuntu 桌面上，install-service.sh 可选择把机器配置成专用收银设备（provision-node.sh）：启动进入 Chromium 全屏模式的收银台或后台应用，TSE 和备份 U 盘按标签挂载，防火墙，禁止休眠，电量耗尽时安全关机。全屏模式新增“System”标签页，显示电池、服务器和网络状态，提供重启与关机按钮，并在电量低时提醒；合上笔记本盖会关闭全屏界面而服务器继续运行；全屏模式使用系统键盘布局。--check 为只读预检，--undo 可撤销所有更改。",
"## 已修复",
"全屏模式下文件对话框打不开。上传按钮和 Ctrl+O 因文件门户拒绝浏览器而毫无反应；全屏模式现在以清空 ambient capabilities 的方式启动浏览器，并使用 GTK 门户。",
"拔出 TSE 或备份 U 盘不再导致服务器卡死。未卸载就拔出的 U 盘会留下失效挂载点；服务器通过它验证 TSE 时卡住并不断重启，全屏界面一直黑屏。现在挂载点会随设备消失而移除。",
"全新 Ubuntu 桌面上 install-service.sh 的故障。因 /media/pos 不存在，systemd 拒绝启动服务；又因缺少 curl 无法执行健康检查，每次启动都被判定失败。",
"更清晰的提示与标签。未注册设备尝试签名时，会提示其不是已注册的收银终端；签名错误信息会指出收银台和原因；仪表盘“Letzte Kassenbuch-Einträge”底部标注为现金账余额而非合计；日结对话框的金额使用门店币种；后台中德语和英语引号不再显示多余空隙。"
]'::jsonb,

    '[
"## Closing the day",
"With several tills, designate the signing till before closing from the admin panel: Kassenbuch, Kassen verwalten, Ladenkasse, Signierende Kasse, then Save (it takes effect immediately and independently of the rest of the form). Without one, the admin panel cannot sign, so a closing with a count, a cash tip payout, a top-up or the daily Geldtransit is refused; only the simple closing and bank or payroll tip payouts work. A shop with one till can leave it unset only if it closes at the till.",
"The Z-Bon aggregate is no longer TSE-signed, so the TSE log holds one transaction less per closing. Closings already signed stay as they are; a WORM entry cannot be rewritten. The Kassendifferenz, cash tip payouts, top-up and Geldtransit of a closing are still signed.",
"If the designated till is later deactivated or its TSE client signed out, a closing with a count, cash payout, top-up or Geldtransit is refused instead of silently signing under another till. Designate another till or reactivate it. A till is eligible only if it belongs to the shop, is active, fiscal, registered with a TSE client in normal operation, and has the role CASHIER; order stations cannot be chosen.",
"Resetting the signing till to none makes the calling till sign again, which is how every install behaved before this release; from the admin panel only the simple closing is then possible.",
"With the TSE disabled nothing is signed and no signing till is needed. Orders, order items, bills and invoice-clearing bills still require a registered, active till.",
"## Upgrade and everything else",
"Migration V91 adds one nullable column, cash_book.signing_terminal_id, and writes no data. Mind that a DSFinV-K package exported after designating a till names that till as TERMINAL_ID for the closing movements, so it differs from one exported before.",
"After restoring a backup made with 1.0.x into this version, restart the server once. Until that restart the legacy invoices are missing from document search and the DATEV export, the supplier links are missing and the four default scheduled jobs are absent; the restart fills them in. Updating an existing installation in place is not affected.",
"Android tills still on 1.1.0 have no self-update: install the 1.1.1 app once by hand. After that a till is offered an update only when the app itself changed (the internal versionCode), so a server-only release prompts nothing and the till may show an older version name than the server. The server has an APK to distribute only after it installed a release through the update page (nightly check or Herunterladen); a server updated by hand has none. Android asks for confirmation itself and, the first time, for permission to install updates.",
"The maintainer account is off unless both MAINTAINER_USERNAME and MAINTAINER_PASSWORD_HASH are set; a value that is not a BCrypt hash, or an invalid MAINTAINER_SESSION_MINUTES, stops the server from starting. The password needs at least 12 characters and at most 72 bytes. Use it only over HTTPS: without it the password crosses the network in clear. Scrubbing of log messages is best effort, and anyone with SSH access still reads the raw files.",
"rejected.log keeps the reason of every rejected request for 30 days together with the employee and device ID of the caller. These IDs are never shown on the maintenance page, but the file holds more personal data than application.log (7 days); reflect that in your data-protection documentation.",
"Not yet verified on hardware: the stick unmounting on a real pull, and the kiosk System tab power buttons and lid switch. The Ubuntu appliance setup is interactive and only offered on a standard Ubuntu desktop install; run install-service.sh --check first."
]'::jsonb,
    '[
"## Tagesabschluss",
"Bei mehreren Kassen die signierende Kasse bestimmen, bevor im Admin-Panel abgeschlossen wird: Kassenbuch, Kassen verwalten, Ladenkasse, Signierende Kasse, dann Speichern (wirkt sofort und unabhängig vom übrigen Formular). Ohne sie kann das Admin-Panel nicht signieren; ein Abschluss mit Zählung, Trinkgeld-Auszahlung bar, Aufstockung oder täglichem Geldtransit wird abgelehnt, nur der einfache Abschluss und Trinkgeld-Auszahlung per Bank oder Lohn gehen. Ein Geschäft mit einer Kasse darf die Einstellung nur leer lassen, wenn es an der Kasse abschließt.",
"Das Z-Bon-Aggregat wird nicht mehr in die TSE signiert; das TSE-Protokoll enthält also eine Transaktion weniger je Abschluss. Bereits signierte Abschlüsse bleiben unverändert, ein WORM-Eintrag lässt sich nicht umschreiben. Kassendifferenz, Trinkgeld-Auszahlung bar, Aufstockung und Geldtransit eines Abschlusses werden weiterhin signiert.",
"Wird die bestimmte Kasse später deaktiviert oder ihr TSE-Client abgemeldet, lehnt ein Abschluss mit Zählung, Auszahlung bar, Aufstockung oder Geldtransit ab, statt still unter einer anderen Kasse zu signieren. Dann eine andere Kasse bestimmen oder sie reaktivieren. Geeignet ist eine Kasse nur, wenn sie zum Geschäft gehört, aktiv und fiskalisch ist, mit einem TSE-Client im Normalbetrieb angemeldet ist und die Rolle CASHIER hat; Bestellstationen stehen nicht zur Wahl.",
"Wird die signierende Kasse auf keine zurückgesetzt, signiert wieder die auslösende Kasse, so wie jede Installation vor dieser Version; aus dem Admin-Panel geht dann nur der einfache Abschluss.",
"Bei deaktivierter TSE wird nichts signiert und keine signierende Kasse gebraucht. Bestellungen, Bestellpositionen, Belege und Belege zum Ausgleich von Rechnungen verlangen weiterhin eine registrierte, aktive Kasse.",
"## Update und alles Weitere",
"Migration V91 ergänzt eine Spalte cash_book.signing_terminal_id (nullable) und schreibt keine Daten. Ein DSFinV-K-Paket, das nach dem Bestimmen einer Kasse exportiert wird, nennt sie als TERMINAL_ID der Abschlussbuchungen und weicht daher von einem früheren Export ab.",
"Nach dem Wiederherstellen einer Datensicherung aus 1.0.x in diese Version den Server einmal neu starten. Bis dahin fehlen die Alt-Rechnungen in der Belegsuche und im DATEV-Export, die Lieferanten-Verknüpfungen und die vier Standard-Zeitpläne; der Neustart ergänzt sie. Ein Update der bestehenden Installation ist nicht betroffen.",
"Android-Kassen mit 1.1.0 können sich noch nicht selbst aktualisieren: Die App 1.1.1 einmal von Hand installieren. Danach wird nur ein Update angeboten, wenn sich die App selbst geändert hat (interne versionCode); eine reine Server-Version löst nichts aus, und die Kasse kann eine ältere Versionsbezeichnung zeigen als der Server. Der Server hat nur dann eine APK zum Verteilen, wenn er eine Version über die Update-Seite installiert hat (nächtliche Prüfung oder Herunterladen); ein von Hand aktualisierter Server hat keine. Android fragt selbst nach Bestätigung und beim ersten Mal nach der Erlaubnis, Updates zu installieren.",
"Das Wartungskonto ist aus, solange nicht MAINTAINER_USERNAME und MAINTAINER_PASSWORD_HASH beide gesetzt sind; ein Wert, der kein BCrypt-Hash ist, oder ein ungültiges MAINTAINER_SESSION_MINUTES verhindert den Serverstart. Das Passwort braucht mindestens 12 Zeichen und höchstens 72 Byte. Nur über HTTPS nutzen: sonst läuft das Passwort unverschlüsselt durchs Netz. Die Bereinigung der Log-Meldungen ist Best-Effort, und wer SSH-Zugriff hat, liest weiterhin die Rohdateien.",
"rejected.log speichert 30 Tage lang den Grund jeder abgewiesenen Anfrage zusammen mit Mitarbeiter-ID und Geräte-ID des Aufrufers. Diese Kennungen erscheinen nie auf der Wartungsseite, die Datei enthält aber mehr personenbezogene Daten als application.log (7 Tage); bitte in der Datenschutzdokumentation berücksichtigen.",
"Noch nicht auf Hardware geprüft: das Aushängen des Sticks beim echten Abziehen sowie die Strom-Schaltflächen des Kiosk-Reiters System und der Deckelkontakt. Die Ubuntu-Geräteeinrichtung ist interaktiv und wird nur bei einer normalen Ubuntu-Desktop-Installation angeboten; vorher install-service.sh --check ausführen."
]'::jsonb,
    '[
"## 日结",
"多收银台的门店在后台日结前，请先指定签名收银台：Kassenbuch → Kassen verwalten → Ladenkasse → Signierende Kasse，然后保存（立即生效，独立于表单其余部分）。未指定时后台无法签名，带清点、现金小费支付、补充备用金或每日 Geldtransit 的日结会被拒绝；只有简单日结以及银行或工资发放的小费可用。只有一台收银台的门店，仅当在收银台上日结时才可留空。",
"Z-Bon 汇总不再进行 TSE 签名，因此每次日结在 TSE 日志中少一笔交易。已签名的日结保持不变，WORM 条目无法改写。日结的现金差额、现金小费支付、补充备用金和 Geldtransit 仍会签名。",
"若指定的收银台之后被停用或其 TSE 客户端已登出，带清点、现金支付、补充备用金或 Geldtransit 的日结会被拒绝，而不会悄悄改用其他收银台签名。请指定其他收银台或重新启用。收银台必须属于本店、处于启用且为财税模式、已用正常模式的 TSE 客户端注册，并且角色为 CASHIER；点单站不可选。",
"把签名收银台重置为“无”后，仍由触发日结的收银台签名，与本版本之前所有安装的行为一致；此时从后台只能做简单日结。",
"TSE 停用时不签名任何内容，也无需签名收银台。订单、订单项、小票和用于冲销发票的小票仍要求已注册且启用的收银台。",
"## 升级与其他",
"迁移 V91 新增一个可为空的列 cash_book.signing_terminal_id，不写入任何数据。指定收银台之后导出的 DSFinV-K 包会把该收银台作为日结记账的 TERMINAL_ID，因此与此前导出的包不同。",
"将 1.0.x 版本的备份恢复到本版本后，请重启一次服务器。重启前，旧发票不会出现在单据搜索和 DATEV 导出中，供应商关联和四个默认计划任务也会缺失；重启后会自动补齐。直接在原有安装上更新不受影响。",
"仍在 1.1.0 的安卓收银台尚无自更新功能：请手动安装一次 1.1.1 应用。此后只有应用本身变化（内部 versionCode）才会提示更新，仅服务器更新的版本不会提示，收银台显示的版本名可能比服务器旧。服务器只有在通过更新页面安装版本（夜间检查或点击“Herunterladen”）后才有安卓安装包可分发；手动更新的服务器没有。安卓会自行请求确认，首次还需允许安装更新。",
"仅当 MAINTAINER_USERNAME 和 MAINTAINER_PASSWORD_HASH 都已设置时维护账号才启用；不是 BCrypt 哈希的值或无效的 MAINTAINER_SESSION_MINUTES 会导致服务器无法启动。密码至少 12 个字符，最多 72 字节。请仅通过 HTTPS 使用，否则密码会在网络中明文传输。日志消息的清理只是尽力而为，能通过 SSH 访问服务器的人仍可读取原始文件。",
"rejected.log 会将每个被拒绝请求的原因连同调用者的员工 ID 和设备 ID 保存 30 天。这些 ID 不会显示在维护页面上，但该文件包含的个人数据多于 application.log（7 天）；请在数据保护文档中予以考虑。",
"尚未在真实硬件上验证：实际拔出 U 盘时的卸载，以及全屏模式 System 标签页的电源按钮和合盖开关。Ubuntu 设备配置为交互式，仅在标准 Ubuntu 桌面安装上提供；请先运行 install-service.sh --check。"
]'::jsonb
);
