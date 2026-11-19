// Runs in <head> before the page is drawn: applies the saved costume so the page never flashes the wrong theme.
// "haunted" (the default) switches Bootstrap to dark mode and turns on haunted.css; "daylight" is the original look.
(function () {
    var costume = 'haunted';
    try {
        costume = localStorage.getItem('bas.costume') === 'daylight' ? 'daylight' : 'haunted';
    } catch (e) {
        // storage blocked: keep the default
    }
    var root = document.documentElement;
    root.setAttribute('data-costume', costume);
    if (costume === 'haunted') {
        root.setAttribute('data-bs-theme', 'dark');
    }
})();
