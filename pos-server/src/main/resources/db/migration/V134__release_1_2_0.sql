-- Release-marker migration for v1.2.0.
--
-- `changelog_release` (created in V79) is the single source of truth for the user-facing
-- release notes (CLAUDE.md rule 13). Write-only by design. Highlights are a curated subset:
-- operator-visible changes only, grouped under `## ` section headings (see V90 and
-- `ChangelogSection` in `shared`). Scope was taken from the TREE diff against v1.1.2: the
-- release/https-caddy and release/session-security branches carry commits dated before
-- v1.1.2 but were not part of it.
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
    '1.2.0',
    '2026-10-25',

    'HTTPS-only access, pairing by check code, safer sessions and an admin panel that works on phones',
    'Zugriff nur über HTTPS, Koppeln per Prüfcode, sicherere Sitzungen und ein Admin-Panel für Handys',
    '仅限 HTTPS 访问、校验码配对、更安全的会话，以及可在手机上使用的后台面板',

    'The server is reachable only over HTTPS through a bundled Caddy with a local certificate authority, tills pair with a check code instead of installing a certificate, sessions and device secrets are stored more safely, updates roll back quickly when a build cannot start, and the admin panel works on phones. Existing installations must re-run the installer, see the known issues.',
    'Der Server ist nur noch über HTTPS erreichbar, über ein mitgeliefertes Caddy mit lokaler Zertifizierungsstelle; Kassen koppeln per Prüfcode, statt ein Zertifikat zu installieren; Sitzungen und Geräte-Geheimnisse werden sicherer gespeichert; Updates werden schnell zurückgesetzt, wenn ein Build nicht startet; und das Admin-Panel funktioniert auf Handys. Bestehende Installationen müssen das Installationsskript erneut ausführen, siehe die bekannten Einschränkungen.',
    '服务器现在只能通过随附的 Caddy（带本地证书颁发机构）以 HTTPS 访问；收银端用校验码配对，无需安装证书；会话和设备密钥的保存更安全；构建无法启动时更新会快速回滚；后台面板可在手机上使用。现有安装必须重新运行安装脚本，详见已知问题。',

    '[
"## New and updated: secure access over HTTPS",
"HTTPS through Caddy. The server now listens on 127.0.0.1 only. The installer sets up a Caddy service on ports 443 and 80 with a local certificate authority, so no certificate has to be bought and nothing else listens on the LAN. Plain HTTP on the LAN is closed: tills and browsers must use an https address. A public certificate is not needed.",
"Pairing with a check code. The pairing QR code carries a check code (Zertifikat-Prüfcode), a fingerprint of the server root certificate. A till that scans the QR code, or has the code typed in, trusts the server certificate without anything being installed on the device. A till that is already paired can enter the code on its offline screen without pairing again. An unpaired till behind an untrusted certificate now goes to the pairing screen instead of Server nicht erreichbar.",
"Browsers get the root certificate from http://server-address/ca (a page with the fingerprint) or /ca.crt (the file). Everything else on port 80 redirects to HTTPS. The installer warns, but never refuses, when the system clock is not synchronised or is earlier than the build date.",
"Safer sessions. The web admin session lives in an HttpOnly cookie and the admin panel is served with a Content-Security-Policy. Tills seal the device token, the session tokens and the remember-me password on the device (Android Keystore, or the desktop keychain). Android backup and device transfer are switched off. Everyone signs in again after the update.",
"## New and updated: updates",
"The update screen shows the server download progress, also when the download was started on another device. After a failed update that was rolled back, the screen says so instead of showing installing.",
"Applying an update no longer hangs the server. A jar that cannot start is rolled back in about 20 seconds, and a version that was staged earlier is no longer installed by mistake when a newer one is staged.",
"The update check no longer waits for a quiet period without requests. Open business since the last Kassenabschluss now counts only fiscal books, so an unclosed Tresor no longer blocks an update. Open bills, open table sessions, unfinished TSE work, running jobs and disk space still decide.",
"## New and updated: the till",
"Cancel item, cancel table, transfer table and transfer items now ask for a reason, chosen from standard chips, up to 500 characters. The server rejects a missing reason on all four. The reason of a transfer is stored with it.",
"With round-robin on, the dashboard top bar shows the next employee who gets an opened table. If the pick shifted while the order was entered, a toast says so. Takeaway is skipped.",
"## New and updated: admin panel",
"The admin panel works on phones and narrow windows: compact filters on every list, and responsive Dashboard, Tische, Grundriss, Terminals, Produkte and Belegfluss. It asks before you leave unsaved changes, and dialogs, drawers and confirmations follow one pattern.",
"Belege: a shared PDF viewer replaces the old overlay. The upload dialog asks for the document type and can scan with the camera into a PDF. Kassen verwalten also lists deactivated books.",
"Staff appear by their HR name in statistics and tips, only for users who may read employees; everyone else sees the user name. Payment method names follow the interface language.",
"Permissions are tighter. Buttons that write follow the permission the action needs, on the screen and on the server. Sign-in is refused for a user without a permission group. Cashiers no longer hold DOCUMENT_READ and VOUCHER_REPORT. The virtual locations Gutschein and Schnellverkauf and the VOUCHER table cannot be edited or deleted.",
"## Fixed",
"A percentage promotion on the whole bill is rounded to the cent. Before, a sub-cent deduction made such a bill impossible to pay. See the TSE documentation, Änderungen je Version, for what an auditor sees. Kassensturz difference entries on non-fiscal books keep Kassendifferenz Plus or Minus in the text when the book name is long.",
"Z-Bon with an overlapping period answers 409 instead of 500 and uses no Z-number. A product CSV row without a valid product type is an error for that row instead of becoming FOOD. An employee can no longer change their own pay through the personal-data route.",
"The original of a cancelled incoming invoice no longer stays in Zu prüfen for ever. Cancelled invoices from before the update are cleared when the server starts.",
"The floor plan follows the pointer when zoomed in. An unreachable server no longer holds the till on the spinner for minutes: the start check is cut off after 15 seconds."
]'::jsonb,
    '[
