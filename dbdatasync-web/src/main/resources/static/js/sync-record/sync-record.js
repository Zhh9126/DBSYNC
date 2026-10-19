/**
 * DBDataSync 数据同步记录页面脚本
 * - 表格渲染（任务级执行状态与同步结果徽章）
 * - 多条件筛选（名称/连接器类型/同步模式/增量策略等）
 * - 导出迁移记录（CSV）
 * - 导出迁移清单（CSV）
 *
 * @author 穿云
 * @version 1.0.0
 * @date 2026-08-27
 */

(function () {
    // ------------------------------------------------------------------
    // 渲染辅助（页面级，不依赖 framework.js 的副作用）
    // ------------------------------------------------------------------

    // 渲染同步结果徽章组：成功 / 失败 / 差异 / 总数
    function renderSyncRecordResult(mapping) {
        const meta = mapping.meta || {};
        const total = Number(meta.total || 0);
        const success = Number(meta.success || 0);
        const fail = Number(meta.fail || 0);
        const diff = Number(meta.diff || 0);
        if (!meta.state && !total) {
            return '<span class="text-xs text-tertiary">未同步</span>';
        }
        let html = '<div class="flex items-center gap-2 flex-wrap text-xs">';
        html += '<span class="badge badge-primary" title="总条数"><i class="fa fa-list"></i> ' + escapeHtml(total) + '</span>';
        if (success) {
            html += '<span class="badge badge-success" title="成功条数"><i class="fa fa-check"></i> ' + escapeHtml(success) + '</span>';
        }
        if (fail) {
            html += '<span class="badge badge-danger" title="失败条数"><i class="fa fa-close"></i> ' + escapeHtml(fail) + '</span>';
        }
        if (diff) {
            html += '<span class="badge badge-warning" title="差异条数"><i class="fa fa-adjust"></i> ' + escapeHtml(diff) + '</span>';
        }
        html += '</div>';
        return html;
    }

    // 渲染任务状态徽章（运行中/ 已完成/ 已停止等）
    function renderSyncRecordState(mapping) {
        const meta = mapping.meta || {};
        const state = Number(meta.state);
        if (state === 1) {
            return '<span class="badge badge-warning"><i class="fa fa-spinner fa-spin"></i> 运行中</span>';
        }
        if (state === 3) {
            return '<span class="badge badge-success"><i class="fa fa-check"></i> 已完成</span>';
        }
        if (state === 2) {
            return '<span class="badge badge-secondary"><i class="fa fa-stop"></i> 已停止</span>';
        }
        return '<span class="badge badge-secondary"><i class="fa fa-circle-o"></i> 未运行</span>';
    }

    // 格式化最后同步时间（endTime 优先，其次 beginTime；毫秒时间戳 -> yyyy-MM-dd HH:mm:ss 等)
    function formatSyncRecordTime(mapping) {
        const meta = mapping.meta || {};
        const t = meta.endTime || meta.beginTime;
        if (!t) {
            return '-';
        }
        // 已是日期字符串则原样展示
        if (typeof t === 'string' && t.indexOf('-') >= 0) {
            return escapeHtml(t);
        }
        // 数字 / 数字字符串：按毫秒时间戳格式化日期时间（支持 10 位秒级时间戳等）
        if (typeof t === 'number' || /^\d+$/.test(String(t))) {
            const n = Number(t);
            const millis = String(n).length <= 10 ? n * 1000 : n;
            const d = new Date(millis);
            if (!isNaN(d.getTime())) {
                const p = function (x) { return x < 10 ? '0' + x : '' + x; };
                return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
                    + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
            }
        }
        return escapeHtml(t);
    }

    // 连接器类型 -> Font Awesome class
    function connectorIcon(type) {
        const t = String(type || '').toLowerCase();
        if (t.indexOf('mysql') >= 0) return 'fa fa-database';
        if (t.indexOf('postgres') >= 0) return 'fa fa-database';
        if (t.indexOf('oracle') >= 0) return 'fa fa-database';
        if (t.indexOf('sqlserver') >= 0 || t.indexOf('sql-server') >= 0 || t.indexOf('mssql') >= 0) return 'fa fa-database';
        if (t.indexOf('mongo') >= 0) return 'fa fa-database';
        if (t.indexOf('redis') >= 0) return 'fa fa-database';
        if (t.indexOf('kafka') >= 0) return 'fa fa-exchange';
        if (t.indexOf('clickhouse') >= 0 || t.indexOf('ck') === 0) return 'fa fa-database';
        if (t.indexOf('elasticsearch') >= 0 || t.indexOf('es') === 0) return 'fa fa-search';
        if (t.indexOf('h2') >= 0) return 'fa fa-database';
        if (t.indexOf('sqlite') >= 0) return 'fa fa-database';
        if (t.indexOf('dameng') >= 0) return 'fa fa-database';
        if (t.indexOf('tidb') >= 0) return 'fa fa-database';
        if (t.indexOf('file') >= 0 || t.indexOf('csv') >= 0) return 'fa fa-file-text-o';
        if (t.indexOf('http') >= 0 || t.indexOf('api') >= 0) return 'fa fa-cloud';
        return 'fa fa-database';
    }

    // HTML 转义
    function escapeHtml(s) {
        if (s === undefined || s === null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // ------------------------------------------------------------------
    // 模板渲染
    // ------------------------------------------------------------------

    function renderRow(mapping, seq) {
        const srcType = mapping.sourceConnector && mapping.sourceConnector.config && mapping.sourceConnector.config.connectorType;
        const tgtType = mapping.targetConnector && mapping.targetConnector.config && mapping.targetConnector.config.connectorType;
        const srcName = (mapping.sourceConnector && mapping.sourceConnector.name) || '-';
        const tgtName = (mapping.targetConnector && mapping.targetConnector.name) || '-';
        return '<tr>'
            + '<td class="text-tertiary">' + (seq || '-') + '</td>'
            + '<td><a href="javascript:" onclick="doLoader(\'/mapping/page/detail?id=' + encodeURIComponent(mapping.id) + '\')">'
            + escapeHtml(mapping.name) + '</a></td>'
            + '<td><div class="flex items-center gap-1">'
            + '<i class="' + escapeHtml(connectorIcon(srcType)) + ' connector-icon"></i>'
            + '<span class="text-xs">' + escapeHtml(srcName) + '</span></div></td>'
            + '<td><div class="flex items-center gap-1">'
            + '<i class="' + escapeHtml(connectorIcon(tgtType)) + ' connector-icon"></i>'
            + '<span class="text-xs">' + escapeHtml(tgtName) + '</span></div></td>'
            + '<td>' + renderModelText(mapping.model) + '</td>'
            + '<td>' + renderSyncModeText(mapping) + '</td>'
            + '<td>' + renderIncrementStrategyText(mapping) + '</td>'
            + '<td>' + renderSyncRecordResult(mapping) + '</td>'
            + '<td>' + renderSyncRecordState(mapping) + '</td>'
            + '<td><span class="text-xs text-secondary">' + formatSyncRecordTime(mapping) + '</span></td>'
            + '<td><button class="btn btn-xs btn-outline" onclick="doLoader(\'/mapping/page/detail?id=' + encodeURIComponent(mapping.id) + '\')">'
            + '<i class="fa fa-eye"></i> 明细</button></td>'
            + '</tr>';
    }

    // ------------------------------------------------------------------
    // 数据加载与分页
    // ------------------------------------------------------------------

    function collectFilters() {
        const filters = {};
        const sk = document.getElementById('searchKey').value.trim();
        if (sk) filters.searchKey = sk;
        const ct = document.getElementById('connectorTypeFilter').value;
        if (ct) filters.connectorType = ct;
        const md = document.getElementById('modelFilter').value;
        if (md) filters.model = md;
        return filters;
    }

    function doSearch(page) {
        const pagination = window.syncRecordPagination;
        if (!pagination) return;
        pagination.doSearch(collectFilters(), page || 1);
    }

    function renderTable(list) {
        const tbody = document.getElementById('syncRecordTableBody');
        if (!list || !list.length) {
            tbody.innerHTML = '<tr><td colspan="11" class="text-center text-tertiary py-6">暂无数据</td></tr>';
            return;
        }
        const html = list.map(renderRow).join('');
        tbody.innerHTML = html;
    }

    function initPagination() {
        const pagination = new PaginationManager({
            requestUrl: '/sync-record/search',
            tableBodySelector: '#syncRecordTableBody',
            paginationSelector: '#syncRecordPagination',
            pageSize: 10,
            emptyHtml: '<tr><td colspan="11" class="text-center text-tertiary py-6">暂无数据</td></tr>',
            renderRow: function (mapping, seq) {
                return renderRow(mapping, seq);
            }
        });
        window.syncRecordPagination = pagination;
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    function exportUrl(endpoint) {
        const params = collectFilters();
        const qs = Object.keys(params).map(function (k) {
            return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
        }).join('&');
        return endpoint + (qs ? '?' + qs : '');
    }

    function exportRecords() {
        window.location.href = exportUrl('/sync-record/exportRecords');
    }
    function exportManifest() {
        window.location.href = exportUrl('/sync-record/exportManifest');
    }

    // 暴露到 window 供模板 onclick 调用
    window.exportRecords = exportRecords;
    window.exportManifest = exportManifest;
    window.doLoader = window.doLoader || function (url) { window.location.href = url; };

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    $(function () {
        // 搜索框回车触发搜索
        $('#searchKey').on('keydown', function (e) {
            if (e.keyCode === 13 || e.which === 13) {
                doSearch(1);
            }
        });
        // 筛选变化触发搜索
        $('#connectorTypeFilter, #modelFilter').on('change', function () {
            doSearch(1);
        });
        initPagination();
    });
})();
