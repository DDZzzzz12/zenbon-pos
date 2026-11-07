-- Release-marker migration for v1.1.2.
--
-- `changelog_release` (created in V79) is the single source of truth for the user-facing
-- release notes (CLAUDE.md rule 13). Write-only by design. Highlights are a curated subset:
-- operator-visible changes only, grouped under `## ` section headings (see V90 and
-- `ChangelogSection` in `shared`). Scope was taken from the TREE diff against v1.1.1.
--
-- release_date MUST match the date of the git tag on `master`; correct it before tagging if the
-- tag slips. Validate an edit twice: an SQL-quoting walk ('' is one escaped quote) AND a JSON parse
-- of every array with equal en/de/zh item counts. V94 and V95 were added after this file by parallel
-- work and follow it; the position is irrelevant for seeding, which selects markers by filename. A
-- fresh install seeds it through SchemaManager.seedReleaseNotes. After the tag, late findings go in
-- the NEXT marker, never here.

INSERT INTO changelog_release (
    release_version,
    release_date,
    title_en, title_de, title_zh,
    summary_en, summary_de, summary_zh,
    highlights_en, highlights_de, highlights_zh,
    known_issues_en, known_issues_de, known_issues_zh
) VALUES (
    '1.1.2',
    '2026-10-11',

    'Problem reports from the admin panel, a TSE that recovers by itself and a redesigned phone till',
    'Problemmeldungen aus dem Admin-Panel, eine TSE, die sich selbst erholt, und eine überarbeitete Handy-Kasse',
    '后台问题报告、可自动恢复的 TSE 和全新改版的手机收银端',

    'Operators can report a problem to GitHub from the admin panel, a TSE plugged in while the server runs signs again without a restart, and the till gets a status bar on every device, a new phone layout and a working browser till for Chinese and for physical keyboards.',
    'Betreiber können aus dem Admin-Panel ein Problem an GitHub melden, eine bei laufendem Server eingesteckte TSE signiert ohne Neustart wieder, und die Kasse erhält auf jedem Gerät eine Statusleiste, ein neues Handy-Layout und eine Browser-Kasse, die Chinesisch und physische Tastaturen beherrscht.',
    '运营者可在后台直接向 GitHub 报告问题；服务器运行中插入的 TSE 无需重启即可恢复签名；收银端在所有设备上都有状态栏，手机布局全新，浏览器收银端也支持中文和实体键盘。',

    '[
"## New and updated: reporting a problem",
"Report a problem from the admin panel. The user menu has a Report a problem entry that files a GitHub issue. Only the server holds the GitHub token. Before anything is sent, a preview shows exactly the text that will be posted, and that preview is what is sent. A log excerpt is optional, contains only warnings and errors and is scrubbed of personal data. Reports are limited to 5 per hour. Administrators and managers may report; an administrator sets the target repository and the token under Settings, Problem reports.",
"Reports to a public repository are readable by everyone, so they never include a log excerpt or the shop name. Read the preview before you send. The token is stored encrypted and can only be replaced, not displayed.",
"## New and updated: the till",
"The status bar shows the date with the year next to the time on tablets and desktops. Phones now have a status bar too, with the employee number, server and TSE status and the date.",
"New phone layout. The bottom bar is Dashboard, Verwaltung and Reservierungen. Settings moved into the Verwaltung menu. Notifications open from a bell with the unread count at the top right of the dashboard. Tapping the shop logo at the top left refreshes the dashboard and reloads the shop logo. The Android navigation bar is hidden; swipe in from the screen edge to show it briefly.",
"Long product, payment method and area names no longer wrap onto a second line and break the card. They shrink or scroll. On phones the Notifications title now fits, the connection state shows as a dot and clearing all is an icon. The labels under Backoffice öffnen and the log-out entry in the side rail are centered. The language button shows DE, EN or ZH instead of a flag symbol that some screens could not draw.",
"Browser till: Chinese now displays. The till loads the Chinese font from the server once, about 1.6 MB, and shows Chinese names under every language. Letters typed on a physical keyboard are no longer turned into digits. The product search takes focus when the order screen opens; Enter adds the first match and Esc clears the field. Esc closes the table lookup, and the order screen is left only with its back arrow, no longer with the system Back button or gesture.",
"## New and updated: documents and servers",
"Belege: a password-protected PDF can now be stored unlocked. The web admin asks for the password when you upload one and the server removes the protection, so the PDF can be read and a ZUGFeRD or Factur-X e-invoice inside it is extracted as usual. A PDF that only restricts editing unlocks with an empty password; a wrong password is refused and nothing is stored. If you keep the PDF locked it is stored as it is and cannot be processed: no invoice data is read and it has to be entered by hand.",
"The installer can set up the maintainer account for log search. On install and update, install-service.sh asks once, default no, and then asks for a username and the password twice, creates the hash on that machine and writes it to pos-server.env. There is no default account or password, and an install or update never changes an existing account. sudo ./install-service.sh --maintainer sets or replaces the account later and --maintainer-off removes it; scripts can pass the password on standard input with --maintainer-user NAME --maintainer-password-stdin. Use /maintenance/ only over HTTPS.",
"Server setup mounts the backup stick by name. A node set up with provision-node.sh now mounts a USB stick labelled ZEN_BACKUP at /media/pos/ZEN_BACKUP without any manual mounting; set that path as the backup path in the admin panel. Label the stick once, for example exfatlabel /dev/sdX1 ZEN_BACKUP. A stick with another label is not mounted.",
"Kiosk: Close the screen UI on the System tab now really turns the screen off while the server keeps running. Press Ctrl+Alt+U, or the combination set in KIOSK_WAKE_COMBO, to bring the till back; a reboot also does. Polkit lets the kiosk user start the screen-off service instead of stopping the kiosk.",
"## Fixed",
"A TSE plugged in while the server runs signs again without a restart. After re-plugging the stick, a power blip on its USB port, or plugging it in after the server had started, the TSE stayed not ready and bills and Kassenbuch entries stayed queued for Nachsignieren until the server was restarted or TSE neu starten was pressed: the automatic self test and the Selbsttest button had no TSE client. The server now gives the TSE the same client and TimeAdmin PIN as at startup, runs the self test and time update by itself and signs the queue. On a TSE 1.x at least one active till must be registered on the TSE; without one the self test says so instead of trying an unregistered till.",
"Kassenbuch entries without a VAT reference, such as Geldtransit and Bankeinzahlung, are stored with rate 0 and shown as Nicht steuerbar instead of 19 %. The DATEV XML export no longer writes a tax of 19.00 for them. The signed data and the DSFinV-K export already treated them as non-taxable and are unchanged; entries booked before the update are not rewritten.",
"Deleting a choice group now removes it from every product. Before, products kept listing the deleted group: the product editor showed an empty entry and the till offered a group without a name or options. Groups deleted earlier are cleaned up when the server updates."
]'::jsonb,
    '[
