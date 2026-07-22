document.addEventListener('DOMContentLoaded', () => {
    const downloadBtn = document.getElementById('download-btn');
    const apkSizeEl = document.getElementById('apk-size');

    // Try to fetch the real file size from the server when served over HTTP.
    if (apkSizeEl && window.location.protocol.startsWith('http')) {
        fetch('WClient-debug.apk', { method: 'HEAD' })
            .then(res => {
                const size = res.headers.get('content-length');
                if (size) {
                    const mb = (parseInt(size, 10) / 1024 / 1024).toFixed(1);
                    apkSizeEl.textContent = `${mb} MB`;
                }
            })
            .catch(() => {
                // Fallback: keep the static approximate size.
            });
    }

    // Ripple / active state for the primary download button.
    if (downloadBtn) {
        downloadBtn.addEventListener('click', (e) => {
            const btn = e.currentTarget;
            btn.classList.add('clicked');
            setTimeout(() => btn.classList.remove('clicked'), 300);
        });
    }

    // Intersection observer for subtle fade-in on scroll.
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = '1';
                entry.target.style.transform = 'translateY(0)';
            }
        });
    }, { threshold: 0.1 });

    document.querySelectorAll('.feature-card, .step').forEach(el => {
        el.style.opacity = '0';
        el.style.transform = 'translateY(20px)';
        el.style.transition = 'opacity 0.6s ease, transform 0.6s ease';
        observer.observe(el);
    });
});
