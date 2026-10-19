// 缓存常用选择器
const $fieldMappingList = () => $("#fieldMappingList");
const $fieldCheckboxes = () => $('.fieldCheckbox');
const $fieldCheckboxAll = () => $('.fieldCheckboxAll');
const $fieldDelBtn = () => $('#fieldDelBtn');
const $fieldMapping = () => $("#fieldMapping");

// 初始化映射关系参数
function initFieldMappingParams(){
    // 生成JSON参数
    let row = [];
    const $list = $fieldMappingList();
    $list.find("tr").each(function(k,v){
        row.push({
            "source":$(this).find("td:eq(1)").text(),
            "target":$(this).find("td:eq(2)").text(),
            "pk": $(this).find("td:eq(3) i").length > 0
        });
    });
    
    // 根据是否有数据来显示/隐藏表格（通过tbody的父元素table）
    const $table = $list.closest('table');
    if (row.length === 0) {
        $table.addClass("hidden");
    } else {
        $table.removeClass("hidden");
    }
    $fieldMapping().val(JSON.stringify(row));
}

function bindFieldSelect(selector, onChange){
    return selector.dbSelect({
        type: 'single',
        onSelect: function (data) {
            if (onChange) {
                onChange(data);
            }
        },
        defaultValue: [],// 默认选中
        customButtons: [ // 最多2个自定义按钮
            {
                text: '刷新字段',
                callback: function(values) {
                    bindRefreshTableFieldsClick();
                }
            }
        ]
    });
}

// 绑定表格拖拽事件（使用原生 HTML5 拖拽 API）
function bindFieldMappingDrop() {
    const $list = $fieldMappingList();
    const rows = $list.find("tr");
    
    // 先移除旧的事件监听器，避免重复绑定
    rows.off('dragstart dragend dragover dragleave drop');
    rows.each(function() {
        const row = this;
        row.setAttribute('draggable', 'true');
    });
    
    // 使用事件委托，避免重复绑定
    $list.on('dragstart', 'tr', function(e) {
        e.originalEvent.dataTransfer.effectAllowed = 'move';
        e.originalEvent.dataTransfer.setData('text/html', this.innerHTML);
        $(this).addClass('dragging');
    });

    $list.on('dragend', 'tr', function(e) {
        $(this).removeClass('dragging');
        $list.find('tr').removeClass('drag-over');
        // 更新行号
        updateRowNumbers();
        initFieldMappingParams();
    });

    $list.on('dragover', 'tr', function(e) {
        e.preventDefault();
        e.originalEvent.dataTransfer.dropEffect = 'move';
        $(this).addClass('drag-over');
        return false;
    });

    $list.on('dragleave', 'tr', function(e) {
        $(this).removeClass('drag-over');
    });

    $list.on('drop', 'tr', function(e) {
        e.preventDefault();
        e.stopPropagation();
        
        const $dragging = $list.find('.dragging');
        if ($dragging.length && $dragging[0] !== this) {
            const draggingIndex = $dragging.index();
            const targetIndex = $(this).index();
            
            if (draggingIndex < targetIndex) {
                $(this).after($dragging);
            } else {
                $(this).before($dragging);
            }
        }
        
        $(this).removeClass('drag-over');
        return false;
    });
}

// 更新表格行号
function updateRowNumbers() {
    $fieldMappingList().find("tr").each(function(index) {
        $(this).find("td:eq(0)").text(index + 1);
    });
}

// 绑定复选框多选或单选事件
function bindFieldMappingCheckbox() {
    const $checkboxAll = $fieldCheckboxAll();
    const $delBtn = $fieldDelBtn();
    let isUpdatingSelectAll = false;

    // 更新全选复选框状态
    function updateSelectAllState() {
        if (isUpdatingSelectAll) return;
        isUpdatingSelectAll = true;
        const $checkboxes = $fieldCheckboxes();
        const $checkedCheckboxes = $checkboxes.filter(':checked');
        const allChecked = $checkboxes.length > 0 && $checkboxes.length === $checkedCheckboxes.length;
        const checkboxAllApi = $checkboxAll.data('checkboxGroup');
        if (checkboxAllApi) {
            checkboxAllApi.setValue(allChecked);
        } else {
            $checkboxAll.prop('checked', allChecked);
        }
        setTimeout(function() {
            isUpdatingSelectAll = false;
        }, 0);
    }

    // 更新删除按钮状态
    function updateDeleteButtonState() {
        $delBtn.prop('disabled', $fieldCheckboxes().filter(':checked').length === 0);
    }

    // 先移除旧的事件绑定，避免重复绑定
    $checkboxAll.off('change');
    $fieldCheckboxes().off('change');

    // 初始化全选复选框
    $checkboxAll.checkboxGroup({
        theme: 'danger',
        size: 'md',
        onChange: function(values) {
            // 全选/取消全选时，同步所有单个复选框
            const isChecked = values.length > 0;
            $fieldCheckboxes().each(function() {
                const api = $(this).data('checkboxGroup');
                if (api) {
                    api.setValue(isChecked);
                }
            });
            updateDeleteButtonState();
        }
    });

    // 为已存在的复选框初始化
    $fieldCheckboxes().each(function() {
        if (!$(this).data('checkboxGroup')) {
            $(this).checkboxGroup({
                theme: 'danger',
                size: 'md',
                onChange: function(values) {
                    updateSelectAllState();
                    updateDeleteButtonState();
                }
            });
        }
    });
}

