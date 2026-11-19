// Page enhancements that make the evidence easier to follow (and a little haunted):
// - hover a citation such as [E3] to see that evidence and a thread to it; hover an evidence class to light up its items
// - approving seals a recommendation with wax, rejecting makes it fade away, then the form is sent as usual
// - slow requests (investigation, report, inspection, manual upload) show what the server is doing meanwhile
// - search words are highlighted in manual passages; the EMF meter lights up the card of the machine you point at
// - the Kiro assistant fills in example questions, and questions sent from the spirit board
// - on the sign-in page the cursor is a lantern
// Text taken from the page is always inserted with textContent.
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
    // Every 30 s the machine list (health and the same failure-risk assessment as the cards) comes back from
    // /machines.json; the cards' risk, aura, meter and the EMF meter follow it. Counts that also depend on
    // connectivity are not recomputed here, so a changed risk level is announced with a link to reload.
    var TONE = { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger', CRITICAL: 'danger' };
    var LEVELS = ['NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
    var firstLevels = {};
    var cardsByCode = {};
    var ledsByCode = {};
    function codeOf(a) {
        return decodeURIComponent((a.getAttribute('href') || '').split('/').pop());
    }
    document.querySelectorAll('a.machine-link').forEach(function (link) {
        var card = link.querySelector('.machine-card');
        var code = codeOf(link);
        cardsByCode[code] = card;
        firstLevels[code] = LEVELS.filter(function (l) { return card.classList.contains('risk-' + l); })[0] || 'NONE';
    });
    document.querySelectorAll('a.emf-led').forEach(function (led) { ledsByCode[codeOf(led)] = led; });

    function setLevelClass(el, level) {
        LEVELS.forEach(function (l) { el.classList.remove('risk-' + l); });
        el.classList.add('risk-' + level);
    }

    function applyMachines(list, at) {
        var scores = [];
        var highest = null;
        var changes = [];
        list.forEach(function (m) {
            if (m.riskScore === null || m.riskScore === undefined) {
                return;
            }
            var pct = Math.round(m.riskScore * 100);
            scores.push(m.riskScore);
            if (!highest || m.riskScore > highest.riskScore) {
                highest = m;
            }
            var card = cardsByCode[m.code];
            if (card) {
                var before = card.style.getPropertyValue('--risk').trim();
                card.style.setProperty('--risk', String(pct));
                setLevelClass(card, m.risk);
                var restless = m.health === 'WARNING' || m.health === 'CRITICAL' || m.risk === 'HIGH' || m.risk === 'CRITICAL';
                card.classList.toggle('is-restless', restless);
                card.classList.toggle('is-possessed', m.health === 'CRITICAL' || m.risk === 'CRITICAL');
                var value = card.querySelector('.metric-value');
                if (value) {
                    value.textContent = pct + '%';
                    var badge = value.nextElementSibling;
                    if (badge && badge.classList.contains('badge')) {
                        badge.className = 'badge text-bg-' + (TONE[m.risk] || 'secondary') + ' st-' + m.risk;
                        badge.textContent = m.risk;
                    }
                }
                var fill = card.querySelector('.haunt-meter > span');
                if (fill) {
                    fill.style.width = pct + '%';
                }
                if (before && before !== String(pct)) {
                    card.classList.remove('is-updated');
                    void card.offsetWidth;
                    card.classList.add('is-updated');
                    setTimeout(function () { card.classList.remove('is-updated'); }, 1700);
                }
            }
            var led = ledsByCode[m.code];
            if (led) {
                led.style.setProperty('--risk', String(pct));
                setLevelClass(led, m.risk);
                led.title = m.code + ' · ' + pct + '% ' + m.risk;
                var label = led.querySelector('.visually-hidden');
                if (label) {
                    label.textContent = m.code + ' · failure risk ' + pct + '% ' + m.risk;
                }
            }
            var was = firstLevels[m.code];
            if (was && was !== 'NONE' && was !== m.risk) {
                changes.push(m.code + ' ' + was + ' → ' + m.risk);
            }
        });
        if (scores.length) {
            var mean = scores.reduce(function (a, b) { return a + b; }, 0) / scores.length;
            var meanPct = Math.round(mean * 100);
            var emf = document.querySelector('.emf');
            if (emf) {
                emf.style.setProperty('--mean', String(meanPct));
            }
            document.querySelectorAll('[data-live="mean"], [data-live="emf-value"]').forEach(function (n) { n.textContent = meanPct + '%'; });
            var top = document.querySelector('[data-live="highest"]');
            if (top && highest) {
                top.textContent = 'highest ' + highest.code + ' ' + Math.round(highest.riskScore * 100) + '%';
            }
        }
        var stamp = document.querySelector('[data-live="stamp"]');
        if (stamp) {
            var dot = stamp.querySelector('.live-dot');
            stamp.textContent = ' live · updated ' + new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
            if (dot) {
                stamp.prepend(dot);
            }
            stamp.classList.add('is-live');
        }
        var note = document.querySelector('[data-live="note"]');
        if (note) {
            note.replaceChildren();
            if (changes.length) {
                note.appendChild(document.createTextNode('Risk level changed since this page opened: ' + changes.join(', ') + '. '));
                var reload = document.createElement('a');
                reload.href = window.location.pathname;
                reload.textContent = 'Reload for every figure';
                note.appendChild(reload);
                note.hidden = false;
                if (H.isHaunted()) {
                    H.say(changes.join(', ') + '. The omens have shifted.', 5000);
                }
            } else {
                note.hidden = true;
            }
        }
    }
    if (document.querySelector('.machine-card')) {
        document.addEventListener('machinesupdate', function (e) { applyMachines(e.detail.machines, e.detail.at); });
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

    // ------------------------------------------------------------------ sign-in: the lantern
    var scene = document.querySelector('.scene-login .haunt-scene');
    var lantern = scene && scene.querySelector('.lantern');
    var skyline = scene && scene.querySelector('.skyline');
    var spot = document.getElementById('lantern-spot');
    if (lantern && skyline && spot) {
        var frame = 0;
        var last = null;
        var moveTo = function (x, y) {
            lantern.style.setProperty('--mx', x.toFixed(0) + 'px');
            lantern.style.setProperty('--my', y.toFixed(0) + 'px');
            var ctm = skyline.getScreenCTM();
            if (ctm && window.DOMPoint) {
                var p = new DOMPoint(x, y).matrixTransform(ctm.inverse());
                spot.setAttribute('cx', p.x.toFixed(1));
                spot.setAttribute('cy', p.y.toFixed(1));
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
    }
})();
