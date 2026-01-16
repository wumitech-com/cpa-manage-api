// ==================== 注册任务管理相关函数 ====================

let selectedTaskIds = new Set();

// 页面加载完成后初始化表单事件
document.addEventListener('DOMContentLoaded', function() {
    // 阻止表单默认提交行为
    const createTaskForm = document.getElementById('create-task-form');
    if (createTaskForm) {
        createTaskForm.addEventListener('submit', function(e) {
            e.preventDefault();
            createRegisterTask();
        });
    }
});

/**
 * 显示创建任务模态框 - 使用最简单直接的方式
 */
function showCreateTaskModal() {
    console.log('showCreateTaskModal 被调用');
    
    const modalElement = document.getElementById('createTaskModal');
    if (!modalElement) {
        console.error('找不到模态框元素: createTaskModal');
        alert('找不到创建任务对话框，请刷新页面重试');
        return;
    }
    
    // 移除所有可能隐藏模态框的类
    modalElement.classList.remove('fade');
    modalElement.classList.add('show');
    
    // 强制设置模态框样式 - 确保可见
    modalElement.style.cssText = `
        display: block !important;
        position: fixed !important;
        top: 0 !important;
        left: 0 !important;
        width: 100% !important;
        height: 100% !important;
        z-index: 1055 !important;
        overflow: auto !important;
        visibility: visible !important;
        opacity: 1 !important;
    `;
    modalElement.setAttribute('aria-hidden', 'false');
    modalElement.setAttribute('aria-modal', 'true');
    
    // 确保 modal-dialog 可见
    const dialog = modalElement.querySelector('.modal-dialog');
    if (dialog) {
        dialog.style.cssText = `
            display: block !important;
            position: relative !important;
            margin: 1.75rem auto !important;
            max-width: 800px !important;
            z-index: 1056 !important;
            visibility: visible !important;
            opacity: 1 !important;
            transform: none !important;
        `;
    }
    
    // 确保 modal-content 可见
    const content = modalElement.querySelector('.modal-content');
    if (content) {
        content.style.cssText = `
            display: block !important;
            visibility: visible !important;
            opacity: 1 !important;
        `;
    }
    
    // 移除旧的 backdrop（如果存在）
    const oldBackdrops = document.querySelectorAll('.modal-backdrop, #createTaskModalBackdrop');
    oldBackdrops.forEach(b => b.remove());
    
    // 创建新的 backdrop
    const backdrop = document.createElement('div');
    backdrop.id = 'createTaskModalBackdrop';
    backdrop.style.cssText = `
        position: fixed !important;
        top: 0 !important;
        left: 0 !important;
        width: 100% !important;
        height: 100% !important;
        z-index: 1050 !important;
        background-color: rgba(0, 0, 0, 0.5) !important;
    `;
    document.body.appendChild(backdrop);
    
    // 设置 body 样式
    document.body.classList.add('modal-open');
    document.body.style.overflow = 'hidden';
    
    // 点击 backdrop 关闭
    backdrop.addEventListener('click', function(e) {
        if (e.target === backdrop) {
            hideCreateTaskModal();
        }
    });
    
    // ESC 键关闭
    const escHandler = function(e) {
        if (e.key === 'Escape') {
            hideCreateTaskModal();
            document.removeEventListener('keydown', escHandler);
        }
    };
    document.addEventListener('keydown', escHandler);
    
    console.log('模态框已强制显示');
}

/**
 * 隐藏创建任务模态框
 */
function hideCreateTaskModal() {
    const modalElement = document.getElementById('createTaskModal');
    if (modalElement) {
        modalElement.classList.remove('show');
        modalElement.style.display = 'none';
        modalElement.setAttribute('aria-hidden', 'true');
        modalElement.removeAttribute('aria-modal');
    }
    
    // 移除所有 backdrop
    const backdrops = document.querySelectorAll('.modal-backdrop, #createTaskModalBackdrop');
    backdrops.forEach(backdrop => backdrop.remove());
    
    // 移除 modal-open 类
    document.body.classList.remove('modal-open');
    document.body.style.overflow = '';
}

/**
 * 创建注册任务
 */
