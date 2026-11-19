// Failure-risk prediction history with the risk-level thresholds (25 / 50 / 75 %).
// In the haunted costume the four risk levels are shaded bands and each prediction point takes its level's colour.
(function () {
    const canvas = document.getElementById('prediction-chart');
    if (!canvas || typeof Chart === 'undefined') {
        return;
    }
    let chart = null;
    let data = null;

    function haunted() {
        return document.documentElement.getAttribute('data-costume') === 'haunted';
    }

    function line(label, value, n, color) {
        return { label: label, data: new Array(n).fill(value), borderColor: color, borderDash: [6, 4], borderWidth: 1, pointRadius: 0 };
    }

    function levelColour(p) {
        return p >= 0.75 ? '#ff4d5e' : p >= 0.5 ? '#ff7e24' : p >= 0.25 ? '#ffb84d' : '#5be3a8';
    }

    const riskBands = {
        id: 'riskBands',
        beforeDatasetsDraw: function (c, args, opts) {
            const a = c.chartArea;
            const y = c.scales.y;
            if (!opts || !opts.enabled || !a || !y) {
                return;
            }
            const bands = [[0, 0.25, 'rgba(91,227,168,.05)'], [0.25, 0.5, 'rgba(255,184,77,.055)'],
                [0.5, 0.75, 'rgba(255,126,36,.075)'], [0.75, 1, 'rgba(255,77,94,.11)']];
            c.ctx.save();
            bands.forEach(function (b) {
                const top = y.getPixelForValue(b[1]);
                const bottom = y.getPixelForValue(b[0]);
                c.ctx.fillStyle = b[2];
                c.ctx.fillRect(a.left, top, a.right - a.left, bottom - top);
            });
            c.ctx.restore();
        }
    };

    function draw(d) {
        const n = d.labels.length;
        const h = haunted();
        const text = h ? 'rgba(205,210,222,.72)' : '#666';
        const grid = h ? 'rgba(148,163,184,.10)' : 'rgba(0,0,0,0.1)';
        const probability = {
            label: 'failure probability', data: d.probability, borderColor: '#0d6efd', backgroundColor: 'rgba(13,110,253,.08)',
            fill: true, tension: 0.2, pointRadius: 2
        };
        if (h) {
            probability.borderColor = '#c4b5fd';
            probability.backgroundColor = 'rgba(167,139,250,.12)';
            probability.pointRadius = 3;
            probability.pointBackgroundColor = function (ctx) { return levelColour(ctx.raw); };
            probability.pointBorderColor = 'rgba(0,0,0,.4)';
        }
        chart = new Chart(canvas, {
            type: 'line',
            plugins: [riskBands],
            data: {
                labels: d.labels,
                datasets: [
                    probability,
                    line('medium', 0.25, n, h ? '#ffb84d' : '#ffc107'), line('high', 0.5, n, h ? '#ff7e24' : '#fd7e14'),
                    line('critical', 0.75, n, h ? '#ff4d5e' : '#dc3545')
                ]
            },
            options: {
                animation: false, maintainAspectRatio: false,
                scales: { y: { min: 0, max: 1, ticks: { color: text, callback: function (v) { return Math.round(v * 100) + '%'; } }, grid: { color: grid } },
                          x: { ticks: { maxTicksLimit: 8, color: text }, grid: { color: grid } } },
                plugins: { legend: { labels: { boxWidth: 10, color: text } },
                           riskBands: { enabled: h },
                           tooltip: { backgroundColor: h ? 'rgba(16,14,26,.96)' : 'rgba(0,0,0,0.8)', borderColor: 'rgba(196,181,253,.45)', borderWidth: h ? 1 : 0,
                                      callbacks: { afterLabel: function (ctx) { return ctx.datasetIndex === 0 ? 'trigger: ' + d.trigger[ctx.dataIndex] : ''; } } } }
            }
        });
        const box = canvas.closest('.chart-box');
        if (box) {
            box.classList.add('is-loaded');
        }
    }

    fetch(canvas.dataset.url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
        .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
        .then(function (d) {
            data = d;
            draw(d);
        })
        .catch(function (e) {
            console.warn('prediction history failed', e);
            const box = canvas.closest('.chart-box');
            if (box && box.parentElement) {
                box.parentElement.classList.add('is-error');
            }
        });

    document.addEventListener('costumechange', function () {
        if (chart && data) {
            chart.destroy();
            draw(data);
        }
    });
})();