"## Neu und geändert: sicherer Zugriff über HTTPS",
"HTTPS über Caddy. Der Server lauscht jetzt nur noch auf 127.0.0.1. Das Installationsskript richtet einen Caddy-Dienst auf den Ports 443 und 80 mit einer lokalen Zertifizierungsstelle ein, es muss also kein Zertifikat gekauft werden und nichts anderes lauscht im LAN. Klartext-HTTP im LAN ist geschlossen: Kassen und Browser müssen eine https-Adresse verwenden. Ein öffentliches Zertifikat ist nicht nötig.",
"Koppeln mit Prüfcode. Der Kopplungs-QR-Code enthält einen Prüfcode (Zertifikat-Prüfcode), den Fingerabdruck des Wurzelzertifikats des Servers. Eine Kasse, die den QR-Code scannt oder bei der der Code eingetippt wird, vertraut dem Serverzertifikat, ohne dass auf dem Gerät etwas installiert wird. Eine bereits gekoppelte Kasse kann den Code auf ihrem Offline-Bildschirm eingeben, ohne neu zu koppeln. Eine ungekoppelte Kasse hinter einem nicht vertrauten Zertifikat landet jetzt im Kopplungsbildschirm statt bei Server nicht erreichbar.",
"Browser laden das Wurzelzertifikat unter http://Server-Adresse/ca (Seite mit dem Fingerabdruck) oder /ca.crt (die Datei). Alles andere auf Port 80 leitet auf HTTPS um. Das Installationsskript warnt, verweigert aber nie, wenn die Systemuhr nicht synchronisiert ist oder vor dem Build-Datum liegt.",
"Sicherere Sitzungen. Die Web-Admin-Sitzung liegt in einem HttpOnly-Cookie und das Admin-Panel wird mit einer Content-Security-Policy ausgeliefert. Kassen verschlüsseln Geräte-Token, Sitzungs-Token und das Passwort für Angemeldet bleiben auf dem Gerät (Android Keystore bzw. Schlüsselbund des Desktops). Android-Backup und Gerätewechsel sind abgeschaltet. Nach dem Update melden sich alle neu an.",
"## Neu und geändert: Updates",
"Der Update-Bildschirm zeigt den Download-Fortschritt auf dem Server, auch wenn der Download auf einem anderen Gerät gestartet wurde. Nach einem fehlgeschlagenen Update, das zurückgesetzt wurde, sagt der Bildschirm das, statt Installation läuft anzuzeigen.",
"Das Einspielen eines Updates lässt den Server nicht mehr hängen. Ein Jar, das nicht startet, wird in etwa 20 Sekunden zurückgesetzt, und eine früher bereitgestellte Version wird nicht mehr versehentlich installiert, wenn eine neuere bereitgestellt wird.",
"Die Update-Prüfung wartet nicht mehr auf eine ruhige Phase ohne Anfragen. Offener Betrieb seit dem letzten Kassenabschluss zählt nur noch für fiskalische Kassen, ein nicht abgeschlossener Tresor blockiert ein Update also nicht mehr. Offene Rechnungen, offene Tischsitzungen, unfertige TSE-Vorgänge, laufende Jobs und Speicherplatz entscheiden weiterhin.",
"## Neu und geändert: die Kasse",
"Artikel stornieren, Tisch stornieren, Tisch umbuchen und Artikel umbuchen verlangen jetzt einen Grund, gewählt aus Standard-Chips, bis 500 Zeichen. Der Server lehnt bei allen vieren einen fehlenden Grund ab. Der Grund einer Umbuchung wird mit ihr gespeichert.",
"Bei aktivem Round-Robin zeigt die Kopfleiste des Dashboards den nächsten Mitarbeiter, der einen geöffneten Tisch bekommt. Hat sich die Wahl während der Bestellung verschoben, weist ein Hinweis darauf hin. Außer-Haus wird übersprungen.",
"## Neu und geändert: Admin-Panel",
"Das Admin-Panel funktioniert auf Handys und schmalen Fenstern: kompakte Filter in jeder Liste sowie responsive Dashboard, Tische, Grundriss, Terminals, Produkte und Belegfluss. Es fragt nach, bevor Sie ungespeicherte Änderungen verlassen, und Dialoge, Seitenleisten und Bestätigungen folgen einem Muster.",
"Belege: Ein gemeinsamer PDF-Viewer ersetzt das alte Overlay. Der Upload-Dialog fragt nach dem Belegtyp und kann mit der Kamera in ein PDF scannen. Kassen verwalten listet auch deaktivierte Kassen.",
"Mitarbeiter erscheinen in Statistiken und Trinkgeld mit ihrem Personalnamen, nur für Benutzer, die Mitarbeiter lesen dürfen; alle anderen sehen den Benutzernamen. Namen der Zahlarten folgen der Oberflächensprache.",
"Die Berechtigungen sind strenger. Schreibende Schaltflächen folgen der Berechtigung, die die Aktion braucht, auf dem Bildschirm und auf dem Server. Die Anmeldung wird für Benutzer ohne Berechtigungsgruppe abgelehnt. Kassierer haben DOCUMENT_READ und VOUCHER_REPORT nicht mehr. Die virtuellen Standorte Gutschein und Schnellverkauf sowie der Tisch VOUCHER lassen sich nicht bearbeiten oder löschen.",
"## Behoben",
"Eine prozentuale Aktion auf den ganzen Beleg wird auf den Cent gerundet. Vorher machte ein Abzug unter einem Cent einen solchen Beleg unbezahlbar. Was ein Prüfer sieht, steht in der TSE-Dokumentation unter Änderungen je Version. Kassensturz-Differenzbuchungen auf nicht fiskalischen Kassen behalten Kassendifferenz Plus oder Minus im Text, auch wenn der Kassenname lang ist.",
"Ein Z-Bon mit überlappendem Zeitraum antwortet mit 409 statt 500 und verbraucht keine Z-Nummer. Eine Artikel-CSV-Zeile ohne gültigen Artikeltyp ist ein Fehler dieser Zeile statt zu FOOD zu werden. Ein Mitarbeiter kann sein eigenes Gehalt nicht mehr über die Route für persönliche Daten ändern.",
"Das Original einer stornierten Eingangsrechnung bleibt nicht mehr für immer in Zu prüfen. Stornierte Rechnungen aus der Zeit vor dem Update werden beim Serverstart bereinigt.",
"Der Grundriss folgt beim Hineinzoomen dem Mauszeiger. Ein nicht erreichbarer Server hält die Kasse nicht mehr minutenlang auf dem Ladekreis: Die Startprüfung wird nach 15 Sekunden abgebrochen."
]'::jsonb,
    '[