async function createRegisterTask() {
    try {
        const taskType = document.getElementById('task-type').value;
        const phoneIdsText = document.getElementById('phone-ids').value.trim();
        const serverIp = document.getElementById('server-ip').value.trim();
        const maxConcurrency = parseInt(document.getElementById('max-concurrency').value) || 10;
        const targetCountPerDevice = parseInt(document.getElementById('target-count-per-device').value) || 1;
        const tiktokVersionDir = document.getElementById('tiktok-version-dir').value.trim();

        // 验证必填字段
        if (!taskType) {
            showAlert('请选择任务类型', 'warning');
            return;
        }
        if (!phoneIdsText) {
            showAlert('请输入设备ID列表', 'warning');
            return;
        }
        if (!serverIp) {
            showAlert('请输入服务器IP', 'warning');
            return;
        }
        if (!tiktokVersionDir) {
            showAlert('请输入TikTok版本目录', 'warning');
            return;
        }

        // 解析设备ID列表
        const phoneIds = phoneIdsText.split('\n')
            .map(id => id.trim())
            .filter(id => id.length > 0);

        if (phoneIds.length === 0) {
            showAlert('设备ID列表不能为空', 'warning');
            return;
        }

        // 构建 resetParams
        const resetParams = {};
        const country = document.getElementById('reset-country').value.trim();
        if (country) resetParams.country = country;
        
        const sdk = document.getElementById('reset-sdk').value.trim();
        if (sdk) resetParams.sdk = sdk;
        
        const imagePath = document.getElementById('reset-image-path').value.trim();
        if (imagePath) resetParams.imagePath = imagePath;
        
        const gaidTag = document.getElementById('reset-gaid-tag').value.trim();
        if (gaidTag) resetParams.gaidTag = gaidTag;
        
        const dynamicIpChannel = document.getElementById('reset-dynamic-ip-channel').value.trim();
        if (dynamicIpChannel) resetParams.dynamicIpChannel = dynamicIpChannel;
        
        const staticIpChannel = document.getElementById('reset-static-ip-channel').value.trim();
        if (staticIpChannel) resetParams.staticIpChannel = staticIpChannel;
        
        const biz = document.getElementById('reset-biz').value.trim();
        if (biz) resetParams.biz = biz;

        // 构建请求数据
        const requestData = {
            phoneIds: phoneIds,
            serverIp: serverIp,
            maxConcurrency: maxConcurrency,
            targetCountPerDevice: targetCountPerDevice,
            tiktokVersionDir: tiktokVersionDir,
            resetParams: resetParams
        };

        // 根据任务类型调用不同的接口
        const apiUrl = taskType === 'FAKE_EMAIL' 
            ? '/api/tt-register/parallel' 
            : '/api/tt-register/outlook/parallel';

        showAlert('正在创建任务...', 'info');

        console.log('发送创建任务请求:', apiUrl, requestData);
        
        const response = await fetch(apiUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(requestData)
        });

        console.log('收到响应:', response.status, response.statusText);
        
        if (!response.ok) {
            const errorText = await response.text();
            console.error('HTTP错误:', errorText);
            showAlert('请求失败: ' + response.status + ' ' + response.statusText, 'danger');
            return;
        }

        const result = await response.json();
        console.log('响应结果:', result);

        if (result.success) {
            showAlert('任务创建成功！任务ID: ' + (result.taskId || result.taskIds?.join(', ') || '已创建'), 'success');
            
            // 关闭模态框
            hideCreateTaskModal();
            
            // 重置表单
            const form = document.getElementById('create-task-form');
            if (form) {
                form.reset();
            }
            
            // 刷新任务列表
            setTimeout(() => {
                loadRegisterTasks();
            }, 1000);
        } else {
            showAlert('任务创建失败: ' + (result.message || '未知错误'), 'danger');
        }

    } catch (error) {
        console.error('创建任务失败:', error);
        showAlert('创建任务失败: ' + (error.message || '网络错误或服务器异常'), 'danger');
    }
}

/**
 * 加载注册任务列表
 */
