/*
 * SCPL front-end behaviour. Loaded (deferred) on every page; each feature binds only when its data-* hook exists.
 * No inline script or handler exists anywhere in the templates (Content-Security-Policy), so everything lives here.
 * Dynamic text is always written with textContent, never innerHTML.
 */
(function () {
    'use strict';

    const doc = document;
    doc.documentElement.classList.add('js');

    const $ = (sel, root) => (root || doc).querySelector(sel);
    const $$ = (sel, root) => Array.prototype.slice.call((root || doc).querySelectorAll(sel));

    // ------------------------------------------------------------------ shared helpers

    function meta(name) {
        const m = $('meta[name="' + name + '"]');
        return m ? m.getAttribute('content') : null;
    }

    /** "/ctx" when the app is deployed under a context path, else "". Derived from this script's own URL. */
    function contextPath() {
        const s = $('script[src*="/js/admin.js"]');
        return s ? new URL(s.src, location.href).pathname.replace(/\/js\/admin\.js$/, '') : '';
    }

    function withContext(url) {
        return url && url.charAt(0) === '/' && url.charAt(1) !== '/' ? contextPath() + url : url;
    }

    /**
     * fetch() for the JSON endpoints. X-Requested-With makes GlobalExceptionHandler answer with JSON ({"error": "..."})
     * instead of redirects; the CSRF header is sent on every non-GET request.
     */
    function fetchJson(url, options) {
        const opts = options || {};
        const method = opts.method || 'GET';
        const headers = {'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json'};
        const token = meta('_csrf');
        const header = meta('_csrf_header');
        if (method !== 'GET' && token && header) {
            headers[header] = token;
        }
        if (opts.body) {
            headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
        }
        return fetch(url, {method: method, headers: headers, body: opts.body, credentials: 'same-origin', redirect: 'manual'})
            .then((res) => {
                if (res.type === 'opaqueredirect') {
                    throw new Error('Your session has ended. Please sign in again.');
                }
                const isJson = (res.headers.get('Content-Type') || '').indexOf('json') >= 0;
                return (isJson ? res.json() : Promise.resolve(null)).then((body) => {
                    if (!res.ok) {
                        throw new Error((body && body.error) || 'Something went wrong. Please try again.');
                    }
                    return body;
                });
            });
    }

    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            return navigator.clipboard.writeText(text).then(() => true, () => false);
        }
        const area = doc.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        doc.body.appendChild(area);
        area.select();
        let ok = false;
        try {
            ok = doc.execCommand('copy');
        } catch (e) {
            ok = false;
        }
        doc.body.removeChild(area);
        return Promise.resolve(ok);
    }

    function formatBytes(n) {
        if (n < 1024) {
            return n + ' B';
        }
        return n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
    }

    // ------------------------------------------------------------------ tabs

    // Notification links such as /society-admin#import point at pages that are not console tabs.
    const STANDALONE = {import: '/import', approvals: '/approvals', backup: '/backup', billing: '/billing',
        contacts: '/contacts', vendors: '/vendors', bills: '/bills'};

    function initTabs() {
        const nav = $('[data-tabs]');
        if (!nav) {
            return;
        }
        const links = $$('[data-tab]', nav);
        const panels = $$('[data-panel]');
        const names = panels.map((p) => p.getAttribute('data-panel'));
        if (!names.length) {
            return;
        }

        function show(name, updateHash) {
            const target = names.indexOf(name) >= 0 ? name : names[0];
            panels.forEach((p) => {
                p.hidden = p.getAttribute('data-panel') !== target;
            });
            links.forEach((a) => {
                const on = a.getAttribute('data-tab') === target;
                a.classList.toggle('active', on);
                if (on) {
                    a.setAttribute('aria-current', 'page');
                } else {
                    a.removeAttribute('aria-current');
                }
            });
            // replaceState (not location.hash) so switching tabs does not jump the page or fill the back button.
            if (updateHash && history.replaceState) {
                history.replaceState(null, '', '#' + target);
            }
        }

        function fromHash() {
            const h = decodeURIComponent(location.hash.replace(/^#/, ''));
            if (names.indexOf(h) < 0 && STANDALONE[h] && /\/society-admin\/?$/.test(location.pathname)) {
                location.replace(contextPath() + '/society-admin' + STANDALONE[h]);
                return;
            }
            show(h, false);
        }

        doc.addEventListener('click', (e) => {
            const a = e.target.closest ? e.target.closest('[data-tab],[data-tab-link]') : null;
            if (!a) {
                return;
            }
            e.preventDefault();
            show(a.getAttribute('data-tab') || a.getAttribute('data-tab-link'), true);
        });
        window.addEventListener('hashchange', fromHash);
        fromHash();
    }

    // ------------------------------------------------------------------ select-all, confirm, autosubmit

    function groupItems(group) {
        return $$('[data-select-group]').filter((c) => c.getAttribute('data-select-group') === group);
    }

    function syncSelectAll(group) {
        const items = groupItems(group).filter((c) => !c.disabled);
        const checked = items.filter((c) => c.checked).length;
        $$('[data-select-all]').filter((a) => a.getAttribute('data-select-all') === group).forEach((a) => {
            a.checked = items.length > 0 && checked === items.length;
            a.indeterminate = checked > 0 && checked < items.length;
        });
    }

    function initFormHelpers() {
        doc.addEventListener('change', (e) => {
            const t = e.target;
            if (!t.matches) {
                return;
            }
            if (t.matches('[data-select-all]')) {
                const group = t.getAttribute('data-select-all');
                groupItems(group).forEach((c) => {
                    if (!c.disabled) {
                        c.checked = t.checked;
                    }
                });
                syncSelectAll(group);
            } else if (t.matches('[data-select-group]')) {
                syncSelectAll(t.getAttribute('data-select-group'));
            } else if (t.matches('select[data-autosubmit]') && t.form) {
                if (t.form.requestSubmit) {
                    t.form.requestSubmit();
                } else {
                    t.form.submit();
                }
            } else if (t.matches('input[type="file"]') && t.form && t.form.hasAttribute('data-upload-form')) {
                showUploadError(t.form, uploadProblem(t.form));
            }
        });

        // Buttons carry their own prompt (the bulk "Reject selected" button); forms carry theirs on the <form>.
        doc.addEventListener('click', (e) => {
            const b = e.target.closest ? e.target.closest('button[data-confirm],input[data-confirm]') : null;
            if (b && !window.confirm(b.getAttribute('data-confirm'))) {
                e.preventDefault();
            }
        });

        doc.addEventListener('submit', (e) => {
            const f = e.target;
            if (f.matches && f.matches('form[data-upload-form]')) {
                const problem = uploadProblem(f);
                showUploadError(f, problem);
                if (problem) {
                    e.preventDefault();
                    return;
                }
            }
            if (f.matches && f.matches('form[data-confirm]') && !window.confirm(f.getAttribute('data-confirm'))) {
                e.preventDefault();
            }
        });
    }

    // ------------------------------------------------------------------ upload size check (§9: clear client-side error)

    function uploadProblem(form) {
        const max = parseInt(form.getAttribute('data-max-bytes'), 10);
        if (!(max > 0)) {
            return null;
        }
        const inputs = $$('input[type="file"]', form);
        for (let i = 0; i < inputs.length; i++) {
            const files = inputs[i].files ? Array.prototype.slice.call(inputs[i].files) : [];
            for (let j = 0; j < files.length; j++) {
                if (files[j].size > max) {
                    return '"' + files[j].name + '" is ' + formatBytes(files[j].size) + '. The limit is ' + formatBytes(max) + '.';
                }
            }
        }
        return null;
    }

    function showUploadError(form, message) {
        const box = $('[data-role="upload-error"]', form);
        if (box) {
            box.textContent = message || '';
            box.hidden = !message;
        }
    }

    // ------------------------------------------------------------------ copy button (temporary password)

    function initCopy() {
        doc.addEventListener('click', (e) => {
            const b = e.target.closest ? e.target.closest('[data-copy-button]') : null;
            if (!b) {
                return;
            }
            const box = b.parentElement;
            const src = box ? $('[data-copy-source]', box) : null;
            if (!src) {
                return;
            }
            const original = b.textContent;
            copyText(src.textContent.trim()).then((ok) => {
                b.textContent = ok ? 'Copied' : 'Copy failed';
                setTimeout(() => {
                    b.textContent = original;
                }, 2000);
            });
        });
    }

    // ------------------------------------------------------------------ auto-refresh (live auction page)

    function initAutoRefresh() {
        const main = $('[data-autorefresh]');
        const seconds = main ? parseInt(main.getAttribute('data-autorefresh'), 10) : 0;
        if (!(seconds > 0)) {
            return;
        }
        const tick = () => {
            const a = doc.activeElement;
            const editing = a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName);
            // Never reload under someone who has typed a bid but not submitted it.
            const dirty = $$('input[type="text"],input[type="number"]', main).some((i) => i.value && i.value !== i.defaultValue);
            if (!doc.hidden && !editing && !dirty) {
                location.reload();
            } else {
                setTimeout(tick, seconds * 1000);
            }
        };
        setTimeout(tick, seconds * 1000);
    }

    // ------------------------------------------------------------------ budget chart

    const fallbackCharts = [];

    function cssVar(name, fallback) {
        return getComputedStyle(doc.documentElement).getPropertyValue(name).trim() || fallback;
    }

    /** Horizontal bars on a plain canvas: used whenever Chart.js is not available. */
    function drawBars(canvas, rows) {
        const dpr = window.devicePixelRatio || 1;
        const width = (canvas.parentElement && canvas.parentElement.clientWidth) || 600;
        const rowH = 26;
        const pad = 8;
        const height = Math.max(60, rows.length * rowH + pad * 2);
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
        canvas.style.width = width + 'px';
        canvas.style.height = height + 'px';
        const ctx = canvas.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, width, height);
        ctx.font = '13px system-ui, sans-serif';
        ctx.textBaseline = 'middle';
        const text = cssVar('--text', '#1d2330');
        const muted = cssVar('--muted', '#5d6678');
        const accent = cssVar('--accent', '#1565c0');
        const max = rows.reduce((m, r) => Math.max(m, r.value), 0);
        if (max <= 0) {
            ctx.fillStyle = muted;
            ctx.fillText('No bills for this selection yet.', pad, height / 2);
            return;
        }
        const labelW = Math.min(180, rows.reduce((m, r) => Math.max(m, ctx.measureText(r.label).width), 0) + 12);
        const barMax = Math.max(10, width - labelW - 110 - pad * 2);
        rows.forEach((r, i) => {
            const y = pad + i * rowH + rowH / 2;
            const w = r.value / max * barMax;
            ctx.fillStyle = text;
            ctx.fillText(r.label, pad, y);
            ctx.fillStyle = accent;
            ctx.fillRect(pad + labelW, y - 8, r.value > 0 ? Math.max(w, 2) : 0, 16);
            ctx.fillStyle = text;
            ctx.fillText('\u20B9' + Math.round(r.value).toLocaleString('en-IN'), pad + labelW + w + 6, y);
        });
    }

    function initCharts() {
        $$('canvas[data-chart]').forEach((canvas) => {
            let source = null;
            try {
                source = $(canvas.getAttribute('data-chart-source'));
            } catch (e) {
                source = null;
            }
            if (!source) {
                return;
            }
            const rows = $$('tr[data-label]', source)
                .map((tr) => ({label: tr.getAttribute('data-label'), value: parseFloat(tr.getAttribute('data-value'))}))
                .filter((r) => isFinite(r.value));
            const hasData = rows.some((r) => r.value > 0);
            if (window.Chart && hasData) {
                window.Chart.defaults.color = cssVar('--text', '#1d2330');
                new window.Chart(canvas, {
                    type: 'bar',
                    data: {labels: rows.map((r) => r.label), datasets: [{label: 'Billed (\u20B9)',
                            data: rows.map((r) => r.value), backgroundColor: cssVar('--accent', '#1565c0')}]},
                    options: {responsive: true, plugins: {legend: {display: false}}, scales: {y: {beginAtZero: true}}}
                });
            } else {
                drawBars(canvas, rows);
                fallbackCharts.push({canvas: canvas, rows: rows});
            }
        });
        if (fallbackCharts.length) {
            let timer = null;
            window.addEventListener('resize', () => {
                clearTimeout(timer);
                timer = setTimeout(() => fallbackCharts.forEach((c) => drawBars(c.canvas, c.rows)), 150);
            });
        }
    }

    // ------------------------------------------------------------------ share row (§13)

    const OPEN_IN_NEW_TAB = ['WHATSAPP', 'TWITTER_X', 'LINKEDIN'];

    function initShare() {
        $$('.share-row[data-share-type]').forEach((row) => {
            const type = encodeURIComponent(row.getAttribute('data-share-type'));
            const id = encodeURIComponent(row.getAttribute('data-share-id'));
            const dlg = $('[data-role="share-dialog"]', row);
            if (!dlg) {
                return;
            }
            const confirmBox = $('[data-role="share-confirm"]', dlg);
            const caption = $('[data-role="share-caption"]', dlg);
            const errorBox = $('[data-role="share-error"]', dlg);
            const modalActions = $('[data-role="share-modal-actions"]', dlg);
            const continueBtn = $('[data-role="share-continue"]', dlg);
            const cancelBtn = $('[data-role="share-cancel"]', dlg);
            const copyCaption = $('[data-role="share-copy-caption"]', dlg);
            const downloadImage = $('[data-role="share-download-image"]', dlg);
            const openApp = $('[data-role="share-open-app"]', dlg);
            let platform = null;
            let result = null;

            function showError(message) {
                errorBox.textContent = message;
                errorBox.hidden = false;
                continueBtn.hidden = true;
            }

            function open(p) {
                platform = p;
                result = null;
                errorBox.hidden = true;
                errorBox.textContent = '';
                modalActions.hidden = true;
                caption.value = '';
                caption.disabled = true;
                confirmBox.textContent = 'Loading...';
                continueBtn.hidden = false;
                continueBtn.disabled = true;
                cancelBtn.textContent = 'Cancel';
                dlg.hidden = false;
                fetchJson(contextPath() + '/share/' + type + '/' + id + '/preview?platform=' + encodeURIComponent(p))
                    .then((preview) => {
                        confirmBox.textContent = preview.confirmation;
                        caption.value = preview.caption || '';
                        caption.disabled = false;
                        continueBtn.disabled = false;
                        continueBtn.focus();
                    })
                    .catch((err) => {
                        confirmBox.textContent = '';
                        showError(err.message);
                    });
            }

            function close() {
                dlg.hidden = true;
            }

            function finish(r) {
                result = r;
                caption.disabled = true;
                continueBtn.hidden = true;
                cancelBtn.textContent = 'Close';
                if (platform === 'COPY_LINK') {
                    copyText(r.shareUrl).then((ok) => {
                        confirmBox.textContent = ok ? 'Link copied to the clipboard: ' + r.shareUrl : 'Copy this link: ' + r.shareUrl;
                    });
                } else if (r.modal) {
                    // Instagram and Facebook cannot take prefilled text, so the person finishes in the app.
                    confirmBox.textContent = 'Saved. ' + r.platformLabel + ' does not accept prefilled posts: copy the caption, '
                        + 'save the image if there is one, then open the app.';
                    downloadImage.hidden = !r.imageUrl;
                    openApp.href = r.outboundUrl;
                    modalActions.hidden = false;
                } else {
                    confirmBox.textContent = 'Shared. The post link is ' + r.shareUrl;
                }
            }

            row.addEventListener('click', (e) => {
                const b = e.target.closest ? e.target.closest('button[data-platform]') : null;
                if (b) {
                    open(b.getAttribute('data-platform'));
                }
            });

            continueBtn.addEventListener('click', () => {
                if (!platform) {
                    return;
                }
                continueBtn.disabled = true;
                // Opened inside the click so popup blockers allow it; pointed at the real URL once the server answers.
                const popup = OPEN_IN_NEW_TAB.indexOf(platform) >= 0 ? window.open('', '_blank') : null;
                const body = new URLSearchParams({platform: platform, caption: caption.value}).toString();
                fetchJson(contextPath() + '/share/' + type + '/' + id, {method: 'POST', body: body})
                    .then((r) => {
                        if (popup) {
                            if (r.outboundUrl && /^https:\/\//.test(r.outboundUrl)) {
                                try {
                                    popup.opener = null;
                                } catch (ignore) { /* best effort */ }
                                popup.location.href = r.outboundUrl;
                            } else {
                                popup.close();
                            }
                        }
                        finish(r);
                    })
                    .catch((err) => {
                        if (popup) {
                            popup.close();
                        }
                        continueBtn.disabled = false;
                        showError(err.message);
                    });
            });

            cancelBtn.addEventListener('click', close);
            copyCaption.addEventListener('click', () => {
                if (result) {
                    copyText(result.caption + ' ' + result.shareUrl).then((ok) => {
                        copyCaption.textContent = ok ? 'Copied' : 'Copy failed';
                        setTimeout(() => {
                            copyCaption.textContent = 'Copy caption';
                        }, 2000);
                    });
                }
            });
            downloadImage.addEventListener('click', () => {
                if (result && result.imageUrl) {
                    // A cross-origin image cannot be fetched under our CSP, so it opens in a new tab to save from there.
                    window.open(withContext(result.imageUrl), '_blank', 'noopener');
                }
            });
            doc.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && !dlg.hidden) {
                    close();
                }
            });
        });
    }

    // ------------------------------------------------------------------ start

    initTabs();
    initFormHelpers();
    initCopy();
    initAutoRefresh();
    initCharts();
    initShare();
})();