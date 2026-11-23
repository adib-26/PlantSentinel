// Page enhancements that make the evidence easier to follow (and a little haunted):
// - hover a citation such as [E3] to see that evidence and a thread to it; hover an evidence class to light up its items
// - approving seals a recommendation with wax, rejecting makes it fade away, then the form is sent as usual
// - slow requests (investigation, report, inspection, manual upload) show what the server is doing meanwhile
// - search words are highlighted in manual passages; the EMF meter lights up the card of the machine you point at
// - every figure on the overview stays live, and a change log lists what changed since the page opened
// - the Kiro assistant fills in example questions, and questions sent from the spirit board
// - on the sign-in page the cursor is a lantern; with the sound on, the clock tower strikes, hidden ghosts sigh when
//   the lantern finds them, and the gate creaks as you sign in
// Text taken from the page or the server is always inserted with textContent.
(function () {
    'use strict';
    var H = window.BasHaunt || { isHaunted: function () { return false; }, reducedMotion: function () { return true; }, say: function () {} };
    var SVGNS = 'http://www.w3.org/2000/svg';

    // ------------------------------------------------------------------ citations summon their evidence
    var preview = null;
    var thread = null;
    var active = null;

    function evidenceParts(target) {
        var title = target.querySelector('.d-flex strong') || target.querySelector('.small.text-secondary') || target.querySelector('strong');
        var detail = target.querySelector('.evidence-detail') || target.querySelector('.explanation');
        var source = target.querySelector('.small.text-secondary:last-child');
        var group = target.closest('.ev-group, .ai-ghost');
        var cls = group ? group.querySelector('.ev-badge') : null;
        return {
            title: title ? title.textContent.replace(/\s+/g, ' ').trim() : target.id,
            detail: detail ? detail.textContent.trim() : '',
            source: source && source !== title && /^source:/.test(source.textContent.trim()) ? source.textContent.trim() : '',
            cls: cls ? cls.textContent.trim() : (group && group.classList.contains('ai-ghost') ? 'AI interpretation' : '')
        };
    }

    function showCite(a) {
        var href = a.getAttribute('href') || '';
        if (href.charAt(0) !== '#') {
            return;
        }
        var target = document.getElementById(decodeURIComponent(href.slice(1)));
        if (!target || target.contains(a)) {
            return;
        }
        hideCite();
        active = { a: a, target: target, title: a.getAttribute('title') };
        if (active.title) {
            a.removeAttribute('title');
        }
        target.classList.add('is-summoned');
        if (!preview) {
            preview = document.createElement('div');
            preview.className = 'spirit-preview';
            preview.setAttribute('aria-hidden', 'true');
            document.body.appendChild(preview);
        }
        var parts = evidenceParts(target);
        preview.replaceChildren();
        var head = document.createElement('div');
        head.className = 'sp-head';
        if (H.svgUse && H.isHaunted()) {
            head.appendChild(H.svgUse('i-candle'));
        }
        head.appendChild(document.createTextNode(parts.title));
        var body = document.createElement('div');
        body.className = 'sp-body';
        body.textContent = parts.detail.length > 420 ? parts.detail.slice(0, 420) + '…' : parts.detail;
        var foot = document.createElement('div');
        foot.className = 'sp-foot';
        foot.textContent = [parts.cls, parts.source, 'click to jump to ' + target.id].filter(Boolean).join(' · ');
        preview.appendChild(head);
        if (parts.detail) {
            preview.appendChild(body);
        }
        preview.appendChild(foot);
        place();
        preview.classList.add('is-shown');
        window.addEventListener('scroll', place, { passive: true });
    }

    function place() {
        if (!active || !preview) {
            return;
        }
        var r = active.a.getBoundingClientRect();
        var w = preview.offsetWidth;
        var h = preview.offsetHeight;
        var x = Math.min(Math.max(8, r.left + r.width / 2 - w / 2), window.innerWidth - w - 8);
        var y = r.bottom + 10;
        if (y + h > window.innerHeight - 8) {
            y = Math.max(8, r.top - h - 10);
        }
        preview.style.transform = 'none';
        preview.style.left = x.toFixed(0) + 'px';
        preview.style.top = y.toFixed(0) + 'px';
        drawThread(r, active.target.getBoundingClientRect(), { x: x, y: y, w: w, h: h });
    }

    function drawThread(from, to, box) {
        var visible = to.bottom > 0 && to.top < window.innerHeight && to.width > 0;
        if (!visible) {
            if (thread) {
                thread.style.display = 'none';
            }
            return;
        }
        if (!thread) {
            thread = document.createElementNS(SVGNS, 'svg');
            thread.setAttribute('class', 'spirit-thread');
            thread.setAttribute('aria-hidden', 'true');
            thread.appendChild(document.createElementNS(SVGNS, 'path'));
            document.body.appendChild(thread);
        }
        thread.style.display = '';
        var sx = from.left + from.width / 2;
        var sy = from.top + from.height / 2;
        var leftSide = to.left > sx;
        var ex = leftSide ? to.left - 4 : to.right + 4;
        var ey = Math.min(Math.max(sy, to.top + 14), to.bottom - 14);
        var bend = Math.max(60, Math.abs(ex - sx) * .45);
        var d = 'M' + sx.toFixed(0) + ' ' + sy.toFixed(0) + ' C' + (sx + (leftSide ? bend : -bend)).toFixed(0) + ' ' + (sy - 40).toFixed(0)
            + ' ' + (ex + (leftSide ? -bend : bend)).toFixed(0) + ' ' + ey.toFixed(0) + ' ' + ex.toFixed(0) + ' ' + ey.toFixed(0);
        thread.firstChild.setAttribute('d', d);
    }

    function hideCite() {
        if (!active) {
            return;
        }
        active.target.classList.remove('is-summoned');
        if (active.title) {
            active.a.setAttribute('title', active.title);
        }
        active = null;
        window.removeEventListener('scroll', place);
        if (preview) {
            preview.classList.remove('is-shown');
        }
        if (thread) {
            thread.style.display = 'none';
        }
    }

    document.addEventListener('mouseover', function (e) {
        var a = e.target.closest && e.target.closest('a.cite');
        if (a && (!active || active.a !== a)) {
            showCite(a);
        }
        var badge = e.target.closest && e.target.closest('[data-ev-filter]');
        if (badge) {
            focusClass(badge.getAttribute('data-ev-filter'));
        }
    });
    document.addEventListener('mouseout', function (e) {
        var a = e.target.closest && e.target.closest('a.cite');
        if (a && active && active.a === a && !a.contains(e.relatedTarget)) {
            hideCite();
        }
        var badge = e.target.closest && e.target.closest('[data-ev-filter]');
        if (badge && !badge.contains(e.relatedTarget)) {
            clearClass();
        }
    });
    document.addEventListener('focusin', function (e) {
        var a = e.target.closest && e.target.closest('a.cite');
        if (a) {
            showCite(a);
        }
    });
    document.addEventListener('focusout', function (e) {
        if (active && e.target === active.a) {
            hideCite();
        }
    });
    document.addEventListener('click', function (e) {
        if (e.target.closest && e.target.closest('a.cite')) {
            hideCite();
        }
    });

    function focusClass(cls) {
        var main = document.querySelector('main');
        var matches = document.querySelectorAll('.evidence-item[data-ev-class="' + cls + '"]');
        if (!main || !matches.length) {
            return;
        }
        main.classList.add('ev-filtering');
        document.querySelectorAll('.evidence-item.ev-match').forEach(function (n) { n.classList.remove('ev-match'); });
        matches.forEach(function (n) { n.classList.add('ev-match'); });
    }

    function clearClass() {
        var main = document.querySelector('main.ev-filtering');
        if (main) {
            main.classList.remove('ev-filtering');
            document.querySelectorAll('.evidence-item.ev-match').forEach(function (n) { n.classList.remove('ev-match'); });
        }
    }

    // ------------------------------------------------------------------ approving seals with wax, rejecting banishes
    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (!form.matches('form[data-review]') || form.dataset.sent || e.defaultPrevented) {
            return;
        }
        var button = e.submitter;
        if (!button || button.name !== 'approve' || !H.isHaunted() || H.reducedMotion()) {
            return;
        }
        e.preventDefault();
        form.dataset.sent = '1';
        var decision = document.createElement('input');
        decision.type = 'hidden';
        decision.name = 'approve';
        decision.value = button.value;
        form.appendChild(decision);
        form.querySelectorAll('button').forEach(function (b) { b.disabled = true; });
        var rec = form.closest('.rec');
        var approve = button.value === 'true';
        if (rec) {
            rec.classList.add(approve ? 'is-sealing' : 'is-banishing');
        }
        if (approve && H.chime) {
            H.chime();
        }
        H.say(approve ? 'Sealed. The work order will appear in Maintenance.' : 'Banished. Nothing will be done about it.', 2500);
        setTimeout(function () { HTMLFormElement.prototype.submit.call(form); }, approve ? 700 : 620);
    });

    // ------------------------------------------------------------------ séance while a slow request runs
    var RITUALS = {
        investigation: { title: 'Gathering evidence…', sub: 'The investigation pipeline is reading the plant. This takes a few seconds.',
            steps: ['telemetry trends', 'anomaly detectors', 'risk model', 'inspections', 'manual sections', 'fault rules'] },
        report: { title: 'Writing the report…', sub: 'The same pipeline runs and opens an investigation with every piece of evidence it used.',
            steps: ['telemetry trends', 'anomaly detectors', 'risk model', 'inspections', 'manual sections', 'fault rules'] },
        'report-issue': { title: 'Sealing the incident report…', sub: 'The decisions and the evidence are written into the report.',
            steps: ['decisions', 'evidence', 'incident report'] },
        inspection: { title: 'Scrying the image…', sub: 'OpenCV prepares the image and the defect model (ONNX) looks for damage.',
            steps: ['decode', 'normalise', 'defect model', 'annotate'] },
        manual: { title: 'Transcribing the tome…', sub: 'Apache Tika extracts the text; it is split into sections and indexed for search.',
            steps: ['extract text', 'split sections', 'index'] }
    };
    var seance = null;

    document.addEventListener('submit', function (e) {
        var form = e.target;
        var kind = form.getAttribute && form.getAttribute('data-seance');
        if (!kind || e.defaultPrevented || !RITUALS[kind]) {
            return;
        }
        var ritual = RITUALS[kind];
        if (e.submitter) {
            setTimeout(function () { e.submitter.disabled = true; }, 0);
        }
        seance = document.createElement('div');
        seance.className = 'seance';
        seance.setAttribute('role', 'status');
        seance.setAttribute('aria-live', 'polite');
        var box = document.createElement('div');
        box.className = 'seance-box';
        var circle = document.createElement('div');
        circle.className = 'seance-circle';
        circle.setAttribute('aria-hidden', 'true');
        var ring = document.createElement('div');
        ring.className = 'sc-ring';
        for (var i = 0; i < 6; i++) {
            var c = document.createElement('span');
            c.className = 'seance-candle';
            c.style.setProperty('--a', (i * 60) + 'deg');
            c.style.setProperty('--i', String(i));
            ring.appendChild(c);
        }
        circle.appendChild(ring);
        if (H.svgUse) {
            circle.appendChild(H.svgUse('g-kiro', 'sc-ghost', '0 0 64 72'));
        }
        var spinner = document.createElement('div');
        spinner.className = 'spinner-border text-primary seance-spinner';
        spinner.setAttribute('aria-hidden', 'true');
        var title = document.createElement('div');
        title.className = 'seance-title';
        title.textContent = ritual.title;
        var sub = document.createElement('div');
        sub.className = 'seance-sub';
        sub.textContent = ritual.sub;
        var steps = document.createElement('div');
        steps.className = 'seance-steps';
        ritual.steps.forEach(function (s, n) {
            var span = document.createElement('span');
            span.style.setProperty('--i', String(n));
            span.textContent = s;
            steps.appendChild(span);
        });
        box.append(circle, spinner, title, sub, steps);
        seance.appendChild(box);
        document.body.appendChild(seance);
        requestAnimationFrame(function () { seance.classList.add('is-shown'); });
    });
    window.addEventListener('pageshow', function (e) {
        if (e.persisted) {
            document.querySelectorAll('.seance').forEach(function (n) { n.remove(); });
            document.querySelectorAll('form[data-seance] button[disabled], form[data-review] button[disabled]').forEach(function (b) { b.disabled = false; });
            document.querySelectorAll('form[data-review][data-sent]').forEach(function (f) { delete f.dataset.sent; });
            document.querySelectorAll('.rec.is-sealing, .rec.is-banishing').forEach(function (r) { r.classList.remove('is-sealing', 'is-banishing'); });
        }
    });

    // ------------------------------------------------------------------ search words glow in the manual passages
    var hits = document.querySelector('.search-hits[data-highlight]');
    if (hits && hits.getAttribute('data-highlight')) {
        var words = hits.getAttribute('data-highlight').toLowerCase().split(/[^\p{L}\p{N}]+/u)
            .filter(function (w, i, all) { return w.length >= 3 && all.indexOf(w) === i; }).slice(0, 12);
        if (words.length) {
            var pattern = new RegExp('(' + words.map(function (w) { return w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }).join('|') + ')', 'gi');
            hits.querySelectorAll('.evidence-detail').forEach(function (block) {
                var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
                var nodes = [];
                while (walker.nextNode()) {
                    nodes.push(walker.currentNode);
                }
                nodes.forEach(function (node) {
                    var parts = node.nodeValue.split(pattern);
                    if (parts.length < 2) {
                        return;
                    }
                    var frag = document.createDocumentFragment();
                    parts.forEach(function (p, idx) {
                        if (idx % 2 === 1) {
                            var m = document.createElement('mark');
                            m.className = 'ink';
                            m.textContent = p;
                            frag.appendChild(m);
                        } else if (p) {
                            frag.appendChild(document.createTextNode(p));
                        }
                    });
                    node.parentNode.replaceChild(frag, node);
                });
            });
        }
    }

    // ------------------------------------------------------------------ EMF meter lights point at their machine
    document.querySelectorAll('.emf-led').forEach(function (led) {
        var link = document.querySelector('a.machine-link[href="' + led.getAttribute('href') + '"]');
        var card = link && link.querySelector('.machine-card');
        if (!card) {
            return;
        }
        var on = function () { card.classList.add('is-called'); led.classList.add('is-called'); };
        var off = function () { card.classList.remove('is-called'); led.classList.remove('is-called'); };
        led.addEventListener('mouseenter', on);
        led.addEventListener('mouseleave', off);
        led.addEventListener('focus', on);
        led.addEventListener('blur', off);
        link.addEventListener('mouseenter', function () { led.classList.add('is-called'); });
        link.addEventListener('mouseleave', function () { led.classList.remove('is-called'); });
    });

    // ------------------------------------------------------------------ the overview stays live
    // The page carries the figures it was drawn with (data-overview, the same JSON as /overview.json). Every 30 s
    // they come back from /overview.json, scoped to your factory and rate limited like other risk assessments, and
    // every count, card, badge, light and alert follows them. The server formats the texts, so a refreshed figure
    // reads exactly like a freshly loaded one. The change log under the machine cards lists, with the time, each change
    // of risk level, health or connectivity and each new critical alert. Refreshing pauses while the tab is hidden,
    // waits as long as the server asks (429 or 503 with Retry-After) and stops when the session has ended.
    var overviewMain = document.querySelector('main[data-overview]');
    if (overviewMain) {
        liveOverview(overviewMain);
    }

    function liveOverview(main) {
        var POLL_MS = 30000;
        var TIMEOUT_MS = 20000;
        var LOG_MAX = 8;
        var ICON = { alert: 'i-ghost', health: 'i-candle', risk: 'i-orb', conn: 'i-moon', machine: 'i-factory', session: 'i-key' };
        var brand = document.querySelector('.navbar-brand');
        var base = ((brand && brand.getAttribute('href')) || '/').replace(/\/$/, '');
        var state = null;
        try {
            state = JSON.parse(main.getAttribute('data-overview'));
        } catch (e) { /* no figures: the page stays as drawn */ }
        if (!state || !Array.isArray(state.machines) || !Array.isArray(state.alerts)) {
            return;
        }
        var cards = {};
        var leds = {};
        var levels = {};
        main.querySelectorAll('[data-machine-card]').forEach(function (n) { cards[n.getAttribute('data-machine-card')] = n; });
        main.querySelectorAll('[data-machine-led]').forEach(function (n) { leds[n.getAttribute('data-machine-led')] = n; });
        main.querySelectorAll('[data-level]').forEach(function (n) { levels[n.getAttribute('data-level')] = n; });
        var logBox = main.querySelector('.live-log');
        var seenAlerts = {};
        state.alerts.forEach(function (a) { seenAlerts[a.key] = true; });
        var newMachines = {};
        var lastPoll = Date.now();
        var lastGood = null;
        var timer = 0;
        var busy = false;
        var stopped = false;

        function hook(name) {
            return main.querySelector('[data-live="' + name + '"]');
        }

        function setText(node, value) {
            if (!node || node.textContent === value) {
                return false;
            }
            node.textContent = value;
            return true;
        }

        function bump(name, value) {
            var node = hook(name);
            if (setText(node, value)) {
                pulse(node, 'is-changed', 1400);
            }
        }

        function flag(name, cls, on) {
            var node = hook(name);
            if (node) {
                node.classList.toggle(cls, on);
            }
        }

        function pulse(node, cls, ms) {
            node.classList.remove(cls);
            void node.offsetWidth;
            node.classList.add(cls);
            setTimeout(function () { node.classList.remove(cls); }, ms);
        }

        function show(node, on) {
            if (node) {
                node.toggleAttribute('hidden', !on);
            }
        }

        function swapClass(node, prefix, value) {
            Array.prototype.slice.call(node.classList).forEach(function (c) {
                if (c.indexOf(prefix) === 0) {
                    node.classList.remove(c);
                }
            });
            node.classList.add(prefix + value);
        }

        function paintBadge(node, value, tone) {
            if (node) {
                node.className = 'badge text-bg-' + tone + ' st-' + value;
                node.textContent = value;
            }
        }

        function icon(id, cls) {
            return H.svgUse ? H.svgUse(id, cls) : document.createElement('span');
        }

        function clockTime(at) {
            var d = at ? new Date(at) : new Date();
            return (isNaN(d) ? new Date() : d).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        }

        function inView(node) {
            var r = node && node.getBoundingClientRect();
            return !!r && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight;
        }

        // ---- what changed between two refreshes, most urgent first
        function changes(prev, next) {
            var out = [];
            var before = {};
            prev.machines.forEach(function (m) { before[m.code] = m; });
            next.alerts.forEach(function (a) {
                if (a.severity === 'CRITICAL' && !seenAlerts[a.key]) {
                    out.push({ kind: 'alert', tone: 'danger', text: 'Critical alert on ' + a.code + ': ' + a.message });
                }
            });
            next.machines.forEach(function (m) {
                var p = before[m.code];
                if (!p) {
                    if (!cards[m.code] && !newMachines[m.code]) {
                        newMachines[m.code] = true;
                        out.push({ kind: 'machine', tone: 'info', reload: true, text: m.code + ' is new on this plant' });
                    }
                    return;
                }
                if (p.health !== m.health) {
                    out.push({ kind: 'health', tone: m.healthTone, text: m.code + ' health ' + p.health + ' → ' + m.health });
                }
                if (p.risk !== m.risk) {
                    out.push({ kind: 'risk', tone: m.risk ? m.riskTone : 'secondary', text: m.code + ' failure risk '
                        + (p.risk ? p.risk + ' ' + p.riskPct : 'not assessed') + ' → ' + (m.risk ? m.risk + ' ' + m.riskPct : 'not assessed') });
                }
                if (p.connectivity !== m.connectivity) {
                    out.push({ kind: 'conn', tone: m.connectivityTone, text: m.code + ' connectivity ' + p.connectivity + ' → ' + m.connectivity });
                }
            });
            return out;
        }

        // ---- apply a refresh to the page
        function apply(next) {
            bump('total', String(next.total));
            bump('normal', String(next.normal));
            bump('attention', String(next.attention));
            flag('attention-card', 'kpi-alarm', next.attention > 0);
            bump('alert-count', String(next.alertCount));
            flag('alert-count', 'text-danger', next.criticalAlerts > 0);
            setText(hook('critical-count'), next.criticalAlerts + ' critical');
            flag('alerts-card', 'kpi-critical', next.criticalAlerts > 0);
            flag('alerts-card', 'kpi-alarm', next.criticalAlerts === 0 && next.alertCount > 0);
            bump('pending', String(next.pendingReviews));
            flag('pending-card', 'kpi-alarm', next.pendingReviews > 0);

            var watch = hook('night-watch');
            if (watch) {
                ['alarm', 'uneasy', 'calm'].forEach(function (mood) { watch.classList.toggle('is-' + mood, next.nightWatch.mood === mood); });
            }
            setText(hook('night-watch-text'), next.nightWatch.text);

            var risk = next.risk;
            bump('mean', risk.mean);
            setText(hook('emf-value'), risk.mean);
            var emf = main.querySelector('.emf');
            if (emf) {
                emf.style.setProperty('--mean', String(risk.meanShare));
            }
            var highest = hook('highest');
            show(highest, !!risk.highest);
            setText(highest, risk.highest || '');
            setText(hook('assessed'), risk.assessedText);
            risk.byLevel.forEach(function (l) {
                var row = levels[l.level];
                if (row) {
                    var bar = row.querySelector('[data-live="level-bar"]');
                    if (bar) {
                        bar.style.width = l.share + '%';
                    }
                    setText(row.querySelector('[data-live="level-count"]'), String(l.count));
                }
            });
            next.machines.forEach(applyMachine);
            renderAlerts(next.alerts);
        }

        function applyMachine(m) {
            var assessed = m.risk !== null && m.risk !== undefined;
            var link = cards[m.code];
            if (link) {
                var card = link.querySelector('.machine-card');
                var before = card.style.getPropertyValue('--risk').trim();
                swapClass(card, 'tone-', m.healthTone);
                swapClass(card, 'risk-', m.risk || 'NONE');
                swapClass(card, 'conn-', m.connectivity);
                card.classList.toggle('is-restless', m.restless);
                card.classList.toggle('is-possessed', m.possessed);
                card.style.setProperty('--risk', String(m.riskShare));
                paintBadge(link.querySelector('[data-live="health"] .badge'), m.health, m.healthTone);
                paintBadge(link.querySelector('[data-live="conn"] .badge'), m.connectivity, m.connectivityTone);
                var riskBlock = link.querySelector('[data-live="risk"]');
                show(riskBlock, assessed);
                show(link.querySelector('[data-live="meter"]'), assessed);
                show(link.querySelector('[data-live="no-risk"]'), !assessed);
                if (assessed && riskBlock) {
                    setText(riskBlock.querySelector('[data-live="risk-value"]'), m.riskPct);
                    paintBadge(riskBlock.querySelector('.badge'), m.risk, m.riskTone);
                }
                var fill = link.querySelector('[data-live="meter"] > span');
                if (fill) {
                    fill.style.width = m.riskShare + '%';
                }
                show(link.querySelector('[data-live="apparition"]'), m.anomalies24h > 0);
                setText(link.querySelector('[data-live="anomalies"]'), m.anomaliesText);
                setText(link.querySelector('[data-live="data-line"]'), m.dataLine);
                show(link.querySelector('[data-live="quiet"]'), !!m.quiet);
                if (m.quiet) {
                    setText(link.querySelector('[data-live="quiet-text"]'), m.quiet);
                }
                if (before && before !== String(m.riskShare)) {
                    pulse(card, 'is-updated', 1700);
                }
            }
            var led = leds[m.code];
            if (led) {
                var said = assessed ? m.riskPct + ' ' + m.risk : null;
                swapClass(led, 'risk-', m.risk || 'NONE');
                led.style.setProperty('--risk', String(m.riskShare));
                led.title = m.code + ' · ' + (said || 'no risk data');
                setText(led.querySelector('.visually-hidden'), m.code + ' · failure risk ' + (said || 'not assessed'));
            }
        }

        function renderAlerts(list) {
            var host = hook('alerts');
            if (!host) {
                return;
            }
            var items = list.map(function (a) {
                var li = document.createElement('li');
                li.className = 'mb-1 sev-' + a.severity + (seenAlerts[a.key] ? '' : ' is-new');
                var badge = document.createElement('span');
                paintBadge(badge, a.severity, a.tone);
                var link = document.createElement('a');
                link.href = base + '/machines/' + encodeURIComponent(a.code);
                link.textContent = a.code;
                var message = document.createElement('span');
                message.textContent = a.message;
                var ago = document.createElement('span');
                ago.className = 'text-secondary';
                ago.textContent = '· ' + a.ago;
                li.append(icon('i-ghost', 'hi apparition-ico costume-only'), ' ', badge, ' ', link, ' ', message, ' ', ago);
                return li;
            });
            if (!items.length) {
                var none = document.createElement('li');
                none.className = 'text-secondary';
                none.textContent = 'No anomalies in the last hour.';
                var tonight = document.createElement('span');
                tonight.className = 'costume-only';
                tonight.textContent = ' No apparitions tonight.';
                none.appendChild(tonight);
                items.push(none);
            }
            host.replaceChildren.apply(host, items);
            list.forEach(function (a) { seenAlerts[a.key] = true; });
        }

        // ---- the change log (role="log": screen readers announce each new entry)
        function addToLog(entries, at) {
            var list = hook('log-list');
            if (!list || !entries.length) {
                return;
            }
            var when = new Date(at);
            if (isNaN(when)) {
                when = new Date();
            }
            for (var i = entries.length - 1; i >= 0; i--) {
                var e = entries[i];
                var li = document.createElement('li');
                li.className = 'is-new log-' + e.kind + ' lt-' + e.tone;
                var time = document.createElement('time');
                time.dateTime = when.toISOString();
                time.textContent = clockTime(when);
                var words = document.createElement('span');
                words.className = 'log-text';
                words.textContent = e.text;
                if (e.reload) {
                    var again = document.createElement('a');
                    again.href = window.location.pathname;
                    again.textContent = 'Reload to see its card';
                    words.append('. ', again);
                }
                if (e.signIn) {
                    var signIn = document.createElement('a');
                    signIn.href = base + '/login';
                    signIn.textContent = 'Sign in again';
                    words.append(' ', signIn);
                }
                li.append(time, icon(ICON[e.kind] || 'i-scroll', 'hi costume-only'), words);
                list.prepend(li);
            }
            while (list.children.length > LOG_MAX) {
                list.lastElementChild.remove();
            }
            show(hook('log-head'), true);
            if (logBox) {
                logBox.classList.add('has-entries');
            }
        }

        // ---- the "live" stamp under the EMF meter (not announced; the log is)
        function stamp(kind, at) {
            var node = hook('stamp');
            if (!node) {
                return;
            }
            var dot = node.querySelector('.live-dot');
            node.replaceChildren();
            if (dot) {
                node.appendChild(dot);
            }
            node.classList.toggle('is-live', kind === 'live');
            node.classList.toggle('is-stale', kind === 'stale');
            if (kind === 'live') {
                lastGood = at;
                node.append(' live · updated ' + clockTime(at));
            } else if (kind === 'stale') {
                node.append(' not updating · figures from ' + (lastGood ? clockTime(lastGood) : 'page load') + ' · retrying');
            } else {
                node.append(' paused · session ended');
            }
        }

        function update(next) {
            var entries = changes(state, next);
            apply(next);
            state = next;
            stamp('live', next.at);
            addToLog(entries, next.at);
            var spoke = H.feedMachines ? H.feedMachines(next.machines) : false;
            // The ghost only tells you about changes when the log is out of sight (scrolled away), never over it.
            if (entries.length && !spoke && H.isHaunted() && !inView(logBox)) {
                H.say(entries[0].text.replace(/[.\s]+$/, '') + (entries.length > 1 ? ', and ' + (entries.length - 1)
                    + ' more in the change log' : '') + '. The omens have shifted.', 6000, true);
            }
        }

        function ended() {
            stopped = true;
            clearTimeout(timer);
            stamp('ended');
            addToLog([{ kind: 'session', tone: 'secondary', signIn: true,
                text: 'Live updates stopped: your session has ended. The figures above are from ' + clockTime(lastGood || state.at) + '.' }],
                new Date().toISOString());
        }

        function retryAfter(r) {
            var seconds = parseInt(r.headers.get('Retry-After') || '', 10);
            return isFinite(seconds) && seconds > 0 ? Math.min(seconds, 600) * 1000 : POLL_MS;
        }

        function poll() {
            clearTimeout(timer);
            if (stopped || busy || document.hidden) {
                return; // a hidden tab resumes on visibilitychange; a running request schedules the next one
            }
            busy = true;
            var delay = POLL_MS;
            var options = { credentials: 'same-origin', redirect: 'manual', cache: 'no-store', headers: { 'Accept': 'application/json' } };
            if (window.AbortSignal && AbortSignal.timeout) {
                options.signal = AbortSignal.timeout(TIMEOUT_MS);
            }
            fetch(base + '/overview.json', options)
                .then(function (r) {
                    // An ended session answers with a redirect to the sign-in page (or 401/403): stop asking.
                    if (r.type === 'opaqueredirect' || r.status === 401 || r.status === 403) {
                        ended();
                        return null;
                    }
                    if (r.status === 429 || r.status === 503) {
                        delay = Math.max(POLL_MS, retryAfter(r));
                    }
                    if (!r.ok || !/json/i.test(r.headers.get('Content-Type') || '')) {
                        throw new Error('HTTP ' + r.status);
                    }
                    return r.json();
                })
                .then(function (next) {
                    if (next && !stopped && Array.isArray(next.machines)) {
                        update(next);
                    }
                })
                .catch(function () {
                    if (!stopped) {
                        stamp('stale');
                    }
                })
                .finally(function () {
                    busy = false;
                    lastPoll = Date.now();
                    if (!stopped) {
                        timer = setTimeout(poll, delay);
                    }
                });
        }

        function resume() {
            if (!stopped && !busy) {
                clearTimeout(timer);
                timer = setTimeout(poll, Math.max(0, POLL_MS - (Date.now() - lastPoll)));
            }
        }

        document.addEventListener('visibilitychange', function () {
            if (!document.hidden) {
                resume();
            }
        });
        window.addEventListener('pageshow', function (e) {
            if (e.persisted) {
                resume();
            }
        });
        if (H.feedMachines) {
            H.feedMachines(state.machines);
        }
        timer = setTimeout(poll, POLL_MS);
    }

    // ------------------------------------------------------------------ Kiro assistant
    var chat = document.getElementById('chat-form');
    var box = chat && chat.querySelector('textarea[name="message"]');
    var listener = chat && chat.querySelector('.kiro-listener');
    var listenTimer = 0;
    function listening() {
        if (!listener) {
            return;
        }
        listener.classList.add('is-listening');
        clearTimeout(listenTimer);
        listenTimer = setTimeout(function () { listener.classList.remove('is-listening'); }, 900);
    }
    if (box) {
        box.addEventListener('input', listening);
        try {
            var ask = sessionStorage.getItem('bas.ask');
            if (ask) {
                sessionStorage.removeItem('bas.ask');
                if (!box.value.trim()) {
                    box.value = ask.slice(0, +(box.getAttribute('maxlength') || 200));
                }
                box.focus();
                listening();
                setTimeout(function () { H.say('Your question is ready. Press “Send to Kiro” when you are.', 4500); }, 600);
            }
        } catch (e) { /* storage blocked */ }
    }
    document.querySelectorAll('.example-chip').forEach(function (chip) {
        chip.addEventListener('click', function () {
            if (!box) {
                H.say('Only engineers can talk to Kiro, and Kiro must be available on this server.', 4000);
                return;
            }
            box.value = chip.getAttribute('data-example') || chip.textContent;
            box.focus();
            listening();
        });
    });
    var steps = document.getElementById('kiro-steps');
    if (steps && window.MutationObserver) {
        var seen = 0;
        new MutationObserver(function () {
            var lis = steps.children;
            for (var i = seen; i < lis.length; i++) {
                lis[i].classList.add('is-new');
            }
            seen = lis.length;
        }).observe(steps, { childList: true });
    }

    // ------------------------------------------------------------------ sign-in: the lantern, and the night's sounds
    // The cursor is a lantern. With the sound on (the Sound switch under the form): the clock tower strikes when the
    // sound starts, a hidden ghost sighs when the lantern finds it, and the gate creaks as you sign in.
    var scene = document.querySelector('.scene-login .haunt-scene');
    var lantern = scene && scene.querySelector('.lantern');
    var skyline = scene && scene.querySelector('.skyline');
    var spot = document.getElementById('lantern-spot');
    if (lantern && skyline && spot) {
        var frame = 0;
        var last = null;
        var spirits = hiddenSpirits(skyline);
        var lastSigh = 0;
        // A spirit sighs when the light reaches it: once per visit, at most every 8 s, never two within 1.8 s.
        var listenFor = function (x, y) {
            var now = Date.now();
            spirits.forEach(function (s) {
                var inside = Math.hypot(x - s.x, y - s.y) < (s.big ? 130 : 100);
                if (inside && !s.inside && now - lastSigh > 1800 && now - s.at > 8000 && H.sigh && H.sigh(s.big)) {
                    lastSigh = now;
                    s.at = now;
                }
                s.inside = inside;
            });
        };
        var moveTo = function (x, y) {
            lantern.style.setProperty('--mx', x.toFixed(0) + 'px');
            lantern.style.setProperty('--my', y.toFixed(0) + 'px');
            var ctm = skyline.getScreenCTM();
            if (ctm && window.DOMPoint) {
                var p = new DOMPoint(x, y).matrixTransform(ctm.inverse());
                spot.setAttribute('cx', p.x.toFixed(1));
                spot.setAttribute('cy', p.y.toFixed(1));
                listenFor(p.x, p.y);
            }
        };
        document.addEventListener('pointermove', function (e) {
            last = e;
            if (!frame) {
                frame = requestAnimationFrame(function () {
                    frame = 0;
                    if (H.isHaunted()) {
                        moveTo(last.clientX, last.clientY);
                    }
                });
            }
        }, { passive: true });
        document.addEventListener('focusin', function (e) {
            if (H.isHaunted() && e.target.matches('input, button')) {
                var r = e.target.getBoundingClientRect();
                moveTo(r.left + r.width / 2, r.top + r.height / 2);
            }
        });
        // switching the sound on: the clock tower strikes, and its face glows while the bell sounds
        var clock = skyline.querySelector('.clock');
        document.addEventListener('ambiencechange', function (e) {
            if (e.detail && e.detail.on && H.toll && H.toll() && clock) {
                clock.classList.remove('is-striking');
                void clock.getBoundingClientRect();
                clock.classList.add('is-striking');
                setTimeout(function () { clock.classList.remove('is-striking'); }, 2400);
            }
        });
    }

    // The ghost behind the clock tower and each pair of eyes in a dark window, in the skyline's own coordinates
    // (read from the markup, so it works before the scene is drawn, e.g. when the page opens in daylight).
    function hiddenSpirits(svg) {
        var out = [];
        var tower = svg.querySelectorAll('.tower-ghost .tg-eye');
        if (tower.length) {
            var tx = 0;
            var ty = 0;
            tower.forEach(function (n) {
                tx += +n.getAttribute('cx');
                ty += +n.getAttribute('cy');
            });
            out.push({ x: tx / tower.length, y: ty / tower.length, big: true, inside: false, at: 0 });
        }
        svg.querySelectorAll('.window-eyes ellipse').forEach(function (n) {
            var x = +n.getAttribute('cx');
            var y = +n.getAttribute('cy');
            var pair = out.filter(function (s) { return !s.big && Math.abs(s.y - y) < 1 && Math.abs(s.x - x) < 12; })[0];
            if (pair) {
                pair.x = (pair.x + x) / 2;
            } else {
                out.push({ x: x, y: y, big: false, inside: false, at: 0 });
            }
        });
        return out;
    }

    // Signing in with the sound on: the gate creaks open and its ghost floats aside, then the form is sent as usual.
    // With the sound off the form is sent at once.
    var gate = document.querySelector('.scene-login form');
    if (gate) {
        gate.addEventListener('submit', function (e) {
            if (gate.dataset.sent) {
                e.preventDefault(); // already on its way
                return;
            }
            var wait = H.isHaunted() && H.creak ? H.creak() : 0;
            if (!wait) {
                return;
            }
            e.preventDefault();
            gate.dataset.sent = '1';
            document.body.classList.add('is-entering');
            setTimeout(function () { HTMLFormElement.prototype.submit.call(gate); }, wait);
        });
        window.addEventListener('pageshow', function (e) {
            if (e.persisted) {
                delete gate.dataset.sent;
                document.body.classList.remove('is-entering');
            }
        });
    }
})();