// 绑定刷新表字段事件
function bindRefreshTableFieldsClick() {
    let id = $("#tableGroupId").val();
    doPoster("/tableGroup/refreshFields", {'id': id}, function (data) {
        if (data.success === true) {
            bootGrowl("刷新字段成功!", "success");
            doLoader('/tableGroup/page/editTableGroup?id=' + id);
        } else {
            bootGrowl(data.message, "danger");
        }
    });
}

// 绑定字段映射表格点击事件
function bindFieldMappingListClick(){
    // 使用事件委托，支持动态添加的行
    const $list = $fieldMappingList();
    $list.off("dblclick", "tr").on('dblclick', 'tr', function () {
        const $pk = $(this).find("td:eq(3)");
        // 更新为新的图标样式
        $pk.html($pk.find("i").length > 0 ? '' : '<i title="主键" class="fa fa-key text-warning"></i>');
        initFieldMappingParams();
    });
}

// 绑定添加字段映射点击事件
function bindFieldMappingAddClick(sourceSelector, targetSelector){
    $("#fieldAddBtn").unbind("click").bind('click', function(){
        let sValues = sourceSelector.getValues();
        let tValues = targetSelector.getValues();
        // 非空检查
        if(sValues.length < 1 && tValues.length < 1){
            bootGrowl("至少有一个字段.", "danger");
            return;
        }

        // 检查重复字段
        let sField = sValues[0];
        let tField = tValues[0];
        sField = sField == null ? "" : sField;
        tField = tField == null ? "" : tField;
        let repeated = false;
        const $list = $fieldMappingList();
        const $tr = $list.find("tr");
        $tr.each(function (k, v) {
            let sf = $(this).find("td:eq(1)").text();
            let tf = $(this).find("td:eq(2)").text();
            if (sField === sf && tField === tf) {
                repeated = true;
                return false; // 跳出循环
            }
        });
        if (repeated) {
            bootGrowl("字段映射已存在.", "danger");
            return;
        }

        // 转义HTML防止XSS攻击
        const escapedSField = escapeHtml(sField);
        const escapedTField = escapeHtml(tField);
        const rowIndex = $tr.length + 1;

        $list.append(`<tr title='双击设置/取消主键 | 拖动排序'>
                    <td>${rowIndex}</td>
                    <td>${escapedSField}</td>
                    <td>${escapedTField}</td>
                    <td></td>
                    <td onclick="event.stopPropagation();">
                        <a href="javascript:void(0)" class="text-info mr-2" title="修改" data-field-edit><i class="fa fa-edit"></i></a>
                        <a href="javascript:void(0)" class="text-danger" title="删除" data-field-del><i class="fa fa-trash-o"></i></a>
                    </td>
                    <td onclick="event.stopPropagation();">
                        <input type="checkbox" class="fieldCheckbox" onclick="event.stopPropagation();" />
                    </td>
                </tr>`);

        refreshFieldMappingTable();
    });
}
// 绑定删除字段映射点击事件
function bindFieldMappingDelClick(){
    $fieldDelBtn().unbind("click").bind('click', function () {
        const checkedRows = [];
        $fieldCheckboxes().filter(':checked').each(function () {
            checkedRows.push($(this).closest('tr'));
        });
        if (checkedRows.length > 0) {
            // 删除选中的行
            checkedRows.forEach(function($row) {
                $row.remove();
            });
            
            // 更新行号
            updateRowNumbers();
            // 更新映射参数（会自动显示/隐藏表格）
            initFieldMappingParams();
            // 更新删除按钮状态
            $fieldDelBtn().prop('disabled', $fieldCheckboxes().filter(':checked').length === 0);
        }
    });
}