"## 新增与更新：通过 HTTPS 安全访问",
"通过 Caddy 提供 HTTPS。服务器现在只监听 127.0.0.1。安装脚本会设置一个监听 443 和 80 端口的 Caddy 服务，并使用本地证书颁发机构，因此无需购买证书，局域网上也没有其他服务在监听。局域网上的明文 HTTP 已关闭：收银端和浏览器必须使用 https 地址。不需要公共证书。",
"用校验码配对。配对二维码包含一个校验码（Zertifikat-Prüfcode），即服务器根证书的指纹。扫描二维码或手动输入该码的收银端会信任服务器证书，无需在设备上安装任何东西。已经配对的收银端可以在离线界面输入该码，无需重新配对。证书不受信任、尚未配对的收银端现在会进入配对界面，而不是显示“服务器无法访问”。",
"浏览器可从 http://服务器地址/ca（带指纹的页面）或 /ca.crt（文件）获取根证书。80 端口上的其他请求都会重定向到 HTTPS。系统时钟未同步或早于构建日期时，安装脚本只会警告，从不拒绝。",
"更安全的会话。网页后台会话保存在 HttpOnly Cookie 中，后台面板带有内容安全策略（Content-Security-Policy）。收银端会把设备令牌、会话令牌和“保持登录”的密码加密保存在设备上（Android Keystore，或桌面系统的密钥环）。Android 备份和设备迁移已关闭。更新后所有人需要重新登录。",
"## 新增与更新：更新",
"更新界面会显示服务器端的下载进度，即使下载是在另一台设备上启动的。更新失败并已回滚后，界面会如实说明，而不是一直显示“正在安装”。",
"应用更新不再让服务器卡住。无法启动的 jar 会在约 20 秒内回滚；暂存了更新的版本后，不会再误装先前暂存的旧版本。",
"更新检查不再等待“没有请求的安静时段”。自上次现金结账以来的未结营业现在只统计税控（fiscal）账簿，因此未结的 Tresor 不会再阻止更新。未结账单、未结桌台会话、未完成的 TSE 操作、运行中的任务和磁盘空间仍然起决定作用。",
"## 新增与更新：收银端",
"取消商品、取消桌台、转台和转移商品现在都需要填写原因，可从标准选项中选择，最多 500 个字符。服务器对这四项都会拒绝缺少原因的请求。转移的原因会随转移一起保存。",
"开启轮流分配（round-robin）时，仪表盘顶栏会显示下一位将获得新开桌台的员工。如果在录单期间选择发生了变化，会弹出提示。外带单会被跳过。",
"## 新增与更新：后台面板",
"后台面板现在可在手机和窄窗口上使用：每个列表都有紧凑筛选，Dashboard、Tische、Grundriss、Terminals、Produkte 和 Belegfluss 为自适应布局。离开未保存的更改前会先询问，对话框、侧栏和确认窗口遵循统一样式。",
"单据：共用的 PDF 查看器取代了旧的浮层。上传对话框会询问单据类型，并可用相机扫描成 PDF。“管理现金账簿”也会列出已停用的账簿。",
"统计和小费中的员工显示人事姓名，仅对有权读取员工信息的用户可见；其他人看到用户名。支付方式名称跟随界面语言。",
"权限更严格。写入类按钮在界面和服务器上都遵循该操作所需的权限。没有权限组的用户无法登录。收银员不再拥有 DOCUMENT_READ 和 VOUCHER_REPORT。虚拟位置 Gutschein 和 Schnellverkauf 以及 VOUCHER 桌台不能被编辑或删除。",
"## 已修复",
"针对整张账单的百分比优惠现在精确到分。以前不足一分的扣减会使这样的账单无法支付。审计人员会看到什么，请见 TSE 文档中的“Änderungen je Version”。非税控账簿上的现金盘点差额记录，在账簿名称很长时仍会在文字中保留 Kassendifferenz Plus 或 Minus。",
"时间段重叠的 Z-Bon 现在返回 409 而不是 500，并且不会占用 Z 编号。商品 CSV 中没有有效商品类型的行，现在只是该行出错，而不会变成 FOOD。员工不能再通过个人资料接口修改自己的薪酬。",
"已取消的进项发票的原件不再永远停留在“待审核”。更新前已取消的发票会在服务器启动时清理。",
"放大后平面图会跟随指针。服务器无法访问时，收银端不再让转圈持续数分钟：启动检查在 15 秒后中止。"
]'::jsonb,

    '[
