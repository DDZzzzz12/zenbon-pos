-- Release-marker migration for v1.2.1.
--
-- `changelog_release` (created in V79) is the single source of truth for the user-facing
-- release notes (CLAUDE.md rule 13). Write-only by design. Highlights are a curated subset:
-- operator-visible changes only, grouped under `## ` section headings (see V90 and
-- `ChangelogSection` in `shared`). Scope was taken from the commits dated after the v1.2.0
-- release commit (c08b49b2b); the restructuring into area modules (platform, ecr, fiscal,
-- accounting, backoffice) is internal and deliberately not listed.
--
-- release_date MUST match the date of the git tag on `master`; correct it before tagging if the
-- tag slips. Validate an edit twice: an SQL-quoting walk ('' is one escaped quote) AND a JSON parse
-- of every array with equal en/de/zh item counts. A fresh install seeds it through
-- SchemaManager.seedReleaseNotes. After the tag, late findings go in the NEXT marker, never here.

INSERT INTO changelog_release (
    release_version,
    release_date,
    title_en, title_de, title_zh,
    summary_en, summary_de, summary_zh,
    highlights_en, highlights_de, highlights_zh,
    known_issues_en, known_issues_de, known_issues_zh
) VALUES (
    '1.2.1',
    '2026-11-07',

    'Standard Linux paths, a second backup copy and fixes from the release QA',
    'Standardpfade unter Linux, eine zweite Sicherungskopie und Korrekturen aus der Release-Prüfung',
    'Linux 标准路径、备份第二份副本，以及发布测试中的修复',

    'New server installs on Linux keep their data in the standard system folders, backups can be copied to a second target, the till warns when a manager sign-in runs out, and the admin panel loads faster. Fixes cover promotions on the till, the update reload, table transfers by a manager, the Kassenbuch form, the DSFinV-K pre-check and the TSE factory reset.',
    'Neue Server-Installationen unter Linux legen ihre Daten in den üblichen Systemordnern ab, Sicherungen können auf ein zweites Ziel kopiert werden, die Kasse warnt, wenn eine Leiter-Anmeldung abläuft, und das Admin-Panel lädt schneller. Korrekturen betreffen Aktionen an der Kasse, das Neuladen nach einem Update, Tischumbuchungen durch eine Leitung, das Kassenbuch-Formular, die DSFinV-K-Vorprüfung und das Zurücksetzen der TSE.',
    '在 Linux 上新安装的服务器把数据放在标准系统文件夹中；备份可以复制到第二个目标；经理登录到期时收银端会提示；后台面板加载更快。修复涉及收银端的优惠、更新后的重新加载、经理转台、现金账簿表单、DSFinV-K 预检以及 TSE 出厂重置。',

    '[
"## New and updated: server on Linux",
"Standard Linux paths. A fresh install with install-service.sh uses /var/lib/zenbon for data, /var/log/zenbon for logs and /etc/zenbon for settings. An existing install in a home folder is detected on every run and keeps its unit unchanged. install-service.sh --migrate-data-dir moves an old install to the standard folders, checks the space first, verifies the copy, runs a health check and rolls back when that fails.",
"Backup second copy. Settings, Backup can name a copy target such as a USB drive or a network mount, with its own retention count. The copy is written as a temporary file and renamed, and only when the target is really mounted. A failed copy shows as a partial failure with its message in the status line; the local backup still counts as done. install-service.sh --backup-device prepares a USB drive and never formats it.",
"## New and updated: till and admin panel",
"The till and the order station tell the operator when a manager sign-in ends by idle time-out or on the server. Before, the lent controls just disappeared; only the hard time limit warned. Signing out by hand stays silent.",
"The Z-Bon closing dialog links to the setting for the signing till.",
"The TSE details of a receipt show the process start and end twice: in local time and in UTC. The UTC values are the ones printed on the receipt. Before, only the local time was shown, which differed from the printed UTC time by the time-zone offset.",
"The admin panel and the browser till load faster: files are revalidated with a short answer instead of a full download, and are delivered compressed. The address in the browser shows a readable name, for example document-detail/42. Older addresses still work.",
"## Fixed",
"Till: a percentage promotion on the whole bill picked before an item promotion no longer makes the till ask for more than the server bills, so the payment no longer ends with PAYMENT_TOTAL_MISMATCH. An open table order or billing screen now stops the automatic reload after an update. The status bar, location tabs and table cards keep their shape at large font sizes, occupied tables in the floor plan show the employee colour, and a voucher amount typed with a German comma enables Hinzufügen.",
"Orders: an order whose owner is in training mode on a table session that is not (or the other way round) is refused with 409 instead of a server error. When a manager is lent to a till or station, a table transfer onto a free table is owned by whoever asked, not by the manager. A whole-table move under a manager sign-in is audited with that sign-in. The partial-bill notification names the owner of the session, not the person who billed.",
"Back office: the entry form no longer asks for a USt-Satz on Kassenbuch types without VAT reference, for example Geldtransit.",
"DSFinV-K: the check before an export no longer reports the Z-Bon summary row as a missing signature.",
"TSE and backup: the factory reset of a development TSE no longer asks for the Admin PIN, so a TSE with a lost PIN can be reset. The TSE status shows uninitialised right after a reset. Backups and TSE exports started in the same second no longer overwrite each other. Changing the backup copy target clears the old target error."
]'::jsonb,
    '[