// ===== 字段映射增强功能 =====

// 通用：添加一行映射（含去重检查）
function addFieldMappingRow(sField, tField, pk, showToast) {
    sField = sField == null ? "" : sField;
    tField = tField == null ? "" : tField;
    if (sField === "" && tField === "") {
        return false;
    }
    // 去重
    let repeated = false;
    $fieldMappingList().find("tr").each(function () {
        if ($(this).find("td:eq(1)").text() === sField && $(this).find("td:eq(2)").text() === tField) {
            repeated = true;
            return false;
        }
    });
    if (repeated) {
        if (showToast !== false) {
            bootGrowl("字段映射已存在: " + sField + " -> " + tField, "danger");
        }
        return false;
    }
    const rowIndex = $fieldMappingList().find("tr").length + 1;
    const pkHtml = pk ? '<i title="主键" class="fa fa-key text-warning"></i>' : '';
    $fieldMappingList().append(`<tr title='双击设置/取消主键 | 拖动排序'>
                    <td>${rowIndex}</td>
                    <td>${escapeHtml(sField)}</td>
                    <td>${escapeHtml(tField)}</td>
                    <td>${pkHtml}</td>
                    <td onclick="event.stopPropagation();">
                        <a href="javascript:void(0)" class="text-info mr-2" title="修改" data-field-edit><i class="fa fa-edit"></i></a>
                        <a href="javascript:void(0)" class="text-danger" title="删除" data-field-del><i class="fa fa-trash-o"></i></a>
                    </td>
                    <td onclick="event.stopPropagation();">
                        <input type="checkbox" class="fieldCheckbox" onclick="event.stopPropagation();" />
                    </td>
                </tr>`);
    return true;
}

// 刷新字段映射表格（行号 + 参数 + 各事件绑定）
function refreshFieldMappingTable() {
    updateRowNumbers();
    initFieldMappingParams();
    bindFieldMappingDrop();
    bindFieldMappingListClick();
    bindFieldMappingCheckbox();
    bindRowActions();
}

// 获取源表字段值列表
function getSourceFieldValues() {
    const values = [];
    $("#source_table_field option").each(function () {
        const v = $(this).val();
        if (v && v !== "") {
            values.push(v);
        }
    });
    return values;
}

// 获取目标表字段值列表
function getTargetFieldValues() {
    const values = [];
    $("#target_table_field option").each(function () {
        const v = $(this).val();
        if (v && v !== "") {
            values.push(v);
        }
    });
    return values;
}

// 共享：按忽略大小写构建目标字段索引（key=大写字段名 -> 原字段名）
function buildTargetFieldIndex() {
    const idx = {};
    getTargetFieldValues().forEach(function (tf) {
        const k = (tf || "").toUpperCase();
        if (!(k in idx)) {
            idx[k] = tf;
        }
    });
    return idx;
}

// 同名字段自动映射计算（忽略大小写）：返回映射列表与未匹配的源/目标字段
function computeSameNameMapping() {
    const idx = buildTargetFieldIndex();
    const mappedT = {};
    const list = [];
    const unmatchedS = [];
    getSourceFieldValues().forEach(function (sf) {
        const t = idx[(sf || "").toUpperCase()];
        if (t && mappedT[t] === undefined) {
            list.push({s: sf, t: t});
            mappedT[t] = true;
        } else {
            unmatchedS.push(sf);
        }
    });
    const unmatchedT = getTargetFieldValues().filter(function (tf) {
        return mappedT[tf] === undefined;
    });
    return {list: list, unmatchedS: unmatchedS, unmatchedT: unmatchedT};
}

// 自动映射结果提示：列出未匹配字段，提醒用户手动修改（有问题的用户自己处理）
function showFieldMappingHint(result) {
    $("#fieldMappingHint").remove();
    if (result.list.length === 0 && result.unmatchedS.length === 0 && result.unmatchedT.length === 0) {
        return;
    }
    const sList = result.unmatchedS.length ? result.unmatchedS.join(", ") : "无";
    const tList = result.unmatchedT.length ? result.unmatchedT.join(", ") : "无";
    const html = '<div id="fieldMappingHint" class="mt-2 p-3 rounded border text-sm" style="background:#fff8e6;border-color:#f5c96d;color:#7a5b00;">'
        + '<i class="fa fa-info-circle"></i> 字段自动映射（同名，忽略大小写）已匹配 ' + result.list.length + ' 条'
        + (result.unmatchedS.length ? '；<b>未匹配源字段</b>：' + escapeHtml(sList) : '')
        + (result.unmatchedT.length ? '；<b>未匹配目标字段</b>：' + escapeHtml(tList) : '')
        + '。请通过下方「同名映射 / 同序映射 / 手动编辑」建立映射关系，无需同步的字段可不映射。'
        + '<a href="javascript:void(0)" onclick="$(\'#fieldMappingHint\').remove()" class="ml-2 text-primary">关闭</a>'
        + '</div>';
    $fieldMappingList().closest('table').after(html);
}