"Upgrading from v1.1.2: an in-app update only replaces the server jar. The server then keeps its old settings and logs a warning that it serves plain HTTP. To get HTTPS, download the full server package, close the day, and run sudo ./install-service.sh from the unpacked folder (pass the port chosen in the wizard as the second argument, answer y to Start now). Ports 80 and 443 cannot be the server port.",
"Order matters: run the installer first, then update the tills. Until the installer has run, tills on the new app cannot reach the server; after it, tills not yet updated cannot reach it either. Give each till the new https address (the IP without a port, or a local name).",
"A till that is already paired enters the check code from Terminals, Kopplungscode erzeugen, on its offline screen. Otherwise the root certificate must be installed on the device. Browsers need the root certificate from /ca; compare the fingerprint with the installer output.",
"The system clock must be right before the first start: Caddy dates its certificates and its 10-year root from it, and the installer only warns. Android should use the IP or a local DNS name; the name .local may not resolve with Private DNS.",
"Everyone is signed out on the first start after the update, the admin panel needs HTTPS to sign in, and a restored Android phone must be paired again because backup is off.",
"A percentage promotion on a bill is rounded to the cent from the first start of this version on each installation; older data is not changed. The shared PDF viewer has not yet been checked on the print preview, the browser till and an Android tablet."
]'::jsonb,
    '[
