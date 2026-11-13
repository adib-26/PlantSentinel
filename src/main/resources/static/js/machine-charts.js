// Draws one Chart.js line chart per telemetry channel and refreshes it every 15 seconds.
(function () {
    const container = document.getElementById('charts');
    if (!container || typeof Chart === 'undefined') {
        return;
    }
    const baseUrl = container.dataset.url;
    const resolution = document.getElementById('chart-resolution');
    let minutes = 120;
    const charts = {};

    function limitLine(label, value, length, color) {
        return {
            label: label, data: new Array(length).fill(value), borderColor: color, borderDash: [6, 4],
            borderWidth: 1, pointRadius: 0, fill: false
        };
    }

    function datasets(metric, data) {
        const values = data.series[metric];
        const limits = data.limits[metric];
        const sets = [{
            label: limits.label, data: values, borderColor: '#0d6efd', backgroundColor: 'rgba(13,110,253,.08)',
            borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.2
        }];
        sets.push(limitLine('warning', limits.warnHigh, values.length, '#fd7e14'));
        sets.push(limitLine('critical', limits.critHigh, values.length, '#dc3545'));
        if (limits.warnLow !== null) {
            sets.push(limitLine('low warning', limits.warnLow, values.length, '#fd7e14'));
        }
        return sets;
    }

    function render(data) {
        if (resolution) {
            resolution.textContent = data.resolution;
        }
        container.querySelectorAll('canvas[data-metric]').forEach(function (canvas) {
            const metric = canvas.dataset.metric;
            const sets = datasets(metric, data);
            if (charts[metric]) {
                charts[metric].data.labels = data.labels;
                charts[metric].data.datasets = sets;
                charts[metric].update('none');
                return;
            }
            charts[metric] = new Chart(canvas, {
                type: 'line',
                data: { labels: data.labels, datasets: sets },
                options: {
                    animation: false, maintainAspectRatio: false, responsive: true,
                    interaction: { mode: 'index', intersect: false },
                    plugins: {
                        legend: { display: true, labels: { boxWidth: 10, filter: function (item) { return item.datasetIndex === 0; } } }
                    },
                    scales: { x: { ticks: { maxTicksLimit: 6 } } }
                }
            });
        });
    }

    function load() {
        fetch(baseUrl + '?minutes=' + minutes, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(render)
            .catch(function (e) { console.warn('telemetry refresh failed', e); });
    }

    document.querySelectorAll('#chart-window [data-minutes]').forEach(function (button) {
        button.addEventListener('click', function () {
            document.querySelectorAll('#chart-window [data-minutes]').forEach(function (b) { b.classList.remove('active'); });
            button.classList.add('active');
            minutes = parseInt(button.dataset.minutes, 10);
            load();
        });
    });

    load();
    setInterval(load, 15000);
})();
