(function () {
    'use strict';

    var TOKEN_KEY = 'maintenanceToken';
    var SOURCES = ['rejected', 'error', 'client-error', 'application'];

    var MESSAGES = {
        en: {
            title: 'Server maintenance', language: 'Language', signIn: 'Sign in', signOut: 'Sign out',
            username: 'Username', password: 'Password',
            badCredentials: 'Wrong username or password.',
            throttled: 'Too many failed attempts. Try again in {0} seconds.',
            sessionExpired: 'The session has ended. Please sign in again.',
            notAvailable: 'The maintainer account is not enabled on this server.',
            traceId: 'Trace id', from: 'From', to: 'To', level: 'Minimum level', anyLevel: 'Any',
            code: 'Error code', status: 'HTTP status', text: 'Contains text', sources: 'Sources',
            search: 'Search', searching: 'Searching…', noResults: 'No matching entries.',
            summary: 'Showing {0} of {1} matching entries from {2} files.', since: 'Searched since {0}.',
            truncatedCap: 'There were more matches than can be kept. Narrow the filter (trace id, time range, level).',
            truncatedBudget: 'The search stopped early to protect the server. Narrow the time range or sources.',
            retention: 'Logs before {0} are no longer kept.',
            logger: 'Logger', duration: 'Duration', client: 'Client', copy: 'Copy',
            requestFailed: 'The request failed ({0}).',
        },
        de: {
            title: 'Server-Wartung', language: 'Sprache', signIn: 'Anmelden', signOut: 'Abmelden',
            username: 'Benutzername', password: 'Passwort',
            badCredentials: 'Benutzername oder Passwort falsch.',
            throttled: 'Zu viele Fehlversuche. Bitte in {0} Sekunden erneut versuchen.',
            sessionExpired: 'Die Sitzung ist abgelaufen. Bitte erneut anmelden.',
            notAvailable: 'Das Wartungskonto ist auf diesem Server nicht aktiviert.',
            traceId: 'Trace-ID', from: 'Von', to: 'Bis', level: 'Mindest-Level', anyLevel: 'Alle',
            code: 'Fehlercode', status: 'HTTP-Status', text: 'Enthält Text', sources: 'Quellen',
            search: 'Suchen', searching: 'Suche läuft…', noResults: 'Keine passenden Einträge.',
            summary: '{0} von {1} passenden Einträgen aus {2} Dateien.', since: 'Durchsucht ab {0}.',
            truncatedCap: 'Es gab mehr Treffer, als gespeichert werden können. Filter eingrenzen (Trace-ID, Zeitraum, Level).',
            truncatedBudget: 'Die Suche wurde zum Schutz des Servers vorzeitig beendet. Zeitraum oder Quellen eingrenzen.',
            retention: 'Logs vor dem {0} werden nicht mehr aufbewahrt.',
            logger: 'Logger', duration: 'Dauer', client: 'Client', copy: 'Kopieren',
            requestFailed: 'Die Anfrage ist fehlgeschlagen ({0}).',
        },
        zh: {
            title: '服务器维护', language: '语言', signIn: '登录', signOut: '退出登录',
            username: '用户名', password: '密码',
            badCredentials: '用户名或密码错误。',
            throttled: '失败次数过多，请在 {0} 秒后重试。',
            sessionExpired: '会话已结束，请重新登录。',
            notAvailable: '此服务器未启用维护账户。',
            traceId: '追踪 ID', from: '开始', to: '结束', level: '最低级别', anyLevel: '全部',
            code: '错误代码', status: 'HTTP 状态', text: '包含文本', sources: '来源',
            search: '搜索', searching: '搜索中…', noResults: '没有匹配的条目。',
            summary: '显示 {0} / {1} 条匹配记录，来自 {2} 个文件。', since: '搜索范围自 {0} 起。',
            truncatedCap: '匹配项过多，无法全部保留。请缩小筛选范围（追踪 ID、时间、级别）。',
            truncatedBudget: '为保护服务器，搜索已提前停止。请缩小时间范围或来源。',
            retention: '{0} 之前的日志已不再保留。',
            logger: '日志器', duration: '耗时', client: '客户端', copy: '复制',
            requestFailed: '请求失败（{0}）。',
        },
    };

    function pickLanguage() {
        var wanted = (navigator.language || 'en').slice(0, 2).toLowerCase();
        return MESSAGES[wanted] ? wanted : 'en';
    }

    function readToken() {
        try { return sessionStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
    }

    function writeToken(value) {
        try {
            if (value) { sessionStorage.setItem(TOKEN_KEY, value); } else { sessionStorage.removeItem(TOKEN_KEY); }
        } catch (e) { /* storage may be blocked; the page then works until reload */ }
    }

    function toIso(localValue) {
        if (!localValue) { return ''; }
        var date = new Date(localValue);
        return isNaN(date.getTime()) ? '' : date.toISOString();
    }

    document.addEventListener('alpine:init', function () {
        Alpine.data('maintenance', function () {
            return {
                lang: pickLanguage(),
                token: readToken(),
                sourceNames: SOURCES,
                login: { username: '', password: '', busy: false, error: '' },
                query: { traceId: '', from: '', to: '', level: '', code: '', status: '', text: '', sources: SOURCES.slice() },
                result: null,
                error: '',
                busy: false,
                openRows: {},

                t: function (key) {
                    var table = MESSAGES[this.lang] || MESSAGES.en;
                    var text = table[key] !== undefined ? table[key] : (MESSAGES.en[key] !== undefined ? MESSAGES.en[key] : key);
                    for (var i = 1; i < arguments.length; i++) { text = text.replace('{' + (i - 1) + '}', String(arguments[i])); }
                    return text;
                },

                signIn: function () {
                    var self = this;
                    self.login.busy = true;
                    self.login.error = '';
                    fetch('/api/maintenance/login', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ username: self.login.username, password: self.login.password }),
                    }).then(function (response) {
                        if (response.status === 404) { self.login.error = self.t('notAvailable'); return null; }
                        if (response.status === 429) {
                            self.login.error = self.t('throttled', response.headers.get('Retry-After') || '30');
                            return null;
                        }
                        if (response.status === 401) { self.login.error = self.t('badCredentials'); return null; }
                        if (!response.ok) { self.login.error = self.t('requestFailed', response.status); return null; }
                        return response.json();
                    }).then(function (body) {
                        if (body && body.accessToken) {
                            self.token = body.accessToken;
                            writeToken(body.accessToken);
                            self.login.password = '';
                        }
                    }).catch(function () {
                        self.login.error = self.t('requestFailed', 'network');
                    }).finally(function () {
                        self.login.busy = false;
                    });
                },

                signOut: function (message) {
                    this.token = '';
                    writeToken('');
                    this.result = null;
                    this.openRows = {};
                    this.error = '';
                    this.login.error = message || '';
                },

                search: function () {
                    var self = this;
                    var params = new URLSearchParams();
                    if (self.query.traceId) { params.set('traceId', self.query.traceId); }
                    if (toIso(self.query.from)) { params.set('from', toIso(self.query.from)); }
                    if (toIso(self.query.to)) { params.set('to', toIso(self.query.to)); }
                    if (self.query.level) { params.set('level', self.query.level); }
                    if (self.query.code) { params.set('code', self.query.code); }
                    if (self.query.status) { params.set('status', String(self.query.status)); }
                    if (self.query.text) { params.set('q', self.query.text); }
                    if (self.query.sources.length > 0 && self.query.sources.length < SOURCES.length) {
                        self.query.sources.forEach(function (name) { params.append('source', name); });
                    }
                    self.busy = true;
                    self.error = '';
                    fetch('/api/maintenance/logs?' + params.toString(), {
                        headers: { 'Authorization': 'Bearer ' + self.token },
                    }).then(function (response) {
                        if (response.status === 401) { self.signOut(self.t('sessionExpired')); return null; }
                        if (!response.ok) {
                            return response.json().catch(function () { return {}; }).then(function (problem) {
                                self.error = (problem && problem.detail) || self.t('requestFailed', response.status);
                                return null;
                            });
                        }
                        return response.json();
                    }).then(function (body) {
                        if (body) { self.result = body; self.openRows = {}; }
                    }).catch(function () {
                        self.error = self.t('requestFailed', 'network');
                    }).finally(function () {
                        self.busy = false;
                    });
                },

                summary: function () {
                    if (!this.result) { return ''; }
                    var text = this.t('summary', this.result.entries.length, this.result.matched, this.result.scannedFiles);
                    // Without a trace id the server searches only the last 24 hours unless a start is given;
                    // say so, or an older entry looks like it does not exist.
                    return this.result.from ? text + ' ' + this.t('since', this.formatTime(this.result.from)) : text;
                },

                retentionNote: function () {
                    if (!this.result || !this.result.oldestAvailable) { return ''; }
                    var asked = toIso(this.query.from);
                    if (!asked) { return ''; }
                    return asked.slice(0, 10) < this.result.oldestAvailable ? this.t('retention', this.result.oldestAvailable) : '';
                },

                toggle: function (index) { this.openRows[index] = !this.openRows[index]; },
                isOpen: function (index) { return !!this.openRows[index]; },
                formatTime: function (iso) { return new Date(iso).toLocaleString(this.lang); },
                exceptionText: function (entry) {
                    return entry.exception ? [entry.exception.summary].concat(entry.exception.frames).join('\n') : '';
                },
                // navigator.clipboard exists only in a secure context (HTTPS or localhost); over plain
                // HTTP on the LAN the button would do nothing, so it is not shown.
                get canCopy() { return !!(window.isSecureContext && navigator.clipboard); },
                copy: function (text) {
                    if (navigator.clipboard && text) { navigator.clipboard.writeText(text).catch(function () {}); }
                },
            };
        });
    });
})();
