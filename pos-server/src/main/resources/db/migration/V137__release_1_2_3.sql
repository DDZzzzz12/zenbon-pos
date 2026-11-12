-- Release-marker migration for v1.2.3.
--
-- `changelog_release` (created in V79) is the single source of truth for the user-facing
-- release notes (CLAUDE.md rule 13). Write-only by design. Highlights are a curated subset:
-- operator-visible changes only, grouped under `## ` section headings (see V90 and
-- `ChangelogSection` in `shared`). Scope: the blank browser till/admin page after an update, and
-- fiscal work silently lost on PostgreSQL. The Android client is unchanged (versionCode stays 17).
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
    '1.2.3',
    '2026-11-11',

    'Browser till opens again after an update; fiscal work no longer lost silently',
    'Browser-Kasse startet nach einem Update wieder; Fiskalarbeit geht nicht mehr unbemerkt verloren',
    '更新后浏览器收银端恢复正常；财税操作不再被静默丢失',

    'After the update to 1.2.2 the browser till at /pos/ and the web admin could stay blank, because the browser kept an old copy of the app. On PostgreSQL, a failed database statement in a few fiscal paths could roll back the whole step while the server reported success. Both are fixed.',
    'Nach dem Update auf 1.2.2 konnten die Browser-Kasse unter /pos/ und das Web-Admin-Panel leer bleiben, weil der Browser eine alte Kopie der App behielt. Auf PostgreSQL konnte in einigen Fiskalabläufen eine fehlgeschlagene Datenbankanweisung den ganzen Schritt zurückrollen, während der Server Erfolg meldete. Beides ist behoben.',
    '更新到 1.2.2 后，/pos/ 的浏览器收银端和网页管理端可能一片空白，因为浏览器保留了旧版应用。在 PostgreSQL 上，部分财税流程中一条失败的数据库语句可能导致整个步骤回滚，而服务器却报告成功。两个问题均已修复。',

    '[
"## Fixed",
"Browser till (/pos/) and web admin (/admin/) stay blank after a server update. The server now lets the browser revalidate its cached app files by content only, so an updated app is loaded instead of a stale copy that points to files that no longer exist.",
"Z-Bon closing on PostgreSQL. A failure while creating the Z-Bon PDF or recalculating favourites could answer with a Z-number while nothing was stored. Both steps now run after the closing has been saved, so a failure there no longer undoes the closing.",
"Cancelling a queued TSE signature. If the audit entry for the cancellation cannot be written, the cancellation now fails and the entry stays queued instead of being abandoned without a record.",
"Late TSE signing of pending bills. A failure on one bill no longer rolls back the other bills of the same run."
]'::jsonb,
    '[
"## Korrigiert",
"Browser-Kasse (/pos/) und Web-Admin (/admin/) bleiben nach einem Server-Update leer. Der Server lässt den Browser seine zwischengespeicherten App-Dateien jetzt nur noch anhand des Inhalts prüfen, sodass die aktualisierte App geladen wird statt einer veralteten Kopie, die auf nicht mehr vorhandene Dateien verweist.",
"Z-Bon-Abschluss auf PostgreSQL. Ein Fehler beim Erstellen des Z-Bon-PDFs oder beim Neuberechnen der Favoriten konnte mit einer Z-Nummer beantwortet werden, obwohl nichts gespeichert war. Beide Schritte laufen jetzt nach dem Speichern des Abschlusses, ein Fehler dort macht den Abschluss also nicht mehr rückgängig.",
"Stornieren einer wartenden TSE-Signatur. Kann der Audit-Eintrag zur Stornierung nicht geschrieben werden, schlägt die Stornierung jetzt fehl und der Eintrag bleibt in der Warteschlange, statt ohne Nachweis aufgegeben zu werden.",
"Nachträgliche TSE-Signierung offener Belege. Ein Fehler bei einem Beleg setzt die anderen Belege desselben Durchlaufs nicht mehr zurück."
]'::jsonb,
    '[
"## 已修复",
"服务器更新后，浏览器收银端（/pos/）和网页管理端（/admin/）一片空白。服务器现在只按内容让浏览器校验缓存的应用文件，因此会加载更新后的应用，而不是指向已不存在文件的旧副本。",
"PostgreSQL 上的 Z-Bon 日结。生成 Z-Bon PDF 或重新计算常用商品时出错，可能返回了 Z 编号但什么都没有保存。这两步现在在日结保存之后执行，因此这里的失败不再撤销日结。",
"取消排队中的 TSE 签名。如果无法写入取消的审计记录，取消现在会失败，条目保留在队列中，而不是无记录地被放弃。",
"待处理账单的 TSE 延迟签名。某一张账单出错不再回滚同一轮中的其他账单。"
]'::jsonb,
    '[
"A browser that still shows a blank page after the server update needs one hard reload (Ctrl+Shift+R) or cleared site data for that address; from 1.2.3 on this is no longer necessary.",
"Tax auditors: on a PostgreSQL installation running 1.2.2 or earlier, TSE signatures can exist without a Kassenbuch entry behind them. See the change log in the TSE documentation, version 1.2.3."
]'::jsonb,
    '[
"Ein Browser, der nach dem Server-Update weiter eine leere Seite zeigt, braucht einmal ein hartes Neuladen (Strg+Umschalt+R) oder gelöschte Websitedaten für diese Adresse; ab 1.2.3 ist das nicht mehr nötig.",
"Für Betriebsprüfer: Auf einer PostgreSQL-Installation mit 1.2.2 oder älter können TSE-Signaturen ohne dazugehörigen Kassenbucheintrag existieren. Siehe die Änderungsübersicht in der TSE-Dokumentation, Version 1.2.3."
]'::jsonb,
    '[
"在服务器更新后仍显示空白页的浏览器，需要强制刷新一次（Ctrl+Shift+R）或清除该地址的网站数据；从 1.2.3 起不再需要。",
"致税务稽查人员：在运行 1.2.2 或更早版本的 PostgreSQL 安装上，可能存在没有对应 Kassenbuch 条目的 TSE 签名。请参阅 TSE 文档中 1.2.3 版本的变更记录。"
]'::jsonb
);
