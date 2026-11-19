// Kiro assistant: while Kiro is answering, shows the tool calls the server has logged and the text streamed so far,
// then reloads the page once the reply is stored. All text is set with textContent, never as HTML.
(function () {
    const form = document.getElementById('chat-form');
    if (form) {
        form.addEventListener('submit', function () {
            const button = form.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
                button.textContent = 'Sending…';
            }
        });
    }

    const run = document.getElementById('kiro-run');
    if (!run) {
        return;
    }
    const steps = document.getElementById('kiro-steps');
    const text = document.getElementById('kiro-text');
    const elapsed = document.getElementById('kiro-elapsed');

    function render(d) {
        elapsed.textContent = '(' + d.elapsedSeconds + ' s)';
        steps.replaceChildren();
        d.steps.forEach(function (s) {
            const li = document.createElement('li');
            const code = document.createElement('code');
            code.textContent = s.tool;
            li.appendChild(code);
            li.appendChild(document.createTextNode(' ' + (s.machineCode || '') + ' · ' + s.status
                + (s.durationMs !== null ? ' · ' + s.durationMs + ' ms' : '')));
            steps.appendChild(li);
        });
        if (d.text) {
            text.textContent = d.text;
        }
    }

    function poll() {
        fetch(run.dataset.url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
            .then(function (d) {
                render(d);
                if (d.finished) {
                    window.location.reload();
                } else {
                    setTimeout(poll, 2000);
                }
            })
            .catch(function (e) {
                console.warn('Kiro status failed', e);
                setTimeout(poll, 5000);
            });
    }

    poll();
})();