"## Neu und geändert: Problem melden",
"Problem aus dem Admin-Panel melden. Das Benutzermenü hat den Eintrag Problem melden, der ein GitHub-Issue anlegt. Das GitHub-Token liegt nur auf dem Server. Vor dem Senden zeigt eine Vorschau genau den Text, der gesendet wird, und genau diese Vorschau wird gesendet. Ein Log-Auszug ist optional, enthält nur Warnungen und Fehler und wird von personenbezogenen Daten bereinigt. Es sind höchstens 5 Meldungen pro Stunde möglich. Administratoren und Manager dürfen melden; ein Administrator legt das Ziel-Repository und das Token unter Einstellungen, Problemmeldungen fest.",
"Meldungen an ein öffentliches Repository kann jeder lesen, deshalb enthalten sie nie einen Log-Auszug oder den Namen des Betriebs. Lesen Sie die Vorschau vor dem Senden. Das Token wird verschlüsselt gespeichert und lässt sich nur ersetzen, nicht anzeigen.",
"## Neu und geändert: die Kasse",
"Die Statusleiste zeigt auf Tablet und Desktop das Datum mit Jahr neben der Uhrzeit. Auch Handys haben jetzt eine Statusleiste mit Mitarbeiternummer, Server- und TSE-Status und Datum.",
"Neues Handy-Layout. Die untere Leiste besteht aus Dashboard, Verwaltung und Reservierungen. Die Einstellungen sind ins Menü Verwaltung gewandert. Benachrichtigungen öffnen Sie über eine Glocke mit der Zahl ungelesener Meldungen oben rechts im Dashboard. Ein Tipp auf das Geschäftslogo oben links aktualisiert das Dashboard und lädt das Logo neu. Die Android-Navigationsleiste ist ausgeblendet; wischen Sie vom Bildschirmrand, um sie kurz einzublenden.",
"Lange Namen von Artikeln, Zahlarten und Bereichen brechen nicht mehr in eine zweite Zeile um und zerstören die Karte; sie werden kleiner oder laufen als Lauftext. Auf dem Handy passt jetzt der Titel Benachrichtigungen, der Verbindungsstatus erscheint als Punkt und Alle löschen als Symbol. Die Beschriftungen von Backoffice öffnen und Abmelden in der Seitenleiste sind zentriert. Die Sprachwahl zeigt DE, EN oder ZH statt eines Flaggensymbols, das manche Bildschirme nicht darstellen konnten.",
"Browser-Kasse: Chinesisch wird jetzt dargestellt. Die Kasse lädt die chinesische Schrift einmalig vom Server, etwa 1,6 MB, und zeigt chinesische Namen in jeder Sprache an. Auf einer physischen Tastatur getippte Buchstaben werden nicht mehr zu Ziffern. Die Artikelsuche erhält den Fokus, sobald die Bestellansicht öffnet; Enter fügt den ersten Treffer hinzu, Esc leert das Feld. Esc schließt die Tischsuche, und die Bestellansicht verlassen Sie nur mit ihrem Zurück-Pfeil, nicht mehr mit der System-Zurück-Taste oder -Geste.",
"## Neu und geändert: Belege und Server",
"Belege: Ein passwortgeschütztes PDF kann jetzt entsperrt gespeichert werden. Das Web-Admin fragt beim Hochladen nach dem Passwort und der Server entfernt den Schutz, sodass das PDF gelesen und eine darin enthaltene ZUGFeRD- oder Factur-X-Rechnung wie gewohnt ausgelesen werden kann. Ein PDF, das nur das Bearbeiten einschränkt, lässt sich mit leerem Passwort entsperren; ein falsches Passwort wird abgelehnt und es wird nichts gespeichert. Bleibt das PDF gesperrt, wird es unverändert gespeichert und kann nicht verarbeitet werden: Es werden keine Rechnungsdaten ausgelesen, sie müssen von Hand erfasst werden.",
"Das Installationsskript kann das Wartungskonto für die Log-Suche einrichten. Bei Installation und Update fragt install-service.sh einmalig (Standard: nein), fragt dann Benutzername und zweimal das Passwort, erzeugt den Hash auf diesem Rechner und trägt ihn in pos-server.env ein. Es gibt kein Standardkonto und kein Standardpasswort, und eine Installation oder ein Update ändert ein vorhandenes Konto nie. sudo ./install-service.sh --maintainer richtet das Konto später ein oder ersetzt es, --maintainer-off entfernt es; Skripte können das Passwort mit --maintainer-user NAME --maintainer-password-stdin über die Standardeingabe übergeben. /maintenance/ nur über HTTPS verwenden.",
"Die Server-Einrichtung hängt den Backup-Stick über seinen Namen ein. Ein mit provision-node.sh eingerichteter Rechner bindet einen USB-Stick mit dem Namen ZEN_BACKUP jetzt ohne manuelles Einhängen unter /media/pos/ZEN_BACKUP ein; tragen Sie diesen Pfad im Admin-Panel als Backup-Pfad ein. Benennen Sie den Stick einmalig, zum Beispiel exfatlabel /dev/sdX1 ZEN_BACKUP. Ein Stick mit anderem Namen wird nicht eingebunden.",
"Kiosk: Bildschirm-Oberfläche schließen auf dem Reiter System schaltet den Bildschirm jetzt wirklich aus, während der Server weiterläuft. Mit Strg+Alt+U oder der in KIOSK_WAKE_COMBO gesetzten Kombination kommt die Kasse zurück; auch ein Neustart genügt. Polkit erlaubt dem Kiosk-Benutzer, den Bildschirm-Aus-Dienst zu starten, statt den Kiosk zu stoppen.",
"## Behoben",
"Eine bei laufendem Server eingesteckte TSE signiert wieder ohne Neustart. Nach dem Wiedereinstecken des Sticks, einem Stromaussetzer am USB-Anschluss oder dem Einstecken nach dem Serverstart blieb die TSE nicht betriebsbereit, und Belege und Kassenbucheinträge blieben im Nachsignieren, bis der Server neu gestartet oder TSE neu starten gedrückt wurde: Der automatische Selbsttest und die Schaltfläche Selbsttest hatten keinen TSE-Client. Der Server gibt der TSE jetzt denselben Client und dieselbe TimeAdmin-PIN wie beim Start, führt Selbsttest und Zeitabgleich selbst aus und signiert die Warteschlange nach. Bei einer TSE 1.x muss mindestens eine aktive Kasse bei der TSE registriert sein; fehlt sie, meldet der Selbsttest das, statt eine nicht registrierte Kasse zu versuchen.",
"Kassenbuch-Vorgänge ohne USt-Bezug, etwa Geldtransit und Bankeinzahlung, werden mit Satz 0 gespeichert und als Nicht steuerbar statt als 19 % angezeigt. Der DATEV-XML-Export schreibt für sie keine Steuer von 19,00 mehr. Die signierten Daten und der DSFinV-K-Export behandelten sie bereits als nicht steuerbar und bleiben unverändert; vor dem Update gebuchte Vorgänge werden nicht umgeschrieben.",
"Das Löschen einer Auswahlgruppe entfernt sie jetzt von allen Artikeln. Zuvor führten die Artikel die gelöschte Gruppe weiter: Der Artikeleditor zeigte einen leeren Eintrag und die Kasse bot eine Gruppe ohne Namen und Optionen an. Früher gelöschte Gruppen werden beim Update des Servers bereinigt."
]'::jsonb,
    '[
