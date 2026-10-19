/**
 * 全局同步数据明细页（仪表盘下钻目标）
 * 数据源：/monitor/queryAllData（跨驱动聚合分表明细）
 */
(function (window) {
    'use strict';

    var pagination = null;

    function escapeHtml(s) {
        if (s === undefined || s === null) return '';
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatTime(ts) {
        var n = Number(ts);
        if (!n) return '-';
        try {
            var d = new Date(n);
            var p = function (x) { return x < 10 ? '0' + x : '' + x; };
            return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
                + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
        } catch (e) {
            return String(ts);
        }
    }

    function eventBadge(event) {
        var ev = String(event || '');
        var map = {
            'INSERT': ['badge-success', '新增'],
            'UPDATE': ['badge-info', '修改'],
            'UPSERT': ['badge-info', 'Upsert'],
            'DELETE': ['badge-warning', '删除'],
            'ALTER': ['badge-error', 'DDL']
        };
        var cfg = map[ev] || ['badge-info', ev || '-'];
        return '<span class="badge ' + cfg[0] + '">' + escapeHtml(cfg[1]) + '</span>';
    }

    function resultBadge(success) {
        return Number(success) === 1
            ? '<span class="badge badge-success">成功</span>'
            : '<span class="badge badge-error">失败</span>';
    }

    function columnsToTable(columns) {
        var $content = '<table class="table">';
        $content += '<thead><tr><th></th><th>字段</th><th>类型</th><th>值</th></tr></thead><tbody>';
        if (!columns || !columns.length) {
            $content += '<tr><td colspan="4" class="text-center text-gray-400">暂无字段数据</td></tr>';
        } else {
            columns.forEach(function (col, i) {
                var val = col.value;
                if (val === null || val === undefined) val = 'NULL';
                $content += '<tr>'
                    + '<td>' + (i + 1) + '</td>'
                    + '<td>' + escapeHtml(col.key || '') + '</td>'
                    + '<td>' + escapeHtml(col.keyType || '') + '</td>'
                    + '<td class="white-space-none">' + escapeHtml(String(val)) + '</td>'
                    + '</tr>';
            });
        }
        $content += '</tbody></table>';
        return $content;
    }

    function showErrorDetail(row) {
        var content = row && row.error ? String(row.error) : '';
        if (!content.trim()) {
            bootGrowl('该记录无失败原因', 'info');
            return;
        }
        showConfirm({ title: '失败原因', icon: 'warning', size: 'max', message: content });
    }

    window.showGlobalDataDetail = function (metaId, messageId) {
        doPoster('/monitor/getDataDetail', { metaId: metaId, messageId: messageId }, function (response) {
            if (!response.success) {
                bootGrowl(response.message || '加载数据失败', 'danger');
                return;
            }
            var message = response.data || {};
            var body = (message.columns && message.columns.length)
                ? columnsToTable(message.columns)
                : '<p class="text-center text-tertiary">暂无字段数据</p>';
            showConfirm({ title: '数据详情', icon: 'info', size: 'max', body: body, confirmText: '关闭' });
        });
    };

    function renderRow(row) {
        var taskName = escapeHtml(row.mappingName || '');
        var taskCell = row.mappingId
            ? '<a href="javascript:" onclick="doLoader(\'/mapping/page/events?id=' + encodeURIComponent(row.mappingId) + '&tableGroupId=' + encodeURIComponent(row.tableGroupId || '') + '\')">' + taskName + '</a>'
            : taskName;
        return '<tr>'
            + '<td>' + (taskCell || '-') + '</td>'
            + '<td><span class="text-xs">' + escapeHtml(row.sourceTableName || '-') + '</span>'
            + ' <i class="fa fa-arrow-right text-tertiary"></i> '
            + '<span class="text-xs">' + escapeHtml(row.targetTableName || '-') + '</span></td>'
            + '<td>' + eventBadge(row.event) + '</td>'
            + '<td>' + resultBadge(row.success) + '</td>'
            + '<td title="' + escapeHtml(row.error || '') + '">'
            + (row.error
                ? '<a href="javascript:" class="text-error" onclick="window.showDataErrorDetail && window.showDataErrorDetail(this)">' + escapeHtml(String(row.error).substring(0, 40)) + '</a>'
                : '-')
            + '</td>'
            + '<td class="text-xs">' + formatTime(row.createTime) + '</td>'
            + '<td><button class="btn btn-xs btn-outline" onclick="showGlobalDataDetail(\'' + encodeURIComponent(row.metaId || '') + '\', \'' + encodeURIComponent(row.id || '') + '\')">'
            + '<i class="fa fa-eye"></i> 数据</button></td>'
            + '</tr>';
    }

    function collectFilters() {
        var filters = {};
        var ev = document.getElementById('eventFilter').value;
        if (ev) filters.event = ev;
        var st = document.getElementById('statusFilter').value;
        if (st !== '') filters.status = st;
        return filters;
    }

    // 失败原因点击（renderRow 里的内联入口）
    window.showDataErrorDetail = function (el) {
        var text = el.getAttribute('title') || '';
        if (text.trim()) {
            showConfirm({ title: '失败原因', icon: 'warning', size: 'max', message: text });
        }
    };

    function initPage() {
        // 读取仪表盘下钻上下文（event/status），优先 sessionStorage，回退页面 data-attr
        var root = document.getElementById('monitorDataPage');
        var drill = null;
        try {
            drill = JSON.parse(sessionStorage.getItem('dashboard.drill') || 'null');
            sessionStorage.removeItem('dashboard.drill');
        } catch (e) { /* ignore */ }
        var attrStatus = root ? (root.getAttribute('data-status') || '') : '';
        var attrEvent = root ? (root.getAttribute('data-event') || '') : '';
        if (drill) {
            if (drill.status !== undefined && drill.status !== null && drill.status !== '') {
                document.getElementById('statusFilter').value = String(drill.status);
            }
            if (drill.event) {
                document.getElementById('eventFilter').value = drill.event;
            }
        } else if (attrStatus !== '' || attrEvent !== '') {
            if (attrStatus !== '') document.getElementById('statusFilter').value = attrStatus;
            if (attrEvent !== '') document.getElementById('eventFilter').value = attrEvent;
        }

        pagination = new PaginationManager({
            requestUrl: '/monitor/queryAllData',
            tableBodySelector: '#dataDetailTableBody',
            paginationSelector: '#dataDetailPagination',
            pageSize: 10,
            emptyHtml: '<tr><td colspan="7" class="text-center text-tertiary py-6">暂无数据</td></tr>',
            renderRow: function (row) { return renderRow(row); }
        });

        window.dataListSearch = function (page) {
            pagination.doSearch(collectFilters(), page || 1);
        };

        $('#eventFilter,#statusFilter').change(function () { window.dataListSearch(1); });
        window.dataListSearch(1);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initPage);
    } else {
        initPage();
    }
})(window);
