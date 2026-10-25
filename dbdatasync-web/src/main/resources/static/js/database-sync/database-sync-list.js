/**
 * 整库迁移列表页
 */
(function (window) {
    'use strict';

    /** 与 CommonTaskStatusEnum.DONE 对齐：本轮业务已完成 */
    var META_STATE_DONE = 3;

    function databaseSyncCreateValidateSync(taskId) {
        showConfirm({
            title: '创建数据对比任务？',
            text: '将按该迁移任务的全部同步映射生成数据对比任务，用于校验迁移后数据一致性。',
            icon: 'info',
            onConfirm: function () {
                doPoster('/database-sync/createValidateSync', { id: taskId }, function (response) {
                    if (response.success) {
                        bootGrowl('数据对比任务创建成功', 'success');
                        doLoader('/validate-sync');
                    } else {
                        bootGrowl('创建失败: ' + (response.message || '未知错误'), 'danger');
                    }
                });
            }
        });
    }

    function databaseSyncStart(taskId) {
        doPoster('/database-sync/start', { id: taskId }, function (response) {
            if (response.success) {
                bootGrowl(response.data || '启动成功', 'success');
                refreshIndexList();
            } else {
                bootGrowl(response.message || '启动失败', 'danger');
            }
        });
    }

    function databaseSyncStop(taskId) {
        doPoster('/database-sync/stop', { id: taskId }, function (response) {
            if (response.success) {
                bootGrowl(response.data || '停止成功', 'success');
                refreshIndexList();
            } else {
                bootGrowl(response.message || '停止失败', 'danger');
            }
        });
    }

    function databaseSyncRemove(taskId) {
        if (!confirm('确定删除该任务？')) {
            return;
        }
        doPoster('/database-sync/remove', { id: taskId }, function (response) {
            if (response.success) {
                bootGrowl(response.data || '删除成功', 'success');
                refreshIndexList();
            } else {
                bootGrowl(response.message || '删除失败', 'danger');
            }
        });
    }

    function getTaskStateConfig(taskId) {
        return {
            0: {
                icon: 'fa-play',
                title: '启动',
                onclick: "databaseSyncStart('" + taskId + "')",
                disabled: false,
                class: 'badge-info',
                text: '未运行'
            },
            1: {
                icon: 'fa-pause',
                title: '停止',
                onclick: "databaseSyncStop('" + taskId + "')",
                disabled: false,
                class: 'badge-success',
                text: '运行中'
            },
            2: {
                icon: 'fa-spinner fa-spin',
                title: '停止中',
                text: '停止中',
                disabled: true,
                class: 'badge-warning'
            },
            3: {
                icon: 'fa-play',
                title: '启动',
                onclick: "databaseSyncStart('" + taskId + "')",
                disabled: false,
                class: 'badge-primary',
                text: '已完成'
            }
        };
    }

    function renderTaskStateText(state) {
        const stateConfig = getTaskStateConfig();
        const config = stateConfig[state] || stateConfig[0];
        return '<span class="badge ' + config.class + '">' + config.text + '</span>';
    }

    function renderTaskStateButton(state, taskId) {
        const stateConfig = getTaskStateConfig(taskId);
        const config = stateConfig[state] || stateConfig[0];
        const disabledAttr = config.disabled ? ' disabled' : '';
        const onclickAttr = config.onclick ? ' onclick="' + config.onclick + '"' : '';
        let html = '<button class="table-action-btn play" title="' + config.title + '"' + onclickAttr + disabledAttr + '>'
            + '<i class="fa ' + config.icon + '"></i></button>';
        if (state === 0 || state === 3) {
            html += '<button class="table-action-btn delete" title="删除" onclick="databaseSyncRemove(\'' + taskId + '\')">'
                + '<i class="fa fa-trash"></i></button>';
        }
        return html;
    }

    function renderTableProgressText(task) {
        var total = Number(task.totalTableCount);
        var completed = Number(task.completedTableCount);
        if (isNaN(total) || total <= 0) {
            return '';
        }
        if (isNaN(completed) || completed < 0) {
            completed = 0;
        }
        if (completed > total) {
            completed = total;
        }
        return '<span class="text-xs text-secondary whitespace-nowrap">' + completed + '/' + total + '张表</span>';
    }

    function renderTaskDurationText(task) {
        var isRunning = Number(task.metaState) === 1;
        if (isRunning) {
            return '';
        }
        var durationText = formatElapsedDuration(task.beginTime, task.endTime);
        if (!durationText) {
            return '';
        }
        return '<span class="text-xs text-tertiary whitespace-nowrap">任务耗时：' + durationText + '</span>';
    }

    function renderResultColumn(task) {
        var n = Number(task.errorCount);
        if (isNaN(n) || n < 0) {
            n = 0;
        }
        var taskId = String(task.id || '').replace(/'/g, '');
        var isRunning = Number(task.metaState) === 1;
        var progressRaw = task.progress;
        if (progressRaw === null || progressRaw === undefined || progressRaw === '') {
            if (isRunning) {
                progressRaw = 0;
            } else {
                return '';
            }
        }
        var progress = Number(progressRaw);
        if (isNaN(progress) || progress < 0) {
            progress = 0;
        }
        if (progress > 100) {
            progress = 100;
        }
        if (progress === 0 && !isRunning) {
            return '';
        }

        var state = 'success';
        if (isRunning) {
            if (progress >= 80) {
                state = 'danger';
            } else if (progress >= 60) {
                state = 'warning';
            }
        } else if (Number(task.metaState) !== META_STATE_DONE && Number(task.endTime) > 0 && progress < 100) {
            state = 'warning';
        } else if (n > 0) {
            state = 'danger';
        }

        var left = (n > 0)
            ? ('异常：<a href="javascript:void(0)" class="hover:underline cursor-pointer" title="查看失败迁移结果" '
                + 'onclick="doLoader(\'/database-sync/page/detail?id=' + taskId + '&detailStatus=fail\'); return false;">'
                + '<span class="badge badge-error">' + n + '</span></a>')
            : '<span class="badge badge-success">正常</span>';
        var tableProgressHtml = renderTableProgressText(task);
        var durationHtml = renderTaskDurationText(task);
        var centerParts = [];
        if (tableProgressHtml) {
            centerParts.push(tableProgressHtml);
        }
        if (durationHtml) {
            centerParts.push(durationHtml);
        }
        var centerHtml = centerParts.length
            ? ('<span class="progress-meta">' + centerParts.join('') + '</span>')
            : '';
        var rightText = (isRunning && progress === 0) ? '0%' : (progress + '%');
        return ''
            + '<div class="min-w-200">'
            + '  <div class="progress-header progress-header-compact">'
            + '    <span class="progress-title">' + left + '</span>'
            + centerHtml
            + '    <span class="progress-value progress-value-' + state + '">' + rightText + '</span>'
            + '  </div>'
            + '  <div class="progress-bar progress-bar-compact">'
            + '    <div class="progress-fill ' + state + '" data-progress-width="' + progress + '"></div>'
            + '  </div>'
            + '</div>';
    }

    function databaseSyncUpgrade(taskId) {
        if (!confirm('为该整库迁移任务创建增量（CDC）驱动并启动？\n增量以迁移完成时刻为起点，不会重复全量。')) {
            return;
        }
        doPoster('/database-sync/upgrade', { id: taskId, autoStart: true }, function (response) {
            if (response.success) {
                const ids = response.data || [];
                bootGrowl('已创建并启动增量驱动：' + ids.length + ' 个，可在数据同步列表查看', 'success');
                refreshIndexList();
            } else {
                bootGrowl(response.message || '接续增量失败', 'danger');
            }
        });
    }

    function initDatabaseSyncerListPage() {
        window.backIndexPage = function () {
            doLoader('/database-sync/list');
        };
        window.databaseSyncStart = databaseSyncStart;
        window.databaseSyncStop = databaseSyncStop;
        window.databaseSyncRemove = databaseSyncRemove;
        window.databaseSyncUpgrade = databaseSyncUpgrade;

        const pagination = new PaginationManager({
            requestUrl: '/database-sync/search',
            tableBodySelector: '#database-sync-table',
            storageKey: 'database-sync-list',
            renderRow: function (task, index) {
                const mappingCount = task.mappingCount != null ? task.mappingCount : 0;
                const taskId = String(task.id || '').replace(/"/g, '');
                return ''
                    + '<tr>'
                    + '<td>' + index + '</td>'
                    + '<td>'
                    + '<a href="javascript:void(0)" class="text-primary hover:underline cursor-pointer" title="点击查看迁移结果" '
                    + 'onclick="doLoader(\'/database-sync/page/detail?id=' + taskId + '\')">'
                    + escapeHtml(task.name || '') + '</a>'
                    + '</td>'
                    + '<td>' + mappingCount + '</td>'
                    + '<td>' + renderResultColumn(task) + '</td>'
                    + '<td>' + renderTaskStateText(task.metaState || 0) + '</td>'
                    + '<td>' + formatDate(task.updateTime || '') + '</td>'
                    + '<td><div class="flex items-center">'
                    + '<button class="table-action-btn view" title="修改" onclick="doLoader(\'/database-sync/page/edit?id='
                    + taskId + '\')">'
                    + '<i class="fa fa-edit"></i></button>'
                    + '<button class="table-action-btn info" title="预检测（启动前校验）" onclick="runPreCheck(\'/database-sync/precheck\',\''
                    + taskId + '\',\'' + escapeHtml(task.name || '') + '\')">'
                    + '<i class="fa fa-list-alt"></i></button>'
                    + (Number(task.metaState) === META_STATE_DONE
                    + '<button class="table-action-btn compare" title="一键数据对比（迁移完成后校验一致性）" '
                    + 'onclick="databaseSyncCreateValidateSync(\'' + taskId + '\')"><i class="fa fa-exchange"></i></button>'
                        ? '<button class="table-action-btn upgrade" title="一键接续增量（离线结果 -> 实时 CDC）" '
                        + 'onclick="databaseSyncUpgrade(\'' + taskId + '\')"><i class="fa fa-magic"></i></button>'
                        : '')
                    + renderTaskStateButton(task.metaState || 0, task.id)
                    + '</div></td>'
                    + '</tr>';
            },
            emptyHtml: '<td colspan="8" class="text-center">'
                + '<i class="fa fa-database empty-icon"></i>'
                + '<p class="empty-text">暂无任务</p>'
                + '<p class="empty-description">点击「添加」创建第一个任务</p></td>',
            customPageSize: true
        });

        const searchInput = initSearch('database-sync-search', function (searchKey) {
            pagination.doSearch({ searchKey: searchKey }, 1);
        });

        window.refreshIndexList = function () {
            pagination.doSearch({ searchKey: searchInput.getValue() }, pagination.currentPage);
        };

        PageRefreshManager.register(function () {
            pagination.doSearch({ searchKey: searchInput.getValue() }, pagination.currentPage);
        });
    }

    $(document).ready(initDatabaseSyncerListPage);
})(window);