"## 新增与更新：报告问题",
"可在后台报告问题。用户菜单中的“报告问题”会创建一个 GitHub Issue。GitHub 令牌只保存在服务器上。发送前，预览会显示将要发布的完整文本，实际发送的就是这份预览。日志摘录为可选项，只包含警告和错误，并会清除个人数据。每小时最多可报告 5 次。管理员和经理可以报告；管理员在设置的“问题报告”中指定目标仓库和令牌。",
"发送到公开仓库的报告任何人都能看到，因此不会包含日志摘录或店铺名称。发送前请先阅读预览。令牌以加密方式保存，只能替换，无法查看。",
"## 新增与更新：收银端",
"平板和桌面端的状态栏现在在时间旁显示含年份的日期。手机也有了状态栏，显示员工号、服务器和 TSE 状态以及日期。",
"全新的手机布局。底部栏为 Dashboard、Verwaltung 和 Reservierungen。设置移入了 Verwaltung 菜单。通知通过仪表盘右上角带未读数量的铃铛打开。点击左上角的店铺标志会刷新仪表盘并重新加载标志。Android 导航栏已隐藏；从屏幕边缘滑入可短暂显示。",
"过长的商品、支付方式和区域名称不再换行破坏卡片，而是缩小字号或滚动显示。在手机上，通知标题现在能完整显示，连接状态显示为圆点，“全部清除”改为图标。侧边栏中“打开后台”和“登出”的文字已居中。语言按钮显示 DE、EN 或 ZH，而不是部分屏幕无法显示的旗帜符号。",
"浏览器收银端：现在可以显示中文。收银端只需从服务器加载一次中文字体，约 1.6 MB，并在任何语言下都能显示中文名称。在实体键盘上输入的字母不再变成数字。打开点单界面时，商品搜索自动获得焦点；回车添加第一个匹配项，Esc 清空输入框。Esc 可关闭桌位查找，点单界面只能通过它自己的返回箭头离开，系统返回键或手势不再起作用。",
"## 新增与更新：单据与服务器",
"单据：受密码保护的 PDF 现在可以解锁后保存。上传时后台会询问密码，由服务器移除保护，这样 PDF 可以被读取，其中的 ZUGFeRD 或 Factur-X 电子发票也会照常提取。仅限制编辑的 PDF 可用空密码解锁；密码错误会被拒绝，且不会保存任何内容。如果保持锁定，PDF 将原样保存且无法被处理：不会读取发票数据，需要手动录入。",
"安装脚本现在可以为日志搜索设置维护账号。安装和更新时 install-service.sh 只询问一次（默认否），然后询问用户名并让您输入两次密码，在本机生成哈希并写入 pos-server.env。没有默认账号或默认密码，安装或更新也不会更改已有账号。之后可用 sudo ./install-service.sh --maintainer 设置或替换账号，用 --maintainer-off 删除；脚本可通过 --maintainer-user NAME --maintainer-password-stdin 从标准输入传入密码。请仅通过 HTTPS 使用 /maintenance/。",
"服务器设置现在按名称挂载备份 U 盘。用 provision-node.sh 设置的机器会把名为 ZEN_BACKUP 的 U 盘自动挂载到 /media/pos/ZEN_BACKUP，无需手动挂载；请在后台把该路径设为备份路径。只需为 U 盘命名一次，例如 exfatlabel /dev/sdX1 ZEN_BACKUP。名称不同的 U 盘不会被挂载。",
"Kiosk：系统页中的“关闭屏幕界面”现在会真正关闭屏幕，而服务器继续运行。按 Ctrl+Alt+U（或 KIOSK_WAKE_COMBO 中设置的组合键）即可恢复收银端；重启也可以。Polkit 现在允许 kiosk 用户启动关屏服务，而不是停止 kiosk。",
"## 已修复",
"服务器运行中插入的 TSE 无需重启即可恢复签名。重新插入 U 盘、USB 端口短暂断电，或在服务器启动后才插入 TSE 时，TSE 一直处于未就绪状态，小票和现金账簿记录一直留在补签队列中，直到重启服务器或点击 TSE neu starten：自动自检和 Selbsttest 按钮都没有 TSE 客户端。现在服务器会为 TSE 设置与启动时相同的客户端和 TimeAdmin PIN，自动执行自检和时间同步并补签队列。TSE 1.x 至少需要一台已在 TSE 注册的有效收银台；若没有，自检会明确提示，而不会尝试未注册的收银台。",
"不涉及增值税的现金账簿记录（如 Geldtransit 和 Bankeinzahlung）现在以税率 0 保存，并显示为“Nicht steuerbar”，而不是 19 %。DATEV XML 导出也不再为它们写入 19.00 的税额。已签名数据和 DSFinV-K 导出本来就按非应税处理，保持不变；更新前已记账的记录不会被改写。",
"删除选项组现在会把它从所有商品中移除。以前商品仍会列出已删除的选项组：商品编辑器显示一个空条目，收银端提供一个没有名称和选项的组。更早删除的选项组会在服务器更新时被清理。"
]'::jsonb,

    '[