"Update von v1.1.2: Ein In-App-Update ersetzt nur das Server-Jar. Der Server behält dann seine alten Einstellungen und protokolliert eine Warnung, dass er Klartext-HTTP ausliefert. Für HTTPS laden Sie das vollständige Server-Paket herunter, schließen den Tag ab und führen sudo ./install-service.sh aus dem entpackten Ordner aus (den im Assistenten gewählten Port als zweites Argument angeben, bei Start now mit y antworten). Die Ports 80 und 443 können nicht der Server-Port sein.",
"Die Reihenfolge zählt: erst das Installationsskript, dann die Kassen aktualisieren. Solange das Skript nicht gelaufen ist, erreichen Kassen mit der neuen App den Server nicht; danach erreichen ihn noch nicht aktualisierte Kassen nicht. Geben Sie jeder Kasse die neue https-Adresse (die IP ohne Port oder einen lokalen Namen).",
"Eine bereits gekoppelte Kasse gibt auf ihrem Offline-Bildschirm den Prüfcode aus Terminals, Kopplungscode erzeugen, ein. Sonst muss das Wurzelzertifikat auf dem Gerät installiert werden. Browser brauchen das Wurzelzertifikat von /ca; vergleichen Sie den Fingerabdruck mit der Ausgabe des Installationsskripts.",
"Die Systemuhr muss vor dem ersten Start stimmen: Caddy datiert seine Zertifikate und sein 10 Jahre gültiges Wurzelzertifikat danach, und das Installationsskript warnt nur. Android sollte die IP oder einen lokalen DNS-Namen verwenden; der Name .local wird mit privatem DNS unter Umständen nicht aufgelöst.",
"Beim ersten Start nach dem Update sind alle abgemeldet, das Admin-Panel braucht HTTPS zur Anmeldung, und ein wiederhergestelltes Android-Handy muss neu gekoppelt werden, weil das Backup abgeschaltet ist.",
"Eine prozentuale Aktion auf einen Beleg wird ab dem ersten Start dieser Version auf der jeweiligen Installation auf den Cent gerundet; ältere Daten bleiben unverändert. Der gemeinsame PDF-Viewer wurde noch nicht in der Druckvorschau, an der Browser-Kasse und auf einem Android-Tablet geprüft."
]'::jsonb,
    '[
"从 v1.1.2 升级：应用内更新只会替换服务器 jar。服务器随后保留旧设置，并记录一条提供明文 HTTP 的警告。要启用 HTTPS，请下载完整的服务器安装包，先完成当日结账，再在解压后的文件夹中运行 sudo ./install-service.sh（把向导中选择的端口作为第二个参数，在“Start now”处回答 y）。80 和 443 不能作为服务器端口。",
"顺序很重要：先运行安装脚本，再更新收银端。脚本运行之前，使用新版应用的收银端无法连接服务器；运行之后，尚未更新的收银端也无法连接。请给每台收银端新的 https 地址（不带端口的 IP，或本地名称）。",
"已经配对的收银端在离线界面输入“终端 → 生成配对码”中的校验码即可；否则必须在设备上安装根证书。浏览器需要从 /ca 获取根证书，请把指纹与安装脚本的输出核对。",
"首次启动前系统时钟必须正确：Caddy 会据此给证书和有效期 10 年的根证书记日期，而安装脚本只会警告。Android 应使用 IP 或本地 DNS 名称；开启私人 DNS 时，.local 名称可能无法解析。",
"更新后首次启动时所有人都会被登出，后台面板需要 HTTPS 才能登录，由于备份已关闭，恢复出来的 Android 手机必须重新配对。",
"整张账单的百分比优惠自本版本在各安装上首次启动起精确到分；旧数据不变。共用的 PDF 查看器尚未在打印预览、浏览器收银端和 Android 平板上验证。"
]'::jsonb
);
