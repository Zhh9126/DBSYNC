/**
 * Wave24：严格安全模式下的 CSRF 请求头自动注入。
 *
 * Spring Security 使用 CookieCsrfTokenRepository（XSRF-TOKEN cookie，httpOnly=false）。
 * 本脚本统一包装 fetch / XMLHttpRequest / 表单提交，让存量页面无需逐个加隐藏字段：
 * 所有同源写请求自动带上 X-XSRF-TOKEN 头，表单自动补 _csrf 隐藏域。
 * 非严格模式下服务端不校验，多余的请求头无副作用。
 */
(function (global) {
    'use strict';

    var COOKIE_NAME = 'XSRF-TOKEN';
    var HEADER_NAME = 'X-XSRF-TOKEN';
    var MUTATING = ['POST', 'PUT', 'PATCH', 'DELETE'];

    function readToken() {
        var parts = (document.cookie || '').split(';');
        for (var i = 0; i < parts.length; i++) {
            var pair = parts[i].trim();
            if (pair.indexOf(COOKIE_NAME + '=') === 0) {
                return decodeURIComponent(pair.substring(COOKIE_NAME.length + 1));
            }
        }
        return '';
    }

    function isMutating(method) {
        return MUTATING.indexOf(String(method || 'GET').toUpperCase()) >= 0;
    }

    if (global.fetch) {
        var originalFetch = global.fetch;
        global.fetch = function (input, init) {
            var options = init || {};
            var method = options.method || (input && input.method) || 'GET';
            if (isMutating(method)) {
                var token = readToken();
                if (token) {
                    var headers = new Headers(options.headers || (input && input.headers) || {});
                    if (!headers.has(HEADER_NAME)) {
                        headers.set(HEADER_NAME, token);
                    }
                    options.headers = headers;
                }
            }
            return originalFetch.call(this, input, options);
        };
    }

    if (global.XMLHttpRequest && global.XMLHttpRequest.prototype) {
        var proto = global.XMLHttpRequest.prototype;
        var originalOpen = proto.open;
        var originalSend = proto.send;
        proto.open = function (method) {
            this.__dbsyncMethod = String(method || 'GET').toUpperCase();
            return originalOpen.apply(this, arguments);
        };
        proto.send = function () {
            if (isMutating(this.__dbsyncMethod)) {
                var token = readToken();
                if (token) {
                    try {
                        this.setRequestHeader(HEADER_NAME, token);
                    } catch (ignored) {
                        // 尚未 open 的异常用法，忽略
                    }
                }
            }
            return originalSend.apply(this, arguments);
        };
    }

    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (!form || form.tagName !== 'FORM' || isMutating(form.method) === false) {
            return;
        }
        if (form.querySelector('input[name="_csrf"]')) {
            return;
        }
        var token = readToken();
        if (!token) {
            return;
        }
        var hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.name = '_csrf';
        hidden.value = token;
        form.appendChild(hidden);
    }, true);
})(window);
