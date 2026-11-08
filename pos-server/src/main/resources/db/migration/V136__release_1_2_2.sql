-- Release-marker migration for v1.2.2.
--
-- `changelog_release` (created in V79) is the single source of truth for the user-facing
-- release notes (CLAUDE.md rule 13). Write-only by design. Highlights are a curated subset:
-- operator-visible changes only, grouped under `## ` section headings (see V90 and
-- `ChangelogSection` in `shared`). Scope: the fix found on the first v1.2.1 tablet update
-- (the app-update download was rejected with a checksum error).
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
    '1.2.2',
    '2026-11-08',

    'App update on the till works again',
    'App-Update an der Kasse funktioniert wieder',
    '收银端应用更新恢复正常',

    'Tills and order stations could not install the Android app update: the download was rejected with a checksum error and the installed app stayed unchanged. The server no longer compresses the app file, and the app no longer asks for a compressed download.',
    'Kassen und Bestellstationen konnten das Android-App-Update nicht installieren: Der Download wurde mit einem Prüfsummenfehler verworfen und die installierte App blieb unverändert. Der Server komprimiert die App-Datei nicht mehr, und die App fordert keinen komprimierten Download mehr an.',
    '收银端和点单站无法安装 Android 应用更新：下载因校验和错误被丢弃，已安装的应用保持不变。服务器不再压缩应用文件，应用也不再请求压缩下载。',

    '[
"## Fixed",
"App update on the till and the order station. The message Die heruntergeladene Datei stimmte nicht mit ihrer Prüfsumme überein no longer appears. The server sent the app file gzip-compressed and the app compared the compressed bytes with the published SHA-256. The server now sends the app file as it is, and the app asks for an uncompressed download."
]'::jsonb,
    '[
"## Korrigiert",
"App-Update an Kasse und Bestellstation. Die Meldung Die heruntergeladene Datei stimmte nicht mit ihrer Prüfsumme überein erscheint nicht mehr. Der Server lieferte die App-Datei gzip-komprimiert aus, und die App verglich die komprimierten Bytes mit der veröffentlichten SHA-256. Der Server liefert die App-Datei jetzt unverändert aus, und die App fordert einen unkomprimierten Download an."
]'::jsonb,
    '[
"## 已修复",
"收银端和点单站的应用更新。不再出现“下载的文件与其校验和不符”的提示。此前服务器以 gzip 压缩方式发送应用文件，应用却把压缩后的字节与公布的 SHA-256 比较。现在服务器原样发送应用文件，应用也请求未压缩的下载。"
]'::jsonb,
    '[
"Tills that already show the checksum message need no new app first: once the server runs 1.2.2, tap Erneut versuchen (retry) and the update installs."
]'::jsonb,
    '[
"Kassen, die die Prüfsummenmeldung schon zeigen, brauchen vorab keine neue App: Sobald der Server 1.2.2 ausführt, auf Erneut versuchen tippen, dann wird das Update installiert."
]'::jsonb,
    '[
"已显示校验和提示的收银端无需先装新应用：服务器运行 1.2.2 后，点击“重试”即可安装更新。"
]'::jsonb
);
