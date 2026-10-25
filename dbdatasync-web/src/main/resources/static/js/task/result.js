/**
 * 数据对比任务执行结果页(遗留独立页)
 * 修复: 原引用本文件缺失导致页面 404、列表与详情弹窗不可用。
 * 数据源: /validate-sync/searchResult + /validate-sync/getResultDetail
 */
(function (window) {
    'use strict';

    function escapeHtml(str) {
        if (str === null || str === undefined) {
            return '-';
        }
        return String(str)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function formatTime(ts) {
        if (!ts) {
            return '-';
        }
        var d = new Date(Number(ts));
        if (isNaN(d.getTime())) {
            return String(ts);
        }
        function p(n) { return n < 10 ? '0' + n : n; }
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
                + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }

    function renderResultCell(row) {
        if (row.isSuccess === true || row.isSuccess === 'true' || Number(row.isSuccess) === 1) {
            return '<span class="badge badge-success"><i class="fa fa-check"></i> 一致</span>';
        }
        var error = row.error || row.message || '';
        if (error) {
            return '<span class="badge badge-error" title="' + escapeHtml(error).substring(0, 200)
                    + '"><i class="fa fa-times"></i> ' + escapeHtml(String(error).substring(0, 60)) + '</span>';
        }
        return '<span class="badge badge-warning"><i class="fa fa-exclamation-triangle"></i> 存在差异</span>';
    }

    function showDetail(taskId, detailId) {
        doPoster('/validate-sync/getResultDetail', { taskId: taskId, id: detailId }, function (response) {
            if (!response || response.success !== true || !response.data) {
                bootGrowl('查询详情失败: ' + ((response && response.message) || '记录不存在'), 'danger');
                return;
            }
            var d = response.data;
            $('#detailSourceTable').text(d.sourceTable || d.sourceTableName || '-');
            $('#detailTargetTable').text(d.targetTable || d.targetTableName || '-');
            $('#detailUpdateTime').text(formatTime(d.updateTime));

            var $body = $('#detailContentTable').empty();
            // content/data 可能是 JSON 字符串(字段级差异)或纯文本
            var raw = d.data || d.content || d.error || '';
            var diffs = [];
            if (raw && typeof raw === 'string' && raw.trim().charAt(0) === '{') {
                try {
                    var parsed = JSON.parse(raw);
                    if (parsed && typeof parsed === 'object') {
                        diffs = parsed.diffs || parsed.fields || parsed.rows || [];
                    }
                } catch (e) { /* 退化为纯文本 */ }
            }
            if (diffs.length) {
                $.each(diffs, function (i, item) {
                    $body.append('<tr><td>' + escapeHtml(item.field || item.name || ('#' + i)) + '</td>'
                            + '<td>' + escapeHtml(item.source != null ? item.source : '-') + '</td>'
                            + '<td>' + escapeHtml(item.target != null ? item.target : '-') + '</td>'
                            + '<td>' + escapeHtml(item.diff || item.remark || '-') + '</td></tr>');
                });
            } else if (raw) {
                $body.append('<tr><td colspan="4"><pre style="white-space:pre-wrap;word-break:break-all;">'
                        + escapeHtml(String(raw)) + '</pre></td></tr>');
            } else {
                $body.append('<tr><td colspan="4" class="text-center text-gray-500">无差异明细</td></tr>');
            }
            $('#resultDetailModal').show();
        });
    }

    function init() {
        var taskId = window.taskId;
        if (!taskId) {
            $('#resultTableBody').html('<tr><td colspan="5" class="text-center">缺少任务ID参数</td></tr>');
            return;
        }

        window.resultPagination = new PaginationManager({
            requestUrl: '/validate-sync/searchResult',
            tableBodySelector: '#resultTableBody',
            params: { taskId: taskId },
            renderRow: function (row, index) {
                var detailId = row.id != null ? String(row.id) : '';
                return '<tr>'
                        + '<td>' + (index + 1) + '</td>'
                        + '<td>' + escapeHtml(row.sourceTable || row.sourceTableName || '-') + '</td>'
                        + '<td>' + escapeHtml(row.targetTable || row.targetTableName || '-') + '</td>'
                        + '<td>' + renderResultCell(row) + '</td>'
                        + '<td>' + formatTime(row.updateTime) + '</td>'
                        + '<td><button type="button" class="table-action-btn view" title="查看详情" onclick="window.showTaskResultDetail(\''
                        + String(taskId) + '\', \'' + detailId.replace(/'/g, '') + '\')"><i class="fa fa-eye"></i></button></td>'
                        + '</tr>';
            },
            emptyHtml: '<td colspan="6" class="text-center"><i class="fa fa-file-text-o empty-icon"></i>'
                    + '<p class="empty-text">暂无执行结果</p><p class="empty-description">请先执行数据对比任务</p></td>'
        });
        window.resultPagination.searchParams = { taskId: taskId };
    }

    window.showTaskResultDetail = showDetail;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(window);
