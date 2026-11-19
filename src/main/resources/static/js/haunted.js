// The haunted costume: switching costumes, ambient sound, and the atmosphere (fog, bats, lantern light on cards,
// ghost eyes that follow the cursor). Every effect is decoration on top of the page; nothing here changes data.
// Motion is skipped when the operating system asks for reduced motion. Typing "boo" outside a text field is a secret.
(function () {
    'use strict';
    var root = document.documentElement;
    var SVGNS = 'http://www.w3.org/2000/svg';
    var reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    var finePointer = window.matchMedia('(hover: hover) and (pointer: fine)');
    var GHOST_FAVICON = 'data:image/svg+xml,' + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 72"><path d="M8 30C8 15.6 18.7 6 32 6s24 9.6 24 24v32a4.8 4.8 0 0 1-9.6 0 '
        + '4.8 4.8 0 0 0-9.6 0 4.8 4.8 0 0 1-9.6 0 4.8 4.8 0 0 0-9.6 0A4.8 4.8 0 0 1 8 62z" fill="#f3f0ff" stroke="#7c3aed" stroke-width="3"/>'
        + '<ellipse cx="24" cy="31" rx="4.5" ry="6" fill="#15132a"/><ellipse cx="40" cy="31" rx="4.5" ry="6" fill="#15132a"/></svg>');

    function isHaunted() {
        return root.getAttribute('data-costume') === 'haunted';
    }

    function store(key, value) {
        try {
            if (value === undefined) {
                return localStorage.getItem(key);
            }
            localStorage.setItem(key, value);
        } catch (e) {
            return null;
        }
        return value;
    }

    function svgUse(id, className, viewBox) {
        var svg = document.createElementNS(SVGNS, 'svg');
        svg.setAttribute('class', className || 'hi');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('viewBox', viewBox || '0 0 24 24');
        var use = document.createElementNS(SVGNS, 'use');
        use.setAttribute('href', '#' + id);
        svg.appendChild(use);
        return svg;
    }

    var H = window.BasHaunt = {
        isHaunted: isHaunted,
        reducedMotion: function () { return reduced.matches; },
        svgUse: svgUse,
        say: function () { /* the Kiro ghost (spirit-board.js) replaces this */ },
        onCostume: function (fn) {
            document.addEventListener('costumechange', function (e) { fn(e.detail.costume); });
        }
    };

    // ---------------------------------------------------------------- costumes
    function applyCostume(costume, persist) {
        root.setAttribute('data-costume', costume);
        if (costume === 'haunted') {
            root.setAttribute('data-bs-theme', 'dark');
        } else {
            root.removeAttribute('data-bs-theme');
        }
        if (persist !== false) {
            store('bas.costume', costume);
        }
        syncControls();
        ensureAtmosphere();
        favicon();
        if (costume !== 'haunted') {
            stopAmbience();
        } else if (store('bas.ambience') === 'on' && audio) {
            startAmbience();
        }
        document.dispatchEvent(new CustomEvent('costumechange', { detail: { costume: costume } }));
    }

    function toggleCostume(origin) {
        var next = isHaunted() ? 'daylight' : 'haunted';
        if (reduced.matches || !document.body || !document.body.animate) {
            applyCostume(next);
            return;
        }
        var curtain = document.createElement('div');
        curtain.className = 'costume-curtain' + (next === 'daylight' ? ' daylight' : '');
        document.body.appendChild(curtain);
        var down = next === 'haunted'
            ? curtain.animate([{ opacity: 0 }, { opacity: .82, offset: .32 }, { opacity: .3, offset: .5 }, { opacity: 1 }],
                { duration: 540, easing: 'ease-in', fill: 'forwards' })
            : curtain.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 280, easing: 'ease-in', fill: 'forwards' });
        down.onfinish = function () {
            applyCostume(next);
            var up = curtain.animate([{ opacity: 1 }, { opacity: 0 }], { duration: next === 'haunted' ? 750 : 450, easing: 'ease-out', fill: 'forwards' });
            up.onfinish = function () { curtain.remove(); };
            if (next === 'haunted') {
                chime();
                spawnBats(origin, 7);
                setTimeout(function () { H.say('The lights are out. The plant is listening.'); }, 900);
            }
        };
    }

    function syncControls() {
        var haunted = isHaunted();
        document.querySelectorAll('[data-costume-toggle]').forEach(function (b) {
            b.setAttribute('aria-checked', haunted ? 'true' : 'false');
        });
        var on = store('bas.ambience') === 'on';
        document.querySelectorAll('[data-ambience]').forEach(function (b) {
            b.setAttribute('aria-pressed', on ? 'true' : 'false');
            b.title = on ? 'Ambient sound (on)' : 'Ambient sound (off)';
        });
    }

    function favicon() {
        var link = document.getElementById('haunt-favicon');
        if (isHaunted()) {
            if (!link) {
                link = document.createElement('link');
                link.id = 'haunt-favicon';
                link.rel = 'icon';
                link.type = 'image/svg+xml';
                link.href = GHOST_FAVICON;
                document.head.appendChild(link);
            }
        } else if (link) {
            link.remove();
        }
    }

    // ---------------------------------------------------------------- ambient sound (synthesised, nothing is downloaded)
    var audio = null;

    function startAmbience() {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) {
            return;
        }
        if (audio) {
            audio.ctx.resume();
            ramp(audio.master, .055, 2);
            return;
        }
        var ctx = new AC();
        var master = ctx.createGain();
        master.gain.value = 0;
        master.connect(ctx.destination);
        // wind: brown noise through a slowly wandering band-pass filter
        var length = ctx.sampleRate * 4;
        var buffer = ctx.createBuffer(1, length, ctx.sampleRate);
        var samples = buffer.getChannelData(0);
        var last = 0;
        for (var i = 0; i < length; i++) {
            last = (last + .02 * (Math.random() * 2 - 1)) / 1.02;
            samples[i] = last * 3.5;
        }
        var noise = ctx.createBufferSource();
        noise.buffer = buffer;
        noise.loop = true;
        var band = ctx.createBiquadFilter();
        band.type = 'bandpass';
        band.frequency.value = 420;
        band.Q.value = .7;
        var gust = ctx.createOscillator();
        gust.frequency.value = .06;
        var gustDepth = ctx.createGain();
        gustDepth.gain.value = 260;
        gust.connect(gustDepth);
        gustDepth.connect(band.frequency);
        noise.connect(band);
        band.connect(master);
        // drone: two detuned low notes and a fifth, breathing slowly
        var drone = ctx.createGain();
        drone.gain.value = .22;
        drone.connect(master);
        [55, 55.6, 82.4].forEach(function (f) {
            var o = ctx.createOscillator();
            o.type = 'sine';
            o.frequency.value = f;
            o.connect(drone);
            o.start();
        });
        var breath = ctx.createOscillator();
        breath.frequency.value = .09;
        var breathDepth = ctx.createGain();
        breathDepth.gain.value = .1;
        breath.connect(breathDepth);
        breathDepth.connect(drone.gain);
        noise.start();
        gust.start();
        breath.start();
        audio = { ctx: ctx, master: master };
        ramp(master, .055, 2.5);
    }

    function stopAmbience() {
        if (!audio) {
            return;
        }
        ramp(audio.master, 0, .8);
        setTimeout(function () {
            if (audio && store('bas.ambience') !== 'on') {
                audio.ctx.suspend();
            }
        }, 900);
    }

    function ramp(gain, to, seconds) {
        var t = gain.context.currentTime;
        gain.gain.cancelScheduledValues(t);
        gain.gain.setValueAtTime(gain.gain.value, t);
        gain.gain.linearRampToValueAtTime(to, t + seconds);
    }

    function toggleAmbience() {
        var on = store('bas.ambience') !== 'on';
        store('bas.ambience', on ? 'on' : 'off');
        if (on && isHaunted()) {
            startAmbience();
            H.say('Listen… the plant hums at night. If a machine turns critical, you will hear its heart.');
        } else {
            stopAmbience();
        }
        syncControls();
    }

    function tone(freqFrom, freqTo, peak, at, length) {
        var ctx = audio.ctx;
        var o = ctx.createOscillator();
        var g = ctx.createGain();
        o.type = 'sine';
        o.frequency.setValueAtTime(freqFrom, at);
        o.frequency.exponentialRampToValueAtTime(freqTo, at + length * .8);
        g.gain.setValueAtTime(0, at);
        g.gain.linearRampToValueAtTime(peak, at + .02);
        g.gain.exponentialRampToValueAtTime(.0005, at + length);
        o.connect(g);
        g.connect(ctx.destination);
        o.start(at);
        o.stop(at + length + .05);
    }

    // a heartbeat when a machine turns critical (only if the engineer switched the sound on)
    H.heartbeat = function (beats) {
        if (!audio || store('bas.ambience') !== 'on' || !isHaunted()) {
            return;
        }
        var t = audio.ctx.currentTime + .05;
        for (var i = 0; i < (beats || 3); i++) {
            tone(62, 38, .32, t, .24);
            tone(54, 34, .22, t + .28, .22);
            t += 1.05;
        }
    };

    function chime() {
        if (!audio || store('bas.ambience') !== 'on') {
            return;
        }
        var t = audio.ctx.currentTime + .02;
        tone(392, 380, .06, t, 2.2);
        tone(587, 570, .035, t + .02, 2.6);
    }
    H.chime = chime;

    // ---------------------------------------------------------------- atmosphere
    function ensureAtmosphere() {
        if (!isHaunted() || document.querySelector('.haunt-atmos') || document.querySelector('.haunt-scene')) {
            return;
        }
        var atmos = document.createElement('div');
        atmos.className = 'haunt-atmos';
        atmos.setAttribute('aria-hidden', 'true');
        ['fog', 'fog fog-2', 'wisp', 'wisp w2'].forEach(function (c) {
            var d = document.createElement('div');
            d.className = c;
            atmos.appendChild(d);
        });
        document.body.prepend(atmos);
    }

    function spawnBats(origin, count) {
        if (!isHaunted() || reduced.matches || document.hidden) {
            return;
        }
        var w = window.innerWidth;
        var h = window.innerHeight;
        var start = { x: w * (.7 + Math.random() * .2), y: 70 };
        if (origin && origin.getBoundingClientRect) {
            var r = origin.getBoundingClientRect();
            start = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
        }
        for (var i = 0; i < count; i++) {
            var bat = document.createElement('span');
            bat.className = 'haunt-bat';
            bat.appendChild(svgUse('i-bat'));
            var size = 14 + Math.random() * 16;
            var toLeft = Math.random() < .65;
            var endX = toLeft ? -80 : w + 80;
            var endY = -40 - Math.random() * 120 + Math.random() * h * .35;
            var c1x = start.x + (Math.random() - .5) * w * .5;
            var c1y = start.y + 60 + Math.random() * h * .3;
            var c2x = (start.x + endX) / 2 + (Math.random() - .5) * w * .3;
            var c2y = Math.random() * h * .25;
            var path = 'M' + (start.x - size / 2).toFixed(0) + ' ' + (start.y - size / 2).toFixed(0)
                + ' C' + c1x.toFixed(0) + ' ' + c1y.toFixed(0) + ', ' + c2x.toFixed(0) + ' ' + c2y.toFixed(0) + ', ' + endX.toFixed(0) + ' ' + endY.toFixed(0);
            bat.style.setProperty('--path', "path('" + path + "')");
            bat.style.setProperty('--s', size.toFixed(0) + 'px');
            bat.style.setProperty('--d', (2.8 + Math.random() * 2.6).toFixed(2) + 's');
            bat.style.setProperty('--delay', (i * .09).toFixed(2) + 's');
            bat.addEventListener('animationend', function (e) {
                if (e.target === this) {
                    this.remove();
                }
            });
            document.body.appendChild(bat);
        }
    }
    H.bats = spawnBats;

    function ambientBats() {
        setTimeout(function () {
            if (isHaunted() && !document.hidden && !document.querySelector('.spirit-board.is-open')) {
                spawnBats(null, 1 + Math.floor(Math.random() * 3));
            }
            ambientBats();
        }, 38000 + Math.random() * 42000);
    }

    function boo() {
        if (!isHaunted()) {
            return;
        }
        H.say('Boo!', 2200);
        var companion = document.querySelector('.kiro-companion');
        if (companion) {
            companion.classList.add('is-booing');
            setTimeout(function () { companion.classList.remove('is-booing'); }, 900);
        }
        if (reduced.matches) {
            return;
        }
        for (var i = 0; i < 14; i++) {
            var g = svgUse('g-kiro', 'boo-ghost', '0 0 64 72');
            g.style.setProperty('--x', (Math.random() * 92).toFixed(1) + 'vw');
            g.style.setProperty('--s', (34 + Math.random() * 40).toFixed(0) + 'px');
            g.style.setProperty('--d', (3 + Math.random() * 2.4).toFixed(2) + 's');
            g.style.setProperty('--delay', (Math.random() * .9).toFixed(2) + 's');
            g.style.setProperty('--dx1', ((Math.random() - .5) * 220).toFixed(0) + 'px');
            g.style.setProperty('--dx2', ((Math.random() - .5) * 360).toFixed(0) + 'px');
            g.style.setProperty('--r1', ((Math.random() - .5) * 30).toFixed(0) + 'deg');
            g.style.setProperty('--r2', ((Math.random() - .5) * 50).toFixed(0) + 'deg');
            g.addEventListener('animationend', function () { this.remove(); });
            document.body.appendChild(g);
        }
        spawnBats(null, 4);
    }
    H.boo = boo;

    // ---------------------------------------------------------------- pointer: fog parallax, lantern on cards, tilt, eyes
    var pointer = { x: -1, y: -1, target: null, frame: 0 };
    var tilted = null;

    function onPointerFrame() {
        pointer.frame = 0;
        if (!isHaunted()) {
            return;
        }
        var atmos = document.querySelector('.haunt-atmos');
        if (atmos && !reduced.matches) {
            atmos.style.setProperty('--fx', ((pointer.x / window.innerWidth - .5) * 2).toFixed(3));
        }
        var t = pointer.target;
        var card = t && t.closest ? t.closest('.card') : null;
        if (card) {
            var r = card.getBoundingClientRect();
            card.style.setProperty('--lx', (pointer.x - r.left).toFixed(0) + 'px');
            card.style.setProperty('--ly', (pointer.y - r.top).toFixed(0) + 'px');
        }
        var mc = t && t.closest ? t.closest('.machine-card') : null;
        if (tilted && tilted !== mc) {
            untilt(tilted);
        }
        if (mc && finePointer.matches && !reduced.matches) {
            var b = mc.getBoundingClientRect();
            var px = (pointer.x - b.left) / b.width;
            var py = (pointer.y - b.top) / b.height;
            mc.classList.add('is-tilting');
            mc.style.setProperty('--ry', ((px - .5) * 7).toFixed(2) + 'deg');
            mc.style.setProperty('--rx', ((.5 - py) * 5).toFixed(2) + 'deg');
            tilted = mc;
        }
        followEyes();
    }

    function untilt(card) {
        card.classList.remove('is-tilting');
        card.style.removeProperty('--rx');
        card.style.removeProperty('--ry');
        if (tilted === card) {
            tilted = null;
        }
    }

    function followEyes() {
        if (pointer.x < 0) {
            return;
        }
        document.querySelectorAll('.ghost-eyes').forEach(function (svg) {
            var eyes = svg.querySelector('.ge-eyes');
            var r = svg.getBoundingClientRect();
            if (!eyes || !r.width) {
                return;
            }
            var dx = pointer.x - (r.left + r.width / 2);
            var dy = pointer.y - (r.top + r.height * .42);
            var d = Math.hypot(dx, dy) || 1;
            var k = Math.min(1, d / 200);
            eyes.setAttribute('transform', 'translate(' + (dx / d * 2.6 * k).toFixed(2) + ' ' + (dy / d * 2.2 * k).toFixed(2) + ')');
        });
    }
    H.followEyes = followEyes;

    document.addEventListener('pointermove', function (e) {
        pointer.x = e.clientX;
        pointer.y = e.clientY;
        pointer.target = e.target;
        if (!pointer.frame) {
            pointer.frame = requestAnimationFrame(onPointerFrame);
        }
    }, { passive: true });
    document.addEventListener('pointerout', function (e) {
        if (tilted && (!e.relatedTarget || !tilted.contains(e.relatedTarget))) {
            untilt(tilted);
        }
    });

    // ---------------------------------------------------------------- keys: "boo"
    var typed = '';
    document.addEventListener('keydown', function (e) {
        var t = e.target;
        if (e.ctrlKey || e.metaKey || e.altKey || (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)))) {
            return;
        }
        typed = (typed + (e.key || '').toLowerCase()).slice(-3);
        if (typed === 'boo') {
            typed = '';
            boo();
        }
    });

    // ---------------------------------------------------------------- controls, print, start
    document.addEventListener('click', function (e) {
        var toggle = e.target.closest && e.target.closest('[data-costume-toggle]');
        if (toggle) {
            e.preventDefault();
            toggleCostume(toggle);
            return;
        }
        var ambience = e.target.closest && e.target.closest('[data-ambience]');
        if (ambience) {
            e.preventDefault();
            toggleAmbience();
        }
    });
    H.toggleCostume = toggleCostume;
    H.toggleAmbience = toggleAmbience;

    // Browsers only allow sound after a click or key press, so a saved "sound on" starts with the first one.
    if (store('bas.ambience') === 'on') {
        var wake = function () {
            document.removeEventListener('pointerdown', wake, true);
            document.removeEventListener('keydown', wake, true);
            if (isHaunted() && store('bas.ambience') === 'on') {
                startAmbience();
            }
        };
        document.addEventListener('pointerdown', wake, true);
        document.addEventListener('keydown', wake, true);
    }

    // Reports and investigations are printed on paper: print them in daylight.
    var printedFrom = null;
    window.addEventListener('beforeprint', function () {
        if (isHaunted()) {
            printedFrom = 'haunted';
            applyCostume('daylight', false);
        }
    });
    window.addEventListener('afterprint', function () {
        if (printedFrom) {
            printedFrom = null;
            applyCostume('haunted', false);
        }
    });

    syncControls();
    ensureAtmosphere();
    favicon();
    ambientBats();
})();