"## Neu und geändert: Server unter Linux",
"Standardpfade unter Linux. Eine Neuinstallation mit install-service.sh nutzt /var/lib/zenbon für Daten, /var/log/zenbon für Protokolle und /etc/zenbon für Einstellungen. Eine vorhandene Installation im Home-Ordner wird bei jedem Lauf erkannt und behält ihre Unit unverändert. install-service.sh --migrate-data-dir verschiebt eine alte Installation in die Standardordner, prüft vorher den Platz, verifiziert die Kopie, führt eine Funktionsprüfung durch und macht bei einem Fehler alles rückgängig.",
"Zweite Sicherungskopie. Unter Einstellungen, Sicherung kann ein Kopierziel wie ein USB-Laufwerk oder ein Netzlaufwerk mit eigener Aufbewahrungsanzahl angegeben werden. Die Kopie wird als temporäre Datei geschrieben und umbenannt, und nur wenn das Ziel wirklich eingehängt ist. Eine fehlgeschlagene Kopie erscheint als Teilfehler mit der Meldung in der Statuszeile; die lokale Sicherung gilt weiterhin als erledigt. install-service.sh --backup-device bereitet ein USB-Laufwerk vor und formatiert es nie.",
"## Neu und geändert: Kasse und Admin-Panel",
"Kasse und Bestellstation melden, wenn eine Leiter-Anmeldung durch Inaktivität oder auf dem Server endet. Bisher verschwanden die geliehenen Bedienelemente kommentarlos; nur die harte Zeitgrenze warnte. Das manuelle Abmelden bleibt still.",
"Der Z-Bon-Abschlussdialog verlinkt auf die Einstellung für die signierende Kasse.",
"Die TSE-Daten eines Belegs zeigen Prozessbeginn und -ende doppelt: in Ortszeit und in UTC. Die UTC-Werte sind die, die auf dem Beleg gedruckt werden. Bisher stand dort nur die Ortszeit, die vom gedruckten UTC-Wert um den Zeitzonenunterschied abwich.",
"Admin-Panel und Browser-Kasse laden schneller: Dateien werden mit einer kurzen Antwort neu geprüft statt vollständig geladen und komprimiert ausgeliefert. Die Adresse im Browser zeigt einen lesbaren Namen, zum Beispiel document-detail/42. Ältere Adressen funktionieren weiter.",
"## Korrigiert",
"Kasse: Eine prozentuale Aktion auf den ganzen Beleg, die vor einer Artikelaktion gewählt wurde, lässt die Kasse nicht mehr mehr verlangen, als der Server abrechnet; die Zahlung endet nicht mehr mit PAYMENT_TOTAL_MISMATCH. Eine offene Tischbestellung oder Abrechnung verhindert jetzt das automatische Neuladen nach einem Update. Statusleiste, Bereichs-Tabs und Tischkarten behalten bei großen Schriftgrößen ihre Form, belegte Tische im Grundriss zeigen die Mitarbeiterfarbe, und ein Gutscheinbetrag mit deutschem Komma aktiviert Hinzufügen.",
"Bestellungen: Eine Bestellung, deren Inhaber im Schulungsmodus ist, auf einer Tischsitzung ohne Schulungsmodus (oder umgekehrt), wird mit 409 abgelehnt statt mit einem Serverfehler. Wird einer Kasse oder Station eine Leitung geliehen, gehört ein Tisch, auf den umgebucht wird, dem Anfragenden und nicht der Leitung. Eine Umbuchung des ganzen Tisches unter einer Leiter-Anmeldung wird mit dieser Anmeldung protokolliert. Die Meldung zur Teilabrechnung nennt den Inhaber der Sitzung, nicht den Abrechnenden.",
"Backoffice: Das Buchungsformular fragt bei Kassenbuch-Arten ohne USt-Bezug, zum Beispiel Geldtransit, nicht mehr nach einem USt-Satz.",
"DSFinV-K: Die Prüfung vor einem Export meldet die Z-Bon-Summenzeile nicht mehr als fehlende Signatur.",
"TSE und Sicherung: Das Zurücksetzen einer Entwicklungs-TSE verlangt die Admin-PIN nicht mehr, sodass eine TSE mit verlorener PIN zurückgesetzt werden kann. Der TSE-Status zeigt direkt nach dem Zurücksetzen nicht initialisiert an. Sicherungen und TSE-Exporte, die in derselben Sekunde starten, überschreiben sich nicht mehr. Beim Ändern des Kopierziels wird der Fehler des alten Ziels gelöscht."
]'::jsonb,
    '[
