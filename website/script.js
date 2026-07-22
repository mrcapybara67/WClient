document.addEventListener('DOMContentLoaded', () => {
    const APK_NAME = 'WClient-v18.1.5-debug.apk';
    const downloadBtn = document.getElementById('download-btn');
    const apkSizeEl = document.getElementById('apk-size');
    const fileSizeDetailEl = document.getElementById('file-size-detail');
    const copyLinkBtn = document.getElementById('copy-link-btn');
    const toast = document.getElementById('toast');
    const navbar = document.getElementById('navbar');
    const menuToggle = document.getElementById('menu-toggle');
    const navLinks = document.querySelector('.nav-links');

    // Format bytes to human readable string
    const formatSize = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
    };

    // Fetch real file size from server when served over HTTP
    const fetchFileSize = async () => {
        if (!window.location.protocol.startsWith('http')) return;

        try {
            const response = await fetch(APK_NAME, { method: 'HEAD' });
            if (response.ok) {
                const size = response.headers.get('content-length');
                if (size) {
                    const formatted = formatSize(parseInt(size, 10));
                    if (apkSizeEl) apkSizeEl.textContent = formatted;
                    if (fileSizeDetailEl) fileSizeDetailEl.textContent = formatted;
                }
            }
        } catch (error) {
            console.warn('Could not fetch APK size:', error);
        }
    };

    fetchFileSize();

    // Generate QR code for the current download URL
    const generateQRCode = () => {
        const canvas = document.getElementById('qr-canvas');
        if (!canvas || typeof QRCode === 'undefined') return;

        const downloadUrl = new URL(APK_NAME, window.location.href).href;

        try {
            QRCode.toCanvas(canvas, downloadUrl, {
                width: 120,
                margin: 1,
                color: {
                    dark: '#0b0c15',
                    light: '#ffffff'
                }
            }, (error) => {
                if (error) {
                    console.warn('QR code generation failed:', error);
                    const qrSection = canvas.closest('.qr-section');
                    if (qrSection) qrSection.style.display = 'none';
                }
            });
        } catch (error) {
            console.warn('QR code generation error:', error);
        }
    };

    generateQRCode();

    // Show toast notification
    const showToast = (message, type = 'info') => {
        if (!toast) return;

        const icon = type === 'success' ? '✓' : 'ℹ️';
        toast.innerHTML = `<span>${icon}</span> ${message}`;
        toast.classList.add('show');

        setTimeout(() => {
            toast.classList.remove('show');
        }, 2500);
    };

    // Copy link to clipboard
    const copyLink = async () => {
        const downloadUrl = new URL(APK_NAME, window.location.href).href;

        try {
            if (navigator.clipboard && window.isSecureContext) {
                await navigator.clipboard.writeText(downloadUrl);
                showToast('Download link copied to clipboard!', 'success');
            } else {
                // Fallback for non-secure contexts
                const textArea = document.createElement('textarea');
                textArea.value = downloadUrl;
                textArea.style.position = 'fixed';
                textArea.style.left = '-9999px';
                document.body.appendChild(textArea);
                textArea.select();
                document.execCommand('copy');
                document.body.removeChild(textArea);
                showToast('Download link copied to clipboard!', 'success');
            }
        } catch (error) {
            console.warn('Could not copy link:', error);
            showToast('Could not copy link', 'error');
        }
    };

    if (copyLinkBtn) {
        copyLinkBtn.addEventListener('click', copyLink);
    }

    // Confetti effect on download button click
    const attachDownloadEffect = (element) => {
        if (element && typeof confetti === 'function') {
            element.addEventListener('click', () => {
                confetti({
                    particleCount: 120,
                    spread: 80,
                    origin: { y: 0.7 },
                    colors: ['#8b5cf6', '#6366f1', '#22d3ee', '#ffffff'],
                    disableForReducedMotion: true
                });

                showToast('Download started!', 'success');
            });
        } else if (element) {
            element.addEventListener('click', () => {
                showToast('Download started!', 'success');
            });
        }
    };

    attachDownloadEffect(downloadBtn);

    // Mobile menu toggle
    const updateMenuState = (isOpen) => {
        menuToggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
        menuToggle.classList.toggle('active', isOpen);
        navLinks.classList.toggle('active', isOpen);
    };

    if (menuToggle && navLinks) {
        menuToggle.addEventListener('click', () => {
            const isOpen = !menuToggle.classList.contains('active');
            updateMenuState(isOpen);
        });

        navLinks.querySelectorAll('a').forEach(link => {
            link.addEventListener('click', () => {
                updateMenuState(false);
            });
        });

        // Close menu when clicking outside
        document.addEventListener('click', (e) => {
            if (!menuToggle.contains(e.target) && !navLinks.contains(e.target)) {
                updateMenuState(false);
            }
        });
    }

    // Navbar scroll effect
    const handleScroll = () => {
        if (window.scrollY > 30) {
            navbar.classList.add('scrolled');
        } else {
            navbar.classList.remove('scrolled');
        }
    };

    window.addEventListener('scroll', handleScroll, { passive: true });

    // Intersection observer for scroll animations
    const observerOptions = {
        threshold: 0.1,
        rootMargin: '0px 0px -50px 0px'
    };

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('visible');
                observer.unobserve(entry.target);
            }
        });
    }, observerOptions);

    document.querySelectorAll('.feature-card, .step, .warning').forEach(el => {
        observer.observe(el);
    });

    // Stagger animation for feature cards
    const featureCards = document.querySelectorAll('.feature-card');
    featureCards.forEach((card, index) => {
        card.style.transitionDelay = `${index * 80}ms`;
    });

    // Smooth scroll for anchor links
    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            const href = this.getAttribute('href');
            if (href === '#') return;

            const target = document.querySelector(href);
            if (target) {
                e.preventDefault();
                target.scrollIntoView({
                    behavior: 'smooth',
                    block: 'start'
                });
            }
        });
    });

    // Reveal elements with initial animation
    const revealElements = () => {
        const animatedElements = document.querySelectorAll('.hero-title, .hero-subtitle, .download-card, .hero-actions, .hero-card-wrapper');
        animatedElements.forEach((el, index) => {
            el.classList.add('reveal');
            el.style.animationDelay = `${index * 0.1}s`;
        });
    };

    revealElements();

    // Attach mobile download effect after reveal
    const mobileDownloadBtn = document.querySelector('#mobile-download .btn');
    attachDownloadEffect(mobileDownloadBtn);
});