async function loadRegisterTasks() {
    try {
        const status = document.getElementById('filter-status')?.value || '';
        const taskType = document.getElementById('filter-task-type')?.value || '';
        const serverIp = document.getElementById('filter-server-ip')?.value || '';
        const phoneId = document.getElementById('filter-phone-id')?.value || '';
        const page = parseInt(document.getElementById('filter-page')?.value) || 1;
        const size = parseInt(document.getElementById('filter-size')?.value) || 20;

        const params = new URLSearchParams();
        if (status) params.append('status', status);
        if (taskType) params.append('taskType', taskType);
        if (serverIp) params.append('serverIp', serverIp);
        if (phoneId) params.append('phoneId', phoneId);
        params.append('page', page);
        params.append('size', size);

        const response = await fetch(`/api/tt-register/task/list?${params.toString()}`);
        const result = await response.json();

        if (result.success) {
            renderRegisterTasksTable(result.data);
            renderRegisterTasksPagination(result.data, page);
        } else {
            document.getElementById('register-tasks-table-body').innerHTML = 
                '<tr><td colspan="10" class="text-center text-danger">加载失败: ' + (result.message || '未知错误') + '</td></tr>';
        }

    } catch (error) {
        console.error('加载任务列表失败:', error);
        document.getElementById('register-tasks-table-body').innerHTML = 
            '<tr><td colspan="10" class="text-center text-danger">加载失败: ' + error.message + '</td></tr>';
    }
}

/**
 * 渲染任务列表表格
 */
function renderRegisterTasksTable(data) {
    const tbody = document.getElementById('register-tasks-table-body');
    
    if (!data || !data.list || data.list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="10" class="text-center text-muted">暂无任务</td></tr>';
        return;
    }

    let html = '';
    data.list.forEach(task => {
        const statusBadge = getStatusBadge(task.status);
        const taskTypeText = task.taskType === 'FAKE_EMAIL' ? '假邮箱注册' : '真邮箱注册';
        const targetCountText = task.targetCount === 0 ? '无限循环' : task.targetCount;
        const isSelected = selectedTaskIds.has(task.taskId);
        
        html += `
            <tr>
                <td>
                    <input type="checkbox" class="task-checkbox" value="${task.taskId}" 
                           ${isSelected ? 'checked' : ''} onchange="toggleTaskSelection('${task.taskId}')">
                </td>
                <td><small>${task.taskId}</small></td>
                <td>${taskTypeText}</td>
                <td><small>${task.phoneId}</small></td>
                <td>${task.serverIp}</td>
                <td>${targetCountText}</td>
                <td>${statusBadge}</td>
                <td><small>${formatDateTime(task.createdAt)}</small></td>
                <td><small>${formatDateTime(task.updatedAt)}</small></td>
                <td>
                    <button class="btn btn-sm btn-info" onclick="viewTaskDetail('${task.taskId}')" title="查看详情">
                        <i class="bi bi-eye"></i>
                    </button>
                    ${task.status === 'RUNNING' || task.status === 'PENDING' ? `
                        <button class="btn btn-sm btn-warning" onclick="stopTaskById('${task.taskId}')" title="停止">
                            <i class="bi bi-stop-fill"></i>
                        </button>
                    ` : ''}
                    ${task.status === 'FAILED' ? `
                        <button class="btn btn-sm btn-success" onclick="resetTask('${task.taskId}')" title="重置">
                            <i class="bi bi-arrow-clockwise"></i>
                        </button>
                    ` : ''}
                    <button class="btn btn-sm btn-danger" onclick="deleteTaskById('${task.taskId}')" title="删除">
                        <i class="bi bi-trash"></i>
                    </button>
                </td>
            </tr>
        `;
    });

    tbody.innerHTML = html;
}

/**
 * 渲染分页
 */
function renderRegisterTasksPagination(data, currentPage) {
    const pagination = document.getElementById('register-tasks-pagination');
    if (!data || !data.totalPages || data.totalPages <= 1) {
        pagination.innerHTML = '';
        return;
    }

    let html = '';
    const totalPages = data.totalPages;

    // 上一页
    html += `<li class="page-item ${currentPage === 1 ? 'disabled' : ''}">
        <a class="page-link" href="#" onclick="goToPage(${currentPage - 1}); return false;">上一页</a>
    </li>`;

    // 页码
    for (let i = 1; i <= totalPages; i++) {
        if (i === 1 || i === totalPages || (i >= currentPage - 2 && i <= currentPage + 2)) {
            html += `<li class="page-item ${i === currentPage ? 'active' : ''}">
                <a class="page-link" href="#" onclick="goToPage(${i}); return false;">${i}</a>
            </li>`;
        } else if (i === currentPage - 3 || i === currentPage + 3) {
            html += `<li class="page-item disabled"><span class="page-link">...</span></li>`;
        }
    }

    // 下一页
    html += `<li class="page-item ${currentPage === totalPages ? 'disabled' : ''}">
        <a class="page-link" href="#" onclick="goToPage(${currentPage + 1}); return false;">下一页</a>
    </li>`;

    pagination.innerHTML = html;
}