"Unlocking a PDF invalidates any digital signature in it, and the locked original is not kept: only the unlocked copy is archived.",
"The problem-report token is sealed with the key of this server. After a backup is restored on another machine, enter the token again under Settings, Problem reports.",
"With three-button Android navigation the Back button is hidden until you swipe in from the screen edge.",
"After the update, Android shows a one-time Viewing full screen notice on the till because the navigation bar is now hidden. Tap Got it.",
"Turning the screen off was tried on one display model. On another, the picture may go dark while the backlight stays on; the shortcut still brings the till back.",
"The browser till downloads the Chinese font, about 1.6 MB, on its first start after the update. Until it arrives, Chinese characters show as boxes."
]'::jsonb,
    '[
"Das Entsperren eines PDFs macht eine digitale Signatur darin ungültig, und das gesperrte Original wird nicht aufbewahrt: Archiviert wird nur die entsperrte Kopie.",
"Das Token für Problemmeldungen ist mit dem Schlüssel dieses Servers verschlüsselt. Nach dem Zurückspielen einer Datensicherung auf einem anderen Rechner tragen Sie das Token unter Einstellungen, Problemmeldungen erneut ein.",
"Bei Android mit Drei-Tasten-Navigation ist die Zurück-Taste ausgeblendet, bis Sie vom Bildschirmrand wischen.",
"Nach dem Update zeigt Android einmalig den Hinweis Vollbildmodus an der Kasse, weil die Navigationsleiste jetzt ausgeblendet ist. Tippen Sie auf Verstanden.",
"Das Ausschalten des Bildschirms wurde an einem Display-Modell erprobt. Bei einem anderen kann das Bild dunkel werden, während die Hintergrundbeleuchtung an bleibt; die Tastenkombination holt die Kasse trotzdem zurück.",
"Die Browser-Kasse lädt die chinesische Schrift, etwa 1,6 MB, beim ersten Start nach dem Update. Bis sie da ist, erscheinen chinesische Zeichen als Kästchen."
]'::jsonb,
    '[
"解锁 PDF 会使其中的数字签名失效，且不会保留已锁定的原件：只归档解锁后的副本。",
"问题报告的令牌使用本服务器的密钥加密。在另一台机器上恢复备份后，请在设置的“问题报告”中重新输入令牌。",
"使用 Android 三键导航时，返回键会被隐藏，需从屏幕边缘滑入才会显示。",
"更新后，由于导航栏已隐藏，Android 会在收银端一次性显示“全屏查看”提示。请点击“知道了”。",
"关闭屏幕仅在一种显示器型号上试过。在其他型号上，画面可能变黑而背光仍亮着；快捷键仍可恢复收银端。",
"更新后首次启动时，浏览器收银端会下载约 1.6 MB 的中文字体。在下载完成前，中文字符会显示为方框。"
]'::jsonb
);
