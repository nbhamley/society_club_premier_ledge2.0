/* Public-site motion: scroll-reveal for cards and sections, and count-up for numbers marked data-count.
   Everything is progressive: without JS (or with reduced motion) the page renders fully static. */
(function () {
    'use strict';
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce || !('IntersectionObserver' in window)) {
        return;
    }
    document.documentElement.classList.add('fx');

    var targets = document.querySelectorAll('main .section, main .card, main .society-hero, main .stat');
    var io = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
            if (!entry.isIntersecting) {
                return;
            }
            entry.target.classList.add('in');
            io.unobserve(entry.target);
        });
    }, {rootMargin: '0px 0px -40px 0px', threshold: 0.05});

    targets.forEach(function (el) {
        // Stagger siblings inside a grid so cards arrive one after another.
        var parent = el.parentElement;
        if (parent && parent.classList.contains('grid')) {
            var index = Array.prototype.indexOf.call(parent.children, el);
            el.style.transitionDelay = Math.min(index, 8) * 70 + 'ms';
        }
        el.classList.add('reveal');
        io.observe(el);
    });

    document.querySelectorAll('[data-count]').forEach(function (el) {
        var end = parseInt(el.getAttribute('data-count'), 10);
        if (isNaN(end) || end <= 0) {
            return;
        }
        var start = null;
        var duration = 1200;
        function step(ts) {
            if (start === null) {
                start = ts;
            }
            var t = Math.min((ts - start) / duration, 1);
            el.textContent = Math.round(end * (1 - Math.pow(1 - t, 3)));
            if (t < 1) {
                requestAnimationFrame(step);
            }
        }
        el.textContent = '0';
        requestAnimationFrame(step);
    });
})();
