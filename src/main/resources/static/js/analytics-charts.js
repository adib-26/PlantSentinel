// Failure-risk prediction history with the risk-level thresholds (25 / 50 / 75 %).
(function () {
    const canvas = document.getElementById('prediction-chart');
    if (!canvas || typeof Chart === 'undefined') {
        return;
    }
    function line(label, value, n, color) {
        return { label: label, data: new Array(n).fill(value), borderColor: color, borderDash: [6, 4], borderWidth: 1, pointRadius: 0 };
    }
    fetch(canvas.dataset.url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
        .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
        .then(function (d) {
            const n = d.labels.length;
            new Chart(canvas, {
                type: 'line',
                data: {
                    labels: d.labels,
                    datasets: [
                        { label: 'failure probability', data: d.probability, borderColor: '#0d6efd', backgroundColor: 'rgba(13,110,253,.08)',
                          fill: true, tension: 0.2, pointRadius: 2 },
                        line('medium', 0.25, n, '#ffc107'), line('high', 0.5, n, '#fd7e14'), line('critical', 0.75, n, '#dc3545')
                    ]
                },
                options: {
                    animation: false, maintainAspectRatio: false,
                    scales: { y: { min: 0, max: 1, ticks: { callback: function (v) { return Math.round(v * 100) + '%'; } } },
                              x: { ticks: { maxTicksLimit: 8 } } },
                    plugins: { legend: { labels: { boxWidth: 10 } },
                               tooltip: { callbacks: { afterLabel: function (ctx) { return ctx.datasetIndex === 0 ? 'trigger: ' + d.trigger[ctx.dataIndex] : ''; } } } }
                }
            });
        })
        .catch(function (e) { console.warn('prediction history failed', e); });
})();