/**
 * 跳转到指定页码
 */
function goToPage(page) {
    document.getElementById('filter-page').value = page;
    loadRegisterTasks();
}

/**
 * 获取状态徽章
 */
function getStatusBadge(status) {
    const badges = {
        'PENDING': '<span class="badge bg-secondary">待执行</span>',
        'RUNNING': '<span class="badge bg-primary">运行中</span>',
        'COMPLETED': '<span class="badge bg-success">已完成</span>',
        'FAILED': '<span class="badge bg-danger">失败</span>',
        'STOPPED': '<span class="badge bg-warning">已停止</span>'
    };
    return badges[status] || '<span class="badge bg-secondary">' + status + '</span>';
}

/**
 * 切换任务选择
 */
function toggleTaskSelection(taskId) {
    if (selectedTaskIds.has(taskId)) {
        selectedTaskIds.delete(taskId);
    } else {
        selectedTaskIds.add(taskId);
    }
    updateSelectAllCheckbox();
}

/**
 * 全选/取消全选
 */
function toggleSelectAllTasks() {
    const selectAll = document.getElementById('select-all-tasks').checked;
    const checkboxes = document.querySelectorAll('.task-checkbox');
    
    checkboxes.forEach(checkbox => {
        checkbox.checked = selectAll;
        if (selectAll) {
            selectedTaskIds.add(checkbox.value);
        } else {
            selectedTaskIds.delete(checkbox.value);
        }
    });
}

/**
 * 更新全选复选框状态
 */
function updateSelectAllCheckbox() {
    const checkboxes = document.querySelectorAll('.task-checkbox');
    const selectAll = document.getElementById('select-all-tasks');
    if (checkboxes.length === 0) {
        selectAll.checked = false;
        return;
    }
    selectAll.checked = Array.from(checkboxes).every(cb => cb.checked);
}

/**
 * 查看任务详情
 */