// 同名映射：自动将源表与目标表中同名字段建立映射（忽略大小写，异构库大小写差异也能匹配）
function bindFieldSameNameClick() {
    $("#fieldSameNameBtn").unbind("click").bind("click", function () {
        const result = computeSameNameMapping();
        let added = 0;
        result.list.forEach(function (m) {
            if (addFieldMappingRow(m.s, m.t, false, false)) {
                added++;
            }
        });
        refreshFieldMappingTable();
        showFieldMappingHint(result);
        if (added > 0) {
            bootGrowl("同名映射完成，新增 " + added + " 条.", "success");
        } else {
            bootGrowl("未找到可映射的同名字段.", "info");
        }
    });
}

// 字段映射自动补齐：打开页面时若无映射关系，自动按同名（忽略大小写）建立映射，并列出未匹配字段供手动修改
function autoFillFieldMappingIfEmpty() {
    const $list = $fieldMappingList();
    if ($list.find("tr").length > 0) {
        return;
    }
    const result = computeSameNameMapping();
    if (result.list.length === 0 && result.unmatchedS.length === 0 && result.unmatchedT.length === 0) {
        return;
    }
    if (result.list.length > 0) {
        result.list.forEach(function (m) {
            addFieldMappingRow(m.s, m.t, false, false);
        });
        refreshFieldMappingTable();
    }
    showFieldMappingHint(result);
}

// 同行映射：按索引位置将源表字段与目标字段一一对应
function bindFieldSameIndexClick() {
    $("#fieldSameIndexBtn").unbind("click").bind("click", function () {
        const sources = getSourceFieldValues();
        const targets = getTargetFieldValues();
        const min = Math.min(sources.length, targets.length);
        let added = 0;
        for (let i = 0; i < min; i++) {
            if (addFieldMappingRow(sources[i], targets[i], false, false)) {
                added++;
            }
        }
        refreshFieldMappingTable();
        if (added > 0) {
            bootGrowl("同行映射完成，新增 " + added + " 条.", "success");
        } else {
            bootGrowl("无可用字段或字段已全部映射.", "info");
        }
    });
}

// 清空映射：清空所有字段映射关系
function bindFieldClearClick() {
    $("#fieldClearBtn").unbind("click").bind("click", function () {
        const $list = $fieldMappingList();
        if ($list.find("tr").length === 0) {
            bootGrowl("当前没有字段映射关系.", "info");
            return;
        }
        showConfirm({
            title: "清空映射",
            message: "确定清空当前所有字段映射关系吗？",
            icon: "warning",
            confirmText: "清空",
            confirmType: "danger",
            onConfirm: function () {
                $list.empty();
                refreshFieldMappingTable();
                bootGrowl("已清空所有字段映射关系.", "success");
            }
        });
    });
}

// 手动编辑映射关系弹窗（新增或修改）
function showManualEditDialog(editRow, onSave) {
    const sourceOptions = $("#source_table_field option").map(function () {
        return '<option value="' + escapeHtml($(this).val()) + '">' + escapeHtml($(this).text()) + '</option>';
    }).get().join("");
    const targetOptions = $("#target_table_field option").map(function () {
        return '<option value="' + escapeHtml($(this).val()) + '">' + escapeHtml($(this).text()) + '</option>';
    }).get().join("");

    let curSource = "", curTarget = "", curPk = false;
    if (editRow && editRow.length) {
        curSource = editRow.find("td:eq(1)").text();
        curTarget = editRow.find("td:eq(2)").text();
        curPk = editRow.find("td:eq(3) i").length > 0;
    }

    const body = '<div class="grid grid-cols-2 gap-3">' +
        '<div class="form-item">' +
            '<label class="form-label">源字段</label>' +
            '<select id="manualEditSource" class="form-control">' + sourceOptions + '</select>' +
        '</div>' +
        '<div class="form-item">' +
            '<label class="form-label">目标字段</label>' +
            '<select id="manualEditTarget" class="form-control">' + targetOptions + '</select>' +
        '</div>' +
    '</div>' +
    '<div class="form-item mt-3">' +
        '<label class="checkbox-inline">' +
            '<input type="checkbox" id="manualEditPk" ' + (curPk ? 'checked' : '') + ' /> 设为目标主键' +
        '</label>' +
    '</div>';

    showConfirm({
        title: editRow && editRow.length ? "修改映射关系" : "手动编辑映射关系",
        body: body,
        confirmText: "保存",
        confirmType: "primary",
        onConfirm: function () {
            const s = $("#manualEditSource").val();
            const t = $("#manualEditTarget").val();
            if (!s && !t) {
                bootGrowl("至少选择一个字段.", "danger");
                return;
            }
            const pk = $("#manualEditPk").is(":checked");
            onSave(s, t, pk);
        }
    });

    // 回填当前值
    $("#manualEditSource").val(curSource);
    $("#manualEditTarget").val(curTarget);
}

