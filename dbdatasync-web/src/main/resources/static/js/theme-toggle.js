/**
 * DBDataSync 主题切换（深色/浅色）
 * - 首次访问跟随系统 prefers-color-scheme
 * - 用户手动切换后持久化 localStorage
 * - 页面 head 内联 snippet 已在渲染前设置 data-theme（防闪烁），此处仅负责交互与持久化
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'dbdatasync-theme';

    function getStoredTheme() {
        try {
            return localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            return null;
        }
    }

    function applyTheme(theme, animate) {
        var root = document.documentElement;
        if (animate) {
            root.classList.add('theme-transition');
            setTimeout(function () { root.classList.remove('theme-transition'); }, 300);
        }
        if (theme === 'dark') {
            root.setAttribute('data-theme', 'dark');
        } else {
            root.removeAttribute('data-theme');
        }
    }

    function initToggle() {
        // 为 header 中的切换按钮绑定事件（按钮由 header.html 提供，防御性查找）
        var btn = document.getElementById('themeToggleBtn');
        if (!btn) {
            return;
        }
        btn.addEventListener('click', function () {
            var current = document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
            var next = current === 'dark' ? 'light' : 'dark';
            applyTheme(next, true);
            try {
                localStorage.setItem(STORAGE_KEY, next);
            } catch (e) { /* 隐私模式下忽略 */ }
        });
    }

    // 初始化：应用持久化主题（head 内联 snippet 兜底，双保险）
    var stored = getStoredTheme();
    if (stored === 'dark' || stored === 'light') {
        applyTheme(stored, false);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initToggle);
    } else {
        initToggle();
    }
})();