"## 新增与更新：Linux 服务器",
"Linux 标准路径。用 install-service.sh 全新安装时，数据放在 /var/lib/zenbon，日志放在 /var/log/zenbon，设置放在 /etc/zenbon。已有的主目录安装每次运行都会被识别，其 unit 保持不变。install-service.sh --migrate-data-dir 会把旧安装移到标准文件夹：先检查空间，核对副本，做健康检查，失败则回滚。",
"备份第二份副本。在“设置、备份”中可指定复制目标，例如 U 盘或网络挂载，并设置各自的保留数量。副本先写成临时文件再改名，且仅在目标确实已挂载时才写入。复制失败会显示为部分失败，并在状态行给出信息；本地备份仍算完成。install-service.sh --backup-device 会准备 U 盘，但绝不格式化。",
"## 新增与更新：收银端和后台面板",
"经理登录因空闲超时或在服务器上结束时，收银端和点单站会提示操作员。以前借出的控件会无声消失，只有硬性时限才会警告。手动退出仍不提示。",
"Z-Bon 结账对话框链接到签名收银端的设置。",
"小票的 TSE 数据现在把处理开始和结束时间显示两次：本地时间和 UTC。UTC 值与小票上打印的相同。以前只显示本地时间，与打印的 UTC 时间相差一个时区偏移。",
"后台面板和浏览器收银端加载更快：文件改为用简短应答重新校验而不是完整下载，并以压缩形式提供。浏览器地址显示可读的名称，例如 document-detail/42，旧地址仍然有效。",
"## 已修复",
"收银端：先选了整单百分比优惠、再选单品优惠时，收银端不再要求比服务器结算更多的金额，付款不再以 PAYMENT_TOTAL_MISMATCH 结束。打开的桌台点单或结账界面现在会阻止更新后的自动重新加载。大字号下状态栏、区域标签和桌台卡片保持完整，平面图中有人的桌台显示员工颜色，用德语逗号输入的代金券金额可以启用“添加”。",
"订单：所有者处于培训模式而桌台会话不是（或相反）的订单会被以 409 拒绝，而不是服务器错误。向收银端或点单站借出经理时，转到空闲桌台的新会话归请求者所有，而不是经理。经理登录下的整桌转台会记入该次登录。部分结账的通知写的是会话所有者，而不是结账的人。",
"后台：对没有增值税关联的现金账簿类型（例如 Geldtransit），录入表单不再要求填写 USt-Satz。",
"DSFinV-K：导出前的检查不再把 Z-Bon 汇总行报告为缺少签名。",
"TSE 与备份：开发用 TSE 的出厂重置不再要求管理员 PIN，因此丢失 PIN 的 TSE 也可以重置。重置后 TSE 状态立即显示为未初始化。同一秒内启动的备份和 TSE 导出不再互相覆盖。更改备份复制目标时会清除旧目标的错误。"
]'::jsonb,

    '[
"The standard folders apply to new installs only. An existing install keeps its folders until you run install-service.sh --migrate-data-dir on purpose. Close the day and make a backup before you do.",
"The backup copy is skipped, and reported as a failure, while its target is not mounted; a folder on the root disk is never accepted as a target. Tills get a new app build with this version."
]'::jsonb,
    '[
"Die Standardordner gelten nur für Neuinstallationen. Eine vorhandene Installation behält ihre Ordner, bis Sie install-service.sh --migrate-data-dir bewusst ausführen. Schließen Sie vorher den Tag ab und erstellen Sie eine Sicherung.",
"Die Sicherungskopie wird übersprungen und als Fehler gemeldet, solange das Ziel nicht eingehängt ist; ein Ordner auf der Systemplatte wird nie als Ziel akzeptiert. Die Kassen erhalten mit dieser Version einen neuen App-Stand."
]'::jsonb,
    '[
"标准文件夹仅适用于新安装。已有安装保持原来的文件夹，直到您有意运行 install-service.sh --migrate-data-dir。运行前请先完成当日结账并做一次备份。",
"复制目标未挂载时，备份副本会被跳过并报告为失败；系统盘上的文件夹绝不会被接受为目标。本版本会给收银端提供新的应用版本。"
]'::jsonb
);