// 手动编辑映射关系按钮：新增一条映射
function bindFieldManualClick() {
    $("#fieldManualBtn").unbind("click").bind("click", function () {
        showManualEditDialog(null, function (s, t, pk) {
            if (addFieldMappingRow(s, t, pk)) {
                refreshFieldMappingTable();
                bootGrowl("已添加映射关系.", "success");
            } else {
                bootGrowl("字段映射已存在，添加失败.", "danger");
            }
        });
    });
}

// 行内操作：修改 / 删除
function bindRowActions() {
    const $list = $fieldMappingList();

    $list.off("click", "[data-field-edit]").on("click", "[data-field-edit]", function (e) {
        e.preventDefault();
        e.stopPropagation();
        const $row = $(this).closest("tr");
        showManualEditDialog($row, function (s, t, pk) {
            $row.find("td:eq(1)").text(s);
            $row.find("td:eq(2)").text(t);
            $row.find("td:eq(3)").html(pk ? '<i title="主键" class="fa fa-key text-warning"></i>' : '');
            initFieldMappingParams();
            bootGrowl("修改映射成功.", "success");
        });
    });

    $list.off("click", "[data-field-del]").on("click", "[data-field-del]", function (e) {
        e.preventDefault();
        e.stopPropagation();
        const $row = $(this).closest("tr");
        showConfirm({
            title: "删除映射",
            message: "确定删除该条映射关系吗？",
            icon: "warning",
            confirmText: "删除",
            confirmType: "danger",
            onConfirm: function () {
                $row.remove();
                refreshFieldMappingTable();
                bootGrowl("已删除该条映射关系.", "success");
            }
        });
    });
}

$(function() {
    // 定义返回函数，子页面返回
    let mappingId = $("#mappingId").val();
    window.backIndexPage = function () {
        doLoader("/mapping/page/edit?id=" + mappingId);
    };

    // 绑定下拉选择事件自动匹配相似字段事件
    let targetTableSelect = bindFieldSelect($('#target_table_field'));
    let sourceTableSelect = bindFieldSelect($('#source_table_field'), function(data) {
        targetTableSelect.setValues(data);
    });

    // 绑定表字段关系点击事件
    initFieldMappingParams();
    // 绑定表格拖拽事件
    bindFieldMappingDrop();
    // 绑定删除表字段映射事件
    bindFieldMappingCheckbox();
    // 绑定添加字段映射关系事件
    bindFieldMappingAddClick(sourceTableSelect, targetTableSelect);
    // 绑定删除字段映射关系事件
    bindFieldMappingDelClick();
    // 绑定字段关系点击事件
    bindFieldMappingListClick();
    // 绑定同名映射 / 同行映射 / 清空映射 / 手动编辑映射关系
    bindFieldSameNameClick();
    bindFieldSameIndexClick();
    bindFieldClearClick();
    bindFieldManualClick();
    bindRowActions();
    // 字段映射自动补齐：映射为空时按同名(忽略大小写)自动建立映射，并提示未匹配字段
    autoFillFieldMappingIfEmpty();

    //保存
    $("#tableGroupSubmitBtn").click(function () {
        let $form = $("#table_group_modify_form");
        if (validateForm($form)) {
            //保存驱动配置
            doPoster("/tableGroup/edit", $form.serializeJson(), function (data) {
                if (data.success === true) {
                    bootGrowl("修改表字段映射成功!", "success");
                    backIndexPage();
                } else {
                    bootGrowl(data.message, "danger");
                }
            });
        }
    });
});