async function viewTaskDetail(taskId) {
    try {
        const response = await fetch(`/api/tt-register/task/${taskId}`);
        const result = await response.json();
        
        if (result.success) {
            const task = result.data;
            const detailHtml = `
                <div class="modal fade" id="taskDetailModal" tabindex="-1">
                    <div class="modal-dialog modal-lg">
                        <div class="modal-content">
                            <div class="modal-header">
                                <h5 class="modal-title">任务详情</h5>
                                <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                            </div>
                            <div class="modal-body">
                                <table class="table table-bordered">
                                    <tr><th>任务ID</th><td>${task.taskId}</td></tr>
                                    <tr><th>任务类型</th><td>${task.taskType === 'FAKE_EMAIL' ? '假邮箱注册' : '真邮箱注册'}</td></tr>
                                    <tr><th>设备ID</th><td>${task.phoneId}</td></tr>
                                    <tr><th>服务器IP</th><td>${task.serverIp}</td></tr>
                                    <tr><th>目标数量</th><td>${task.targetCount === 0 ? '无限循环' : task.targetCount}</td></tr>
                                    <tr><th>TikTok版本</th><td>${task.tiktokVersionDir || '-'}</td></tr>
                                    <tr><th>状态</th><td>${getStatusBadge(task.status)}</td></tr>
                                    <tr><th>国家代码</th><td>${task.country || '-'}</td></tr>
                                    <tr><th>SDK版本</th><td>${task.sdk || '-'}</td></tr>
                                    <tr><th>镜像路径</th><td>${task.imagePath || '-'}</td></tr>
                                    <tr><th>GAID标签</th><td>${task.gaidTag || '-'}</td></tr>
                                    <tr><th>动态IP渠道</th><td>${task.dynamicIpChannel || '-'}</td></tr>
                                    <tr><th>静态IP渠道</th><td>${task.staticIpChannel || '-'}</td></tr>
                                    <tr><th>业务标识</th><td>${task.biz || '-'}</td></tr>
                                    <tr><th>创建时间</th><td>${formatDateTime(task.createdAt)}</td></tr>
                                    <tr><th>更新时间</th><td>${formatDateTime(task.updatedAt)}</td></tr>
                                </table>
                            </div>
                            <div class="modal-footer">
                                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">关闭</button>
                            </div>
                        </div>
                    </div>
                </div>
            `;
            
            // 移除旧的模态框
            const oldModal = document.getElementById('taskDetailModal');
            if (oldModal) oldModal.remove();
            
            // 添加新模态框
            document.body.insertAdjacentHTML('beforeend', detailHtml);
            
            // 显示模态框
            const detailModalElement = document.getElementById('taskDetailModal');
            // 先移除 aria-hidden 属性（如果存在）
            detailModalElement.removeAttribute('aria-hidden');
            detailModalElement.setAttribute('aria-modal', 'true');
            
            const modal = new bootstrap.Modal(detailModalElement, {
                backdrop: true,
                keyboard: true,
                focus: true
            });
            modal.show();
            
            // 模态框关闭后移除
            detailModalElement.addEventListener('hidden.bs.modal', function() {
                this.remove();
            }, { once: true });
        } else {
            showAlert('获取任务详情失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('查看任务详情失败:', error);
        showAlert('查看任务详情失败: ' + error.message, 'danger');
    }
}

/**
 * 停止任务
 */
async function stopTaskById(taskId) {
    if (!confirm('确定要停止这个任务吗？')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/tt-register/task/stop/${taskId}`, {
            method: 'POST'
        });
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务已停止', 'success');
            loadRegisterTasks();
        } else {
            showAlert('停止任务失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('停止任务失败:', error);
        showAlert('停止任务失败: ' + error.message, 'danger');
    }
}

/**
 * 重置任务
 */
async function resetTask(taskId) {
    if (!confirm('确定要重置这个任务吗？任务将重新执行。')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/tt-register/task/reset/${taskId}`, {
            method: 'POST'
        });
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务已重置，将重新执行', 'success');
            loadRegisterTasks();
        } else {
            showAlert('重置任务失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('重置任务失败:', error);
        showAlert('重置任务失败: ' + error.message, 'danger');
    }
}

/**
 * 删除任务
 */
async function deleteTaskById(taskId) {
    if (!confirm('确定要删除这个任务吗？此操作不可恢复。')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/tt-register/task/${taskId}`, {
            method: 'DELETE'
        });
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务已删除', 'success');
            selectedTaskIds.delete(taskId);
            loadRegisterTasks();
        } else {
            showAlert('删除任务失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('删除任务失败:', error);
        showAlert('删除任务失败: ' + error.message, 'danger');
    }
}

/**
 * 批量停止任务
 */
async function batchStopTasks() {
    if (selectedTaskIds.size === 0) {
        showAlert('请先选择要停止的任务', 'warning');
        return;
    }
    
    if (!confirm(`确定要停止选中的 ${selectedTaskIds.size} 个任务吗？`)) {
        return;
    }
    
    try {
        const response = await fetch('/api/tt-register/task/stop/batch', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                taskIds: Array.from(selectedTaskIds)
            })
        });
        const result = await response.json();
        
        if (result.success) {
            showAlert(`已停止 ${result.data?.count || selectedTaskIds.size} 个任务`, 'success');
            selectedTaskIds.clear();
            loadRegisterTasks();
        } else {
            showAlert('批量停止失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('批量停止任务失败:', error);
        showAlert('批量停止任务失败: ' + error.message, 'danger');
    }
}

/**
 * 批量删除任务
 */
async function batchDeleteTasks() {
    if (selectedTaskIds.size === 0) {
        showAlert('请先选择要删除的任务', 'warning');
        return;
    }
    
    if (!confirm(`确定要删除选中的 ${selectedTaskIds.size} 个任务吗？此操作不可恢复。`)) {
        return;
    }
    
    try {
        const response = await fetch('/api/tt-register/task/batch', {
            method: 'DELETE',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                taskIds: Array.from(selectedTaskIds)
            })
        });
        const result = await response.json();
        
        if (result.success) {
            showAlert(`已删除 ${result.data?.count || selectedTaskIds.size} 个任务`, 'success');
            selectedTaskIds.clear();
            loadRegisterTasks();
        } else {
            showAlert('批量删除失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('批量删除任务失败:', error);
        showAlert('批量删除任务失败: ' + error.message, 'danger');
    }
}

