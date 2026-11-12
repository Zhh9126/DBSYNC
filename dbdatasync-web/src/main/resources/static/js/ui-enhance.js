/**
 * DBDataSync · DataX Web 视觉增强脚本
 * 只负责外壳交互（面包屑、日期、刷新），不触碰 framework.js 的业务逻辑。
 */
(function () {
    'use strict';

    var currentUrl = '/index';

    /** 更新顶栏面包屑，页面内也可主动调用 window.uiSetCrumb('连接管理') */
    window.uiSetCrumb = function (text) {
        var el = document.getElementById('pageCrumb');
        if (el && text) {
            el.textContent = text;
        }
    };

    /** 刷新当前页面（顶栏刷新按钮） */
    window.uiRefreshCurrent = function () {
        var btn = document.querySelector('.topbar-icon-btn .fa-refresh');
        if (btn) {
            btn.style.transition = 'transform .5s ease';
            btn.style.transform = 'rotate(360deg)';
            setTimeout(function () { btn.style.transform = ''; }, 520);
        }
        if (typeof window.doLoader === 'function') {
            window.doLoader(currentUrl);
        } else {
            window.location.reload();
        }
    };

    /** 顶栏日期 */
    function renderDate() {
        var el = document.getElementById('topbarDate');
        if (!el) { return; }
        var d = new Date();
        var weeks = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
        var mm = d.getMonth() + 1;
        var dd = d.getDate();
        el.textContent = d.getFullYear() + '年' + mm + '月' + dd + '日 · ' + weeks[d.getDay()];
    }

    /** 侧栏点击 → 面包屑联动（与 framework.js 的点击处理互不干扰） */
    function bindSidebar() {
        if (window.jQuery) {
            window.jQuery(document).on('click', '.sidebar-item[url]', function () {
                var url = window.jQuery(this).attr('url');
                var text = window.jQuery(this).find('span').first().text();
                currentUrl = url || '/index';
                window.uiSetCrumb((text || '').trim());
            });
        } else {
            document.addEventListener('click', function (e) {
                var item = e.target.closest ? e.target.closest('.sidebar-item[url]') : null;
                if (!item) { return; }
                var span = item.querySelector('span');
                currentUrl = item.getAttribute('url') || '/index';
                window.uiSetCrumb(span ? span.textContent.trim() : '');
            });
        }
    }

    renderDate();
    setInterval(renderDate, 60000);
    bindSidebar();
})();
