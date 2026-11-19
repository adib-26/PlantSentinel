// Draws one Chart.js line chart per telemetry channel and refreshes it every 15 seconds.
// Hovering one chart marks the same moment on every channel, so channels can be compared at a glance.
// In the haunted costume the area past the critical limit is shaded red, line segments over a limit change colour,
// and the reading line glows. The daylight costume keeps the original colours.
(function () {
    const container = document.getElementById('charts');
    if (!container || typeof Chart === 'undefined') {
        return;
    }
    const baseUrl = container.dataset.url;
    const resolution = document.getElementById('chart-resolution');
    let minutes = 120;
    const charts = {};
    let lastData = null;
    let hoverIndex = null;

    function haunted() {
        return document.documentElement.getAttribute('data-costume') === 'haunted';
    }

    function palette() {
        return haunted()
            ? { line: '#5eead4', fill: 'rgba(94,234,212,.10)', warn: '#ffb84d', crit: '#ff4d5e', grid: 'rgba(148,163,184,.10)',
                text: 'rgba(205,210,222,.72)', glow: 'rgba(94,234,212,.7)', cross: 'rgba(255,205,130,.9)',
                tipBg: 'rgba(16,14,26,.96)', tipBorder: 'rgba(255,184,77,.45)' }
            : { line: '#0d6efd', fill: 'rgba(13,110,253,.08)', warn: '#fd7e14', crit: '#dc3545', grid: 'rgba(0,0,0,0.1)',
                text: '#666', glow: null, cross: 'rgba(13,110,253,.45)', tipBg: 'rgba(0,0,0,0.8)', tipBorder: 'rgba(0,0,0,0)' };
    }

    function limitLine(label, value, length, color) {
        return {
            label: label, data: new Array(length).fill(value), borderColor: color, borderDash: [6, 4],
            borderWidth: 1, pointRadius: 0, fill: false
        };
    }

    function datasets(metric, data) {
        const p = palette();
        const values = data.series[metric];
        const limits = data.limits[metric];
        const main = {
            label: limits.label, data: values, borderColor: p.line, backgroundColor: p.fill,
            borderWidth: 1.5, pointRadius: 0, fill: true, tension: 0.2
        };
        if (haunted()) {
            main.borderWidth = 1.8;
            main.pointHoverBackgroundColor = '#fff7ea';
            main.pointHoverBorderColor = p.line;
            main.backgroundColor = function (ctx) {
                const area = ctx.chart.chartArea;
                if (!area) {
                    return p.fill;
                }
                const g = ctx.chart.ctx.createLinearGradient(0, area.top, 0, area.bottom);
                g.addColorStop(0, 'rgba(94,234,212,.24)');
                g.addColorStop(1, 'rgba(94,234,212,0)');
                return g;
            };
            main.segment = {
                borderColor: function (ctx) {
                    const hi = Math.max(ctx.p0.parsed.y, ctx.p1.parsed.y);
                    const lo = Math.min(ctx.p0.parsed.y, ctx.p1.parsed.y);
                    if (hi >= limits.critHigh) {
                        return p.crit;
                    }
                    if (hi >= limits.warnHigh || (limits.warnLow !== null && lo <= limits.warnLow)) {
                        return p.warn;
                    }
                    return undefined;
                }
            };
        }
        const sets = [main];
        sets.push(limitLine('warning', limits.warnHigh, values.length, p.warn));
        sets.push(limitLine('critical', limits.critHigh, values.length, p.crit));
        if (limits.warnLow !== null) {
            sets.push(limitLine('low warning', limits.warnLow, values.length, p.warn));
        }
        return sets;
    }

    // Shades the warning band amber and everything past the critical limit red (haunted costume only).
    const hauntZones = {
        id: 'hauntZones',
        beforeDatasetsDraw: function (chart, args, opts) {
            const a = chart.chartArea;
            const y = chart.scales.y;
            if (!opts || !opts.enabled || !a || !y) {
                return;
            }
            const ctx = chart.ctx;
            const width = a.right - a.left;
            const yc = y.getPixelForValue(opts.critHigh);
            const yw = y.getPixelForValue(opts.warnHigh);
            ctx.save();
            if (yc > a.top) {
                const bottom = Math.min(yc, a.bottom);
                const g = ctx.createLinearGradient(0, a.top, 0, bottom);
                g.addColorStop(0, 'rgba(255,77,94,.24)');
                g.addColorStop(1, 'rgba(255,77,94,.07)');
                ctx.fillStyle = g;
                ctx.fillRect(a.left, a.top, width, bottom - a.top);
            }
            if (yw > a.top && yw > yc) {
                const top = Math.max(yc, a.top);
                ctx.fillStyle = 'rgba(255,184,77,.07)';
                ctx.fillRect(a.left, top, width, Math.min(yw, a.bottom) - top);
            }
            if (opts.warnLow !== null && opts.warnLow !== undefined) {
                const yl = y.getPixelForValue(opts.warnLow);
                if (yl < a.bottom) {
                    const top = Math.max(yl, a.top);
                    ctx.fillStyle = 'rgba(255,184,77,.07)';
                    ctx.fillRect(a.left, top, width, a.bottom - top);
                }
            }
            ctx.restore();
        }
    };

    // A soft glow under the reading line.
    const hauntGlow = {
        id: 'hauntGlow',
        beforeDatasetDraw: function (chart, args, opts) {
            if (opts && opts.color && args.index === 0) {
                chart.ctx.save();
                chart.ctx.shadowColor = opts.color;
                chart.ctx.shadowBlur = 10;
            }
        },
        afterDatasetDraw: function (chart, args, opts) {
            if (opts && opts.color && args.index === 0) {
                chart.ctx.restore();
            }
        }
    };

    // The same moment on every channel: a vertical line and a tooltip on each chart.
    const spiritLine = {
        id: 'spiritLine',
        afterDatasetsDraw: function (chart) {
            const x = chart.scales.x;
            const a = chart.chartArea;
            if (hoverIndex === null || !x || !a || hoverIndex >= chart.data.labels.length) {
                return;
            }
            const px = x.getPixelForValue(hoverIndex);
            if (px < a.left || px > a.right) {
                return;
            }
            const ctx = chart.ctx;
            ctx.save();
            ctx.strokeStyle = palette().cross;
            ctx.lineWidth = 1;
            ctx.setLineDash([3, 3]);
            if (haunted()) {
                ctx.shadowColor = 'rgba(255,170,60,.9)';
                ctx.shadowBlur = 6;
            }
            ctx.beginPath();
            ctx.moveTo(px, a.top);
            ctx.lineTo(px, a.bottom);
            ctx.stroke();
            ctx.restore();
        },
        afterEvent: function (chart, args) {
            const e = args.event;
            if (e.type === 'mouseout') {
                sync(null, chart);
            } else if (e.type === 'mousemove') {
                const found = chart.getElementsAtEventForMode(e, 'index', { intersect: false }, false);
                sync(found.length ? found[0].index : null, chart);
            }
        }
    };

    function sync(index, source) {
        if (index === hoverIndex) {
            return;
        }
        hoverIndex = index;
        Object.keys(charts).forEach(function (metric) {
            const c = charts[metric];
            if (c === source) {
                return;
            }
            const point = index === null ? null : c.getDatasetMeta(0).data[index];
            if (point) {
                c.setActiveElements([{ datasetIndex: 0, index: index }]);
                c.tooltip.setActiveElements([{ datasetIndex: 0, index: index }], { x: point.x, y: point.y });
            } else {
                c.setActiveElements([]);
                c.tooltip.setActiveElements([], { x: 0, y: 0 });
            }
            c.update('none');
        });
    }

    function zones(limits) {
        return { enabled: haunted(), warnHigh: limits.warnHigh, critHigh: limits.critHigh, warnLow: limits.warnLow };
    }

    function options(limits) {
        const p = palette();
        return {
            animation: false, maintainAspectRatio: false, responsive: true,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { display: true, labels: { boxWidth: 10, color: p.text, filter: function (item) { return item.datasetIndex === 0; } } },
                tooltip: { backgroundColor: p.tipBg, borderColor: p.tipBorder, borderWidth: haunted() ? 1 : 0 },
                hauntZones: zones(limits),
                hauntGlow: { color: p.glow },
                spiritLine: {}
            },
            scales: {
                x: { ticks: { maxTicksLimit: 6, color: p.text }, grid: { color: p.grid } },
                y: { ticks: { color: p.text }, grid: { color: p.grid } }
            }
        };
    }

    function render(data) {
        lastData = data;
        container.classList.remove('is-error');
        if (resolution) {
            resolution.textContent = data.resolution;
        }
        container.querySelectorAll('canvas[data-metric]').forEach(function (canvas) {
            const metric = canvas.dataset.metric;
            const sets = datasets(metric, data);
            const limits = data.limits[metric];
            if (charts[metric]) {
                charts[metric].data.labels = data.labels;
                charts[metric].data.datasets = sets;
                charts[metric].options.plugins.hauntZones = zones(limits);
                charts[metric].update('none');
                return;
            }
            charts[metric] = new Chart(canvas, {
                type: 'line',
                data: { labels: data.labels, datasets: sets },
                plugins: [hauntZones, hauntGlow, spiritLine],
                options: options(limits)
            });
            const box = canvas.closest('.chart-box');
            if (box) {
                box.classList.add('is-loaded');
            }
        });
    }

    function load() {
        fetch(baseUrl + '?minutes=' + minutes, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(render)
            .catch(function (e) {
                console.warn('telemetry refresh failed', e);
                if (!lastData) {
                    container.classList.add('is-error');
                }
            });
    }

    document.querySelectorAll('#chart-window [data-minutes]').forEach(function (button) {
        button.addEventListener('click', function () {
            document.querySelectorAll('#chart-window [data-minutes]').forEach(function (b) { b.classList.remove('active'); });
            button.classList.add('active');
            minutes = parseInt(button.dataset.minutes, 10);
            hoverIndex = null;
            load();
        });
    });

    // A new costume needs new colours: redraw from the last readings.
    document.addEventListener('costumechange', function () {
        Object.keys(charts).forEach(function (metric) {
            charts[metric].destroy();
            delete charts[metric];
        });
        hoverIndex = null;
        if (lastData) {
            render(lastData);
        }
    });

    load();
    setInterval(load, 15000);
})();
