-- Release-marker migration for v1.2.4.
--
-- `changelog_release` (created in V79) is the single source of truth for the user-facing
-- release notes (CLAUDE.md rule 13). Write-only by design. Highlights are a curated subset:
-- operator-visible changes only, grouped under `## ` section headings (see V90 and
-- `ChangelogSection`). Scope: three till/admin usability fixes. The Android client changed
-- (versionCode 18).
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
    '1.2.4',
    '2026-11-14',

    'Re-pair a till from the offline screen; real server address in the pairing dialog',
    'Kasse über den Offline-Bildschirm neu koppeln; echte Serveradresse im Kopplungsdialog',
    '可在离线界面重新配对收银端；配对对话框显示真实服务器地址',

    'A paired till whose server moved had no way to enter a new address. The pairing dialog in the web admin showed localhost instead of the server address, and the document viewer on Android sat under the status bar. All three are fixed.',
    'Eine gekoppelte Kasse, deren Server umgezogen war, konnte keine neue Adresse eingeben. Der Kopplungsdialog im Web-Admin zeigte localhost statt der Serveradresse, und der Dokumentbetrachter unter Android lag unter der Statusleiste. Alle drei Punkte sind behoben.',
    '服务器地址变更后，已配对的收银端无法输入新地址；网页管理端的配对对话框显示 localhost 而不是服务器地址；Android 上的文档查看器被状态栏遮挡。三个问题均已修复。',

    '[
"## Fixed",
"Server not reachable screen. Below Retry there is now a Re-pair device button that leads to the pairing screen, where the server address can be changed.",
"Pairing dialog in the web admin. When the admin is opened via localhost, the QR code and address now use the server''s network address instead, so a till can actually reach it. The address stays editable.",
"Document viewer on Android. The toolbar no longer sits under the status bar and its buttons can be tapped."
]'::jsonb,
    '[
"## Korrigiert",
"Bildschirm Server nicht erreichbar. Unter Wiederholen gibt es jetzt die Schaltfläche Gerät neu koppeln, die zum Kopplungsbildschirm führt, wo die Serveradresse geändert werden kann.",
"Kopplungsdialog im Web-Admin. Wird das Admin-Panel über localhost geöffnet, verwenden QR-Code und Adresse jetzt die Netzwerkadresse des Servers, damit eine Kasse ihn tatsächlich erreicht. Die Adresse bleibt änderbar.",
"Dokumentbetrachter unter Android. Die Werkzeugleiste liegt nicht mehr unter der Statusleiste, ihre Schaltflächen sind wieder bedienbar."
]'::jsonb,
    '[
"## 已修复",
"“服务器无法连接”界面。重试按钮下方新增“重新配对设备”按钮，可进入配对界面并修改服务器地址。",
"网页管理端的配对对话框。通过 localhost 打开管理端时，二维码和地址现在使用服务器的网络地址，收银端才能真正连接。地址仍可编辑。",
"Android 上的文档查看器。工具栏不再位于状态栏下方，按钮可以点击。"
]'::jsonb,
    '[
"Re-pairing from the offline screen signs the device out of its current terminal; a new pairing code from the web admin is required.",
"The suggested https address in the pairing dialog depends on the server certificate; if a till rejects it, edit the address to the http form."
]'::jsonb,
    '[
"Neues Koppeln über den Offline-Bildschirm meldet das Gerät vom bisherigen Terminal ab; ein neuer Kopplungscode aus dem Web-Admin ist nötig.",
"Die im Kopplungsdialog vorgeschlagene https-Adresse hängt vom Serverzertifikat ab; lehnt eine Kasse sie ab, die Adresse auf die http-Form ändern."
]'::jsonb,
    '[
"从离线界面重新配对会使设备退出当前终端；需要在网页管理端生成新的配对码。",
"配对对话框建议的 https 地址取决于服务器证书；如果收银端拒绝，请改用 http 形式的地址。"
]'::jsonb
);
