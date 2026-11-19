// The spirit board: a command palette to jump to any machine, page or investigation, search the manuals or ask Kiro.
// Press "/" (or Ctrl/Cmd+K) anywhere. In the haunted costume it is an Ouija board whose letters light up as you type
// and whose planchette glides to the selected answer. The Kiro ghost in the corner opens it too; it watches the
// cursor and gets scared when one of your machines turns CRITICAL (it polls /machines.json, scoped to your factory).
// All machine and user text is inserted with textContent, never as HTML.
(function () {
    'use strict';
    var H = window.BasHaunt || { isHaunted: function () { return false; }, reducedMotion: function () { return true; }, onCostume: function () {} };
    var SVGNS = 'http://www.w3.org/2000/svg';
    var brand = document.querySelector('.navbar-brand');
    var nav = document.querySelector('.navbar-nav');
    if (!brand || !nav) {
        return; // sign-in and error pages have no board
    }
    var BASE = (brand.getAttribute('href') || '/').replace(/\/$/, '');
    var roleText = (document.querySelector('.navbar-text') || {}).textContent || '';
    var canWrite = /engineer|admin/.test(roleText);
    var hasAssistant = !!nav.querySelector('a[href$="/assistant"]');
    var MAX_ASK = 200;

    var machines = [];
    var machinesAt = 0;
    var loading = null;
    var FRESH_MS = 30000;

    // One list per tab, shared by every page for 30 s, so moving between pages does not recompute the risk each time.
    try {
        var cached = JSON.parse(sessionStorage.getItem('bas.machines') || 'null');
        if (cached && Array.isArray(cached.list) && Date.now() - cached.at < FRESH_MS) {
            machines = cached.list;
            machinesAt = cached.at;
        }
    } catch (e) { /* no cache */ }

    function loadMachines(force) {
        if (!force && machinesAt && Date.now() - machinesAt < FRESH_MS) {
            return Promise.resolve(machines);
        }
        if (loading) {
            return loading;
        }
        loading = fetch(BASE + '/machines.json', { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(function (list) {
                machines = Array.isArray(list) ? list : [];
                machinesAt = Date.now();
                try {
                    sessionStorage.setItem('bas.machines', JSON.stringify({ at: machinesAt, list: machines }));
                } catch (e) { /* ignore */ }
                document.dispatchEvent(new CustomEvent('machinesupdate', { detail: { machines: machines, at: machinesAt } }));
                return machines;
            })
            .catch(function () { return machines; })
            .finally(function () { loading = null; });
        return loading;
    }

    // ------------------------------------------------------------------ the Kiro ghost
    var companion = null;
    var bubble = null;
    var bubbleTimer = 0;
    var mood = { critical: [], warning: [] };

    function buildCompanion() {
        companion = document.createElement('div');
        companion.className = 'kiro-companion';
        companion.innerHTML =
            '<button type="button" class="kc-button" aria-label="Kiro: open the spirit board to find machines, pages and reports (press /)">'
            + '<svg class="ghost-eyes" viewBox="0 0 64 72" aria-hidden="true">'
            + '<defs><radialGradient id="kc-grad" cx="40%" cy="28%" r="80%"><stop offset="0" stop-color="#fff"/>'
            + '<stop offset=".65" stop-color="#ece8ff"/><stop offset="1" stop-color="#c7bff2"/></radialGradient></defs>'
            + '<path d="M8 30C8 15.6 18.7 6 32 6s24 9.6 24 24v32a4.8 4.8 0 0 1-9.6 0 4.8 4.8 0 0 0-9.6 0 4.8 4.8 0 0 1-9.6 0 '
            + '4.8 4.8 0 0 0-9.6 0A4.8 4.8 0 0 1 8 62z" fill="url(#kc-grad)"/>'
            + '<g class="kc-brows" fill="none" stroke="#15132a" stroke-width="2.2" stroke-linecap="round"><path d="M18.5 24.6 26.5 21"/><path d="M45.5 24.6 37.5 21"/></g>'
            + '<g class="ge-eyes kc-eyes"><ellipse class="kc-eye" cx="24" cy="31" rx="3.9" ry="5.3" fill="#15132a"/>'
            + '<ellipse class="kc-eye" cx="40" cy="31" rx="3.9" ry="5.3" fill="#15132a"/>'
            + '<circle cx="25.3" cy="29.3" r="1.3" fill="#fff"/><circle cx="41.3" cy="29.3" r="1.3" fill="#fff"/></g>'
            + '<ellipse class="kc-mouth" cx="32" cy="42.5" rx="2.8" ry="3.4" fill="#15132a"/>'
            + '<ellipse cx="17.5" cy="38.5" rx="3.4" ry="2" fill="#ff9ec7" opacity=".55"/><ellipse cx="46.5" cy="38.5" rx="3.4" ry="2" fill="#ff9ec7" opacity=".55"/>'
            + '</svg><span class="kc-shadow"></span><span class="kc-badge" aria-hidden="true"></span></button>'
            + '<div class="kc-bubble" role="status" aria-live="polite"></div>';
        document.body.appendChild(companion);
        bubble = companion.querySelector('.kc-bubble');
        var button = companion.querySelector('.kc-button');
        button.addEventListener('click', function () { open(button); });
        button.addEventListener('mouseenter', function () { say(hoverLine(), 3500); });
        button.addEventListener('focus', function () { say(hoverLine(), 3500); });
    }

    function hoverLine() {
        if (mood.critical.length) {
            return codes(mood.critical) + (mood.critical.length === 1 ? ' is' : ' are') + ' CRITICAL. Click me: it is first on the board.';
        }
        if (mood.warning.length) {
            return codes(mood.warning) + (mood.warning.length === 1 ? ' is' : ' are') + ' restless. Press / to summon the board.';
        }
        return 'All quiet. Press / or click me to summon the spirit board.';
    }

    // 3 = critical health or critical failure risk, 2 = warning health or high risk, 1 = medium risk, 0 = calm
    function severity(m) {
        if (m.health === 'CRITICAL' || m.risk === 'CRITICAL') {
            return 3;
        }
        if (m.health === 'WARNING' || m.risk === 'HIGH') {
            return 2;
        }
        return m.risk === 'MEDIUM' ? 1 : 0;
    }

    function describe(m) {
        var parts = [];
        if (m.health === 'CRITICAL' || m.health === 'WARNING') {
            parts.push('health ' + m.health);
        }
        if (m.riskScore !== null && m.riskScore !== undefined) {
            parts.push('failure risk ' + Math.round(m.riskScore * 100) + '% ' + m.risk);
        }
        return m.code + (parts.length ? ' (' + parts.join(', ') + ')' : '');
    }

    function codes(list) {
        var names = list.slice(0, 2).map(describe);
        return names.join(' and ') + (list.length > 2 ? ' and ' + (list.length - 2) + ' more' : '');
    }

    function say(text, ms) {
        if (!bubble || !H.isHaunted()) {
            return;
        }
        bubble.textContent = text;
        bubble.classList.add('is-shown');
        companion.classList.add('is-out');
        clearTimeout(bubbleTimer);
        bubbleTimer = setTimeout(function () {
            bubble.classList.remove('is-shown');
            companion.classList.remove('is-out');
        }, ms || 4200);
    }
    H.say = say;

    function updateMood() {
        if (!companion) {
            return;
        }
        mood.critical = machines.filter(function (m) { return severity(m) === 3; });
        mood.warning = machines.filter(function (m) { return severity(m) === 2; });
        companion.classList.toggle('is-worried', mood.critical.length > 0);
        companion.classList.toggle('is-uneasy', !mood.critical.length && mood.warning.length > 0);
        var badge = companion.querySelector('.kc-badge');
        badge.textContent = String(mood.critical.length || mood.warning.length || '');
        var known = '';
        try {
            known = sessionStorage.getItem('bas.critical') || '';
        } catch (e) { /* ignore */ }
        var now = mood.critical.map(function (m) { return m.code; }).sort().join(',');
        var fresh = mood.critical.some(function (m) { return known.split(',').indexOf(m.code) < 0; });
        try {
            sessionStorage.setItem('bas.critical', now);
        } catch (e) { /* ignore */ }
        if (fresh) {
            say(codes(mood.critical) + (mood.critical.length === 1 ? ' has' : ' have') + ' turned CRITICAL. I can feel it from here.', 7000);
            if (H.heartbeat) {
                H.heartbeat(3);
            }
        }
    }

    // The Kiro ghost polls in costume; the overview polls in both costumes so its cards stay live.
    var overview = !!document.querySelector('.machine-card');

    function poll() {
        if (!document.hidden && (H.isHaunted() || overview)) {
            loadMachines(false).then(updateMood);
        }
    }

    // ------------------------------------------------------------------ the board
    var board = null;
    var input = null;
    var list = null;
    var planchette = null;
    var items = [];
    var selected = 0;
    var opener = null;
    var lastQuery = null;

    var PLANCHETTE = '<svg class="sb-planchette" viewBox="0 0 80 90" aria-hidden="true"><defs>'
        + '<linearGradient id="sb-wood" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#efcd96"/><stop offset="1" stop-color="#9b6b39"/></linearGradient></defs>'
        + '<path d="M40 3C17 3 4 22 6 43c2 18 16 30 34 44 18-14 32-26 34-44C76 22 63 3 40 3z" fill="url(#sb-wood)" stroke="#3a2412" stroke-width="2"/>'
        + '<circle cx="40" cy="38" r="14" fill="rgba(30,18,8,.35)" stroke="#3a2412" stroke-width="3"/>'
        + '<circle cx="40" cy="38" r="11" fill="rgba(255,236,200,.22)"/><circle cx="35" cy="33" r="3.2" fill="rgba(255,255,255,.6)"/></svg>';

    function buildBoard() {
        board = document.createElement('div');
        board.className = 'spirit-board';
        board.setAttribute('role', 'dialog');
        board.setAttribute('aria-modal', 'true');
        board.setAttribute('aria-label', 'Spirit board: find a machine, page or report');
        board.innerHTML =
            '<div class="sb-backdrop" data-close></div>'
            + '<div class="sb-panel">'
            + '<div class="sb-board" aria-hidden="true"></div>'
            + '<div class="sb-input-wrap"><svg class="hi" aria-hidden="true"><use href="#i-search"/></svg>'
            + '<input class="sb-input" type="text" role="combobox" aria-expanded="true" aria-controls="sb-list" aria-autocomplete="list"'
            + ' autocomplete="off" spellcheck="false" maxlength="200" placeholder="Ask the spirits: a machine, a page, INV-12, or a question">'
            + '<kbd>esc</kbd></div>'
            + '<ul class="sb-list" id="sb-list" role="listbox" aria-label="Results"></ul>'
            + '<div class="sb-goodbye" aria-hidden="true">GOODBYE</div>'
            + '<div class="sb-foot"><span><kbd>↑</kbd> <kbd>↓</kbd> choose · <kbd>↵</kbd> go · <kbd>esc</kbd> close</span>'
            + '<span>Press <kbd>/</kbd> on any page</span></div>'
            + '</div>';
        drawOuija(board.querySelector('.sb-board'));
        input = board.querySelector('.sb-input');
        list = board.querySelector('.sb-list');
        list.insertAdjacentHTML('afterbegin', PLANCHETTE);
        planchette = list.querySelector('.sb-planchette');
        board.addEventListener('click', function (e) {
            if (e.target.hasAttribute('data-close')) {
                close();
            }
        });
        input.addEventListener('input', function (e) {
            if (e.data) {
                spell(e.data.length > 1 ? e.data : e.data.slice(-1), e.data.length > 1);
            }
            render();
        });
        input.addEventListener('keydown', onKey);
        list.addEventListener('mousemove', function (e) {
            var li = e.target.closest('.sb-opt');
            if (li && +li.dataset.i !== selected) {
                select(+li.dataset.i);
            }
        });
        list.addEventListener('click', function (e) {
            var li = e.target.closest('.sb-opt');
            if (li) {
                activate(+li.dataset.i);
            }
        });
        document.body.appendChild(board);
    }

    // Two arcs of letters, a row of digits, YES and NO: drawn once, letters spaced evenly along each arc.
    function drawOuija(host) {
        var svg = document.createElementNS(SVGNS, 'svg');
        svg.setAttribute('viewBox', '0 0 680 238');
        function el(name, attrs, text) {
            var n = document.createElementNS(SVGNS, name);
            Object.keys(attrs).forEach(function (k) { n.setAttribute(k, attrs[k]); });
            if (text) {
                n.textContent = text;
            }
            svg.appendChild(n);
            return n;
        }
        function arc(chars, p0, p1, p2, cls) {
            var pts = [];
            var total = 0;
            var prev = null;
            for (var s = 0; s <= 240; s++) {
                var t = s / 240;
                var x = (1 - t) * (1 - t) * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0];
                var y = (1 - t) * (1 - t) * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1];
                if (prev) {
                    total += Math.hypot(x - prev[0], y - prev[1]);
                }
                pts.push([x, y, total, t]);
                prev = [x, y];
            }
            chars.split('').forEach(function (ch, i) {
                var target = total * i / (chars.length - 1);
                var p = pts.find(function (q) { return q[2] >= target; }) || pts[pts.length - 1];
                var t = p[3];
                var dx = 2 * (1 - t) * (p1[0] - p0[0]) + 2 * t * (p2[0] - p1[0]);
                var dy = 2 * (1 - t) * (p1[1] - p0[1]) + 2 * t * (p2[1] - p1[1]);
                var angle = Math.atan2(dy, dx) * 180 / Math.PI;
                el('text', { x: p[0].toFixed(1), y: p[1].toFixed(1), 'text-anchor': 'middle', 'class': cls,
                    'data-ch': ch, transform: 'rotate(' + angle.toFixed(1) + ' ' + p[0].toFixed(1) + ' ' + p[1].toFixed(1) + ')' }, ch);
            });
        }
        arc('ABCDEFGHIJKLM', [92, 130], [340, 14], [588, 130], 'sb-letters');
        arc('NOPQRSTUVWXYZ', [118, 186], [340, 76], [562, 186], 'sb-letters');
        '1234567890'.split('').forEach(function (d, i) {
            el('text', { x: (214 + i * 28).toFixed(0), y: '228', 'text-anchor': 'middle', 'class': 'sb-digits', 'data-ch': d }, d);
        });
        el('circle', { cx: '44', cy: '38', r: '12', 'class': 'sb-glyph' });
        for (var r = 0; r < 8; r++) {
            var a = r * Math.PI / 4;
            el('path', { d: 'M' + (44 + Math.cos(a) * 17).toFixed(1) + ' ' + (38 + Math.sin(a) * 17).toFixed(1)
                + 'L' + (44 + Math.cos(a) * 23).toFixed(1) + ' ' + (38 + Math.sin(a) * 23).toFixed(1), 'class': 'sb-glyph' });
        }
        el('path', { d: 'M640 24a15 15 0 1 0 10 26 12 12 0 0 1-10-26z', 'class': 'sb-glyph' });
        el('text', { x: '76', y: '48', 'class': 'sb-corner sb-yes' }, 'YES');
        el('text', { x: '612', y: '48', 'text-anchor': 'end', 'class': 'sb-corner sb-no' }, 'NO');
        host.appendChild(svg);
    }

    function spell(text, slow) {
        if (!H.isHaunted() || !board) {
            return;
        }
        text.toUpperCase().split('').slice(0, 24).forEach(function (ch, i) {
            setTimeout(function () {
                board.querySelectorAll('.sb-board [data-ch="' + (/^[A-Z0-9]$/.test(ch) ? ch : '#') + '"]').forEach(function (n) {
                    n.classList.add('lit');
                    setTimeout(function () { n.classList.remove('lit'); }, 650);
                });
            }, slow && !H.reducedMotion() ? i * 110 : 0);
        });
    }

    function light(selector, ms) {
        var n = board && board.querySelector(selector);
        if (n) {
            n.classList.add('lit');
            setTimeout(function () { n.classList.remove('lit'); }, ms || 600);
        }
    }

    // ------------------------------------------------------------------ results
    function pages() {
        var out = [];
        nav.querySelectorAll('a.nav-link').forEach(function (a) {
            var use = a.querySelector('use');
            out.push({ kind: 'page', label: a.textContent.trim(), href: a.getAttribute('href'),
                icon: use ? use.getAttribute('href').slice(1) : 'i-scroll', meta: 'Open the ' + a.textContent.trim() + ' page' });
        });
        out.push({ kind: 'page', label: 'Investigations', href: BASE + '/investigations', icon: 'i-scroll', meta: 'Every investigation and its status' });
        out.push({ kind: 'page', label: 'Model registry', href: BASE + '/models', icon: 'i-orb', meta: 'Trained models, test metrics and limitations' });
        if (canWrite) {
            out.push({ kind: 'page', label: 'Register a machine', href: BASE + '/machines/new', icon: 'i-factory', meta: 'Add a machine and point its edge node at it' });
        }
        return out;
    }

    var SEVERITY_DOT = ['#5be3a8', '#ffb84d', '#ff7e24', '#ff4d5e'];

    function machineItems() {
        return machines.map(function (m) {
            var sev = severity(m);
            var risk = m.riskScore !== null && m.riskScore !== undefined ? 'risk ' + Math.round(m.riskScore * 100) + '% ' + m.risk : 'no risk yet';
            return { kind: 'machine', label: m.code, href: BASE + '/machines/' + encodeURIComponent(m.code), icon: 'i-factory',
                meta: [m.name, m.type, m.location].filter(Boolean).join(' · ') + ' · health ' + m.health + ' · ' + risk,
                dot: m.health === 'UNKNOWN' && !m.risk ? '#9aa3b4' : SEVERITY_DOT[sev],
                search: [m.code, m.name, m.type, m.location, m.health, m.risk].join(' '), severity: sev };
        });
    }

    function actions() {
        var out = [{ kind: 'action', label: H.isHaunted() ? 'Take off the costume' : 'Put on the haunted costume', icon: 'i-pumpkin',
            meta: H.isHaunted() ? 'Back to the daylight look' : 'Lights out', run: function () { H.toggleCostume && H.toggleCostume(); } }];
        if (H.isHaunted()) {
            out.push({ kind: 'action', label: 'Ambient sound on or off', icon: 'i-sound', meta: 'Wind, a low hum, and a heartbeat when a machine turns critical',
                run: function () { H.toggleAmbience && H.toggleAmbience(); } });
            out.push({ kind: 'action', label: 'Release the ghosts', icon: 'i-ghost', meta: 'Or type b-o-o anywhere', run: function () { H.boo && H.boo(); } });
        }
        return out;
    }

    function score(q, text) {
        if (!q) {
            return 1;
        }
        var t = text.toLowerCase();
        if (t === q) {
            return 1000;
        }
        if (t.indexOf(q) === 0) {
            return 600;
        }
        var at = t.indexOf(q);
        if (at > 0) {
            return 300 - Math.min(at, 100);
        }
        var j = 0;
        for (var i = 0; i < t.length && j < q.length; i++) {
            if (t[i] === q[j]) {
                j++;
            }
        }
        return j === q.length ? 40 : 0;
    }

    function render() {
        var raw = input.value.trim();
        var q = raw.toLowerCase();
        if (q === lastQuery && items.length) {
            return;
        }
        lastQuery = q;
        var groups = [];
        var inv = /^inv[-\s]?(\d{1,9})$/i.exec(raw) || /^#?(\d{1,9})$/.exec(raw);
        if (inv) {
            groups.push({ title: 'Investigation', items: [{ kind: 'report', label: 'INV-' + inv[1], href: BASE + '/investigations/' + inv[1],
                icon: 'i-scroll', meta: 'Open investigation ' + inv[1] + ' with its evidence and recommendations' }] });
        }
        var ms = machineItems().map(function (m) { m.score = score(q, m.search) + (q ? 0 : m.severity); return m; })
            .filter(function (m) { return m.score > 0; }).sort(function (a, b) { return b.score - a.score || a.label.localeCompare(b.label); }).slice(0, 8);
        if (ms.length) {
            groups.push({ title: 'Machines', items: ms });
        } else if (!machinesAt) {
            groups.push({ title: 'Machines', items: [], note: 'Listening for machines…' });
        }
        var ps = pages().map(function (p) { p.score = score(q, p.label + ' ' + p.meta); return p; }).filter(function (p) { return p.score > 0; })
            .sort(function (a, b) { return b.score - a.score; }).slice(0, q ? 5 : 12);
        if (ps.length) {
            groups.push({ title: 'Pages', items: ps });
        }
        if (raw) {
            var asks = [{ kind: 'search', label: 'Search the manuals for “' + raw + '”', href: BASE + '/manuals?q=' + encodeURIComponent(raw),
                icon: 'i-book', meta: 'BM25 search over every maintenance manual section' }];
            if (canWrite && hasAssistant) {
                asks.push({ kind: 'ask', label: 'Ask Kiro: “' + raw.slice(0, MAX_ASK) + '”', href: BASE + '/assistant', ask: raw.slice(0, MAX_ASK),
                    icon: 'i-ghost', meta: 'Opens the Kiro assistant with your question ready to send' });
            }
            groups.push({ title: 'Ask the spirits', items: asks });
        }
        var acts = actions().map(function (a) { a.score = score(q, a.label + ' ' + a.meta); return a; }).filter(function (a) { return a.score > 0; });
        if (acts.length && (!q || acts.some(function (a) { return a.score >= 40; }))) {
            groups.push({ title: 'Spells', items: acts });
        }
        draw(groups, q);
    }

    function draw(groups, q) {
        list.querySelectorAll('.sb-group, .sb-opt, .sb-empty').forEach(function (n) { n.remove(); });
        items = [];
        groups.forEach(function (g) {
            var head = document.createElement('li');
            head.className = 'sb-group';
            head.setAttribute('role', 'presentation');
            head.textContent = g.title;
            list.appendChild(head);
            if (g.note) {
                var note = document.createElement('li');
                note.className = 'sb-empty';
                note.setAttribute('role', 'presentation');
                note.textContent = g.note;
                list.appendChild(note);
            }
            g.items.forEach(function (it) {
                var i = items.length;
                items.push(it);
                var li = document.createElement('li');
                li.className = 'sb-opt';
                li.id = 'sb-opt-' + i;
                li.dataset.i = String(i);
                li.setAttribute('role', 'option');
                li.setAttribute('aria-selected', 'false');
                if (it.dot) {
                    var dot = document.createElement('span');
                    dot.className = 'sb-dot';
                    dot.style.setProperty('--dot', it.dot);
                    li.appendChild(dot);
                } else {
                    li.appendChild(H.svgUse ? H.svgUse(it.icon, 'hi sb-ico') : document.createElement('span'));
                }
                var text = document.createElement('span');
                text.className = 'sb-text';
                var label = document.createElement('span');
                label.className = 'sb-label';
                appendMarked(label, it.label, it.kind === 'search' || it.kind === 'ask' ? '' : q);
                var meta = document.createElement('span');
                meta.className = 'sb-meta';
                meta.textContent = it.meta || '';
                text.appendChild(label);
                text.appendChild(meta);
                li.appendChild(text);
                var kind = document.createElement('span');
                kind.className = 'sb-kind';
                kind.textContent = it.kind === 'search' ? 'manuals' : it.kind;
                li.appendChild(kind);
                list.appendChild(li);
            });
        });
        if (!items.length) {
            var empty = document.createElement('li');
            empty.className = 'sb-empty';
            empty.setAttribute('role', 'presentation');
            empty.textContent = 'The spirits are silent. Try a machine code, a page name, or INV and a number.';
            list.appendChild(empty);
        }
        select(0);
    }

    function appendMarked(host, text, q) {
        var at = q ? text.toLowerCase().indexOf(q) : -1;
        if (at < 0) {
            host.textContent = text;
            return;
        }
        host.appendChild(document.createTextNode(text.slice(0, at)));
        var mark = document.createElement('mark');
        mark.textContent = text.slice(at, at + q.length);
        host.appendChild(mark);
        host.appendChild(document.createTextNode(text.slice(at + q.length)));
    }

    function select(i) {
        if (!items.length) {
            input.removeAttribute('aria-activedescendant');
            planchette.style.opacity = '0';
            return;
        }
        selected = (i + items.length) % items.length;
        list.querySelectorAll('.sb-opt').forEach(function (li) {
            li.setAttribute('aria-selected', +li.dataset.i === selected ? 'true' : 'false');
        });
        var li = list.querySelector('#sb-opt-' + selected);
        input.setAttribute('aria-activedescendant', li.id);
        li.scrollIntoView({ block: 'nearest' });
        planchette.style.opacity = '1';
        planchette.style.setProperty('--py', (li.offsetTop + li.offsetHeight / 2 - 26).toFixed(0) + 'px');
        planchette.style.setProperty('--pr', (-90 + (Math.random() - .5) * 14).toFixed(1) + 'deg');
    }

    function activate(i) {
        var it = items[i];
        if (!it) {
            return;
        }
        light('.sb-yes', 500);
        var go = function () {
            if (it.run) {
                close();
                it.run();
                return;
            }
            if (it.ask) {
                try {
                    sessionStorage.setItem('bas.ask', it.ask);
                } catch (e) { /* the page still opens */ }
            }
            window.location.href = it.href;
        };
        if (H.isHaunted() && !H.reducedMotion()) {
            setTimeout(go, 230);
        } else {
            go();
        }
    }

    function onKey(e) {
        if (e.key === 'ArrowDown' || (e.key === 'Tab' && !e.shiftKey)) {
            e.preventDefault();
            select(selected + 1);
        } else if (e.key === 'ArrowUp' || (e.key === 'Tab' && e.shiftKey)) {
            e.preventDefault();
            select(selected - 1);
        } else if (e.key === 'Home' && e.ctrlKey) {
            e.preventDefault();
            select(0);
        } else if (e.key === 'End' && e.ctrlKey) {
            e.preventDefault();
            select(items.length - 1);
        } else if (e.key === 'Enter') {
            e.preventDefault();
            activate(selected);
        } else if (e.key === 'Escape') {
            e.preventDefault();
            close();
        }
    }

    function open(from) {
        if (!board) {
            buildBoard();
        }
        if (board.classList.contains('is-open')) {
            input.focus();
            return;
        }
        opener = from || document.activeElement;
        board.classList.add('is-open');
        document.documentElement.style.overflow = 'hidden';
        input.value = '';
        lastQuery = null;
        render();
        requestAnimationFrame(function () {
            board.classList.add('is-visible');
            input.focus();
        });
        loadMachines(false).then(function () {
            lastQuery = null;
            if (board.classList.contains('is-open')) {
                render();
            }
        });
    }

    function close() {
        if (!board || !board.classList.contains('is-open')) {
            return;
        }
        var goodbye = board.querySelector('.sb-goodbye');
        goodbye.classList.add('lit');
        board.classList.remove('is-visible');
        setTimeout(function () {
            goodbye.classList.remove('lit');
            board.classList.remove('is-open');
            document.documentElement.style.overflow = '';
            if (opener && opener.focus) {
                opener.focus();
            }
        }, H.reducedMotion() ? 0 : 220);
    }

    document.addEventListener('keydown', function (e) {
        var t = e.target;
        var typing = t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName));
        if ((e.key === 'k' || e.key === 'K') && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            open();
        } else if (e.key === '/' && !typing && !e.metaKey && !e.ctrlKey && !e.altKey) {
            e.preventDefault();
            open();
        }
    });
    document.addEventListener('click', function (e) {
        var b = e.target.closest && e.target.closest('[data-summon]');
        if (b) {
            e.preventDefault();
            open(b);
        }
    });

    function syncCompanion() {
        if (H.isHaunted()) {
            if (!companion) {
                buildCompanion();
            }
            poll();
        }
    }
    H.onCostume(function () {
        syncCompanion();
        lastQuery = null;
    });
    syncCompanion();
    setInterval(poll, overview ? 30000 : 60000);
    document.addEventListener('visibilitychange', function () {
        if (!document.hidden) {
            poll();
        }
    });
    H.openBoard = open;
})();
