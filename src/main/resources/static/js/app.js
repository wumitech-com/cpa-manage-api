// 全局变量
let currentPage = {
    devicePool: 1,
    accountLibrary: 1
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function() {
    loadDashboard();
});

// 显示指定区域
function showSection(sectionName, event) {
    // 隐藏所有区域
    const sections = document.querySelectorAll('.section');
    sections.forEach(section => section.style.display = 'none');
    
    // 显示指定区域
    document.getElementById(sectionName + '-section').style.display = 'block';
    
    // 更新导航状态
    const navLinks = document.querySelectorAll('.nav-link');
    navLinks.forEach(link => link.classList.remove('active'));
    
    // 只有当event存在时才设置active状态
    if (event && event.target) {
        event.target.classList.add('active');
    } else {
        // 如果没有event，通过href属性找到对应的链接
        const targetLink = document.querySelector(`a[href="#"][onclick*="${sectionName}"]`);
        if (targetLink) {
            targetLink.classList.add('active');
        }
    }
    
    // 根据区域加载对应数据
    switch(sectionName) {
        case 'dashboard':
            loadDashboard();
            break;
        case 'device-pool':
            loadDevicePool();
            break;
        case 'account-library':
            loadAccountLibrary();
            break;
        case 'statistics':
            loadStatistics();
            break;
        case 'task-management':
            loadTaskManagement();
            // 默认加载定时任务标签页
            break;
        case 'register-monitor':
            refreshRegisterStatus();
            break;
        case 'edit-bio-monitor':
            refreshEditBioStatus();
            break;
        case 'auto-nurture':
            loadAutoNurtureTasks();
            break;
        case 'tt-register':
            // 加载页面时不需要预加载数据
            break;
        case 'tt-parallel-register':
            // 加载页面时不需要预加载数据
            break;
        case 'outlook-parallel-register':
            // 加载页面时不需要预加载数据
            break;
    }
}

// 加载控制台数据
async function loadDashboard() {
    try {
        // 加载整体统计
        const response = await fetch('/api/statistics/overview');
        const data = await response.json();
        
        if (data.success) {
            const stats = data.data;
            document.getElementById('total-devices').textContent = stats.totalDevices || 0;
            document.getElementById('total-accounts').textContent = stats.totalAccounts || 0;
            document.getElementById('need-register').textContent = stats.needRegisterCount || 0;
            document.getElementById('need-nurture').textContent = stats.needNurtureCount || 0;
        }
        
        // 加载最近活动
        loadRecentActivities();
        
    } catch (error) {
        console.error('加载控制台数据失败:', error);
        showAlert('加载控制台数据失败', 'danger');
    }
}

// 加载最近活动
function loadRecentActivities() {
    const activitiesContainer = document.getElementById('recent-activities');
    activitiesContainer.innerHTML = `
        <div class="list-group">
            <div class="list-group-item">
                <div class="d-flex w-100 justify-content-between">
                    <h6 class="mb-1">系统启动</h6>
                    <small>刚刚</small>
                </div>
                <p class="mb-1">CPA管理后台系统已启动</p>
            </div>
            <div class="list-group-item">
                <div class="d-flex w-100 justify-content-between">
                    <h6 class="mb-1">定时任务就绪</h6>
                    <small>刚刚</small>
                </div>
                <p class="mb-1">每日定时任务已配置完成</p>
            </div>
        </div>
    `;
}

// 加载设备池数据
async function loadDevicePool(page = 1) {
    try {
        const country = document.getElementById('pool-country-filter').value;
        const status = document.getElementById('pool-status-filter').value;
        
        const params = new URLSearchParams({
            pageNum: page,
            pageSize: 10
        });
        
        if (country) params.append('country', country);
        if (status) params.append('status', status);
        
        const response = await fetch(`/api/devices/pool?${params}`);
        const data = await response.json();
        
        if (data.success) {
            renderDevicePoolTable(data.data);
            renderPagination('pool-pagination', data, 'device-pool');
        } else {
            showAlert('加载设备池数据失败', 'danger');
        }
        
    } catch (error) {
        console.error('加载设备池数据失败:', error);
        showAlert('加载设备池数据失败', 'danger');
    }
}

// 渲染设备池表格
function renderDevicePoolTable(devices) {
    const tbody = document.getElementById('device-pool-table');
    
    if (devices.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="text-center">暂无数据</td></tr>';
        return;
    }
    
    tbody.innerHTML = devices.map(device => `
        <tr>
            <td><input type="checkbox" value="${device.id}" class="pool-checkbox"></td>
            <td>${device.phoneId || '-'}</td>
            <td>${device.country || '-'}</td>
            <td>${device.pkgName || '-'}</td>
            <td><span class="badge ${getStatusBadgeClass(device.status)}">${getStatusText(device.status)}</span></td>
            <td><span class="badge ${device.emailStatus === 1 ? 'bg-success' : 'bg-secondary'}">${device.emailStatus === 1 ? '已绑定' : '未绑定'}</span></td>
            <td>${formatDateTime(device.createdAt)}</td>
            <td>
                <button class="btn btn-sm btn-primary btn-action" onclick="editDevice(${device.id})">
                    <i class="bi bi-pencil"></i>
                </button>
                <button class="btn btn-sm btn-success btn-action" onclick="registerDevice(${device.id})">
                    <i class="bi bi-person-plus"></i>
                </button>
            </td>
        </tr>
    `).join('');
}

// 加载账号库数据
async function loadAccountLibrary(page = 1) {
    try {
        const country = document.getElementById('account-country-filter').value;
        const status = document.getElementById('account-status-filter').value;
        const nurtureStatus = document.getElementById('account-nurture-filter').value;
        
        const params = new URLSearchParams({
            pageNum: page,
            pageSize: 10
        });
        
        if (country) params.append('country', country);
        if (status) params.append('status', status);
        if (nurtureStatus) params.append('nurtureStatus', nurtureStatus);
        
        const response = await fetch(`/api/devices/accounts?${params}`);
        const data = await response.json();
        
        if (data.success) {
            renderAccountLibraryTable(data.data);
            renderPagination('account-pagination', data, 'account-library');
        } else {
            showAlert('加载账号库数据失败', 'danger');
        }
        
    } catch (error) {
        console.error('加载账号库数据失败:', error);
        showAlert('加载账号库数据失败', 'danger');
    }
}

// 渲染账号库表格
function renderAccountLibraryTable(accounts) {
    const tbody = document.getElementById('account-library-table');
    
    if (accounts.length === 0) {
        tbody.innerHTML = '<tr><td colspan="9" class="text-center">暂无数据</td></tr>';
        return;
    }
    
    tbody.innerHTML = accounts.map(account => `
        <tr>
            <td><input type="checkbox" value="${account.id}" class="account-checkbox"></td>
            <td>${account.ttUserName || '-'}</td>
            <td>${account.emailAccount || '-'}</td>
            <td>${account.country || '-'}</td>
            <td>${account.pkgName || '-'}</td>
            <td><span class="badge ${getStatusBadgeClass(account.status)}">${getStatusText(account.status)}</span></td>
            <td><span class="badge ${account.nurtureStatus === 1 ? 'bg-success' : 'bg-warning'}">${account.nurtureStatus === 1 ? '养号完成' : '养号中'}</span></td>
            <td>${account.videoDays || 0}天</td>
            <td>
                <button class="btn btn-sm btn-primary btn-action" onclick="editAccount(${account.id})">
                    <i class="bi bi-pencil"></i>
                </button>
                <button class="btn btn-sm btn-warning btn-action" onclick="nurtureAccount(${account.id})">
                    <i class="bi bi-heart"></i>
                </button>
            </td>
        </tr>
    `).join('');
}

// 渲染分页
function renderPagination(containerId, data, type) {
    const container = document.getElementById(containerId);
    const current = data.current;
    const pages = data.pages;
    
    if (pages <= 1) {
        container.innerHTML = '';
        return;
    }
    
    let pagination = '';
    
    // 上一页
    if (current > 1) {
        pagination += `<li class="page-item"><a class="page-link" href="#" onclick="load${type === 'device-pool' ? 'DevicePool' : 'AccountLibrary'}(${current - 1})">上一页</a></li>`;
    }
    
    // 页码
    for (let i = 1; i <= pages; i++) {
        if (i === current) {
            pagination += `<li class="page-item active"><span class="page-link">${i}</span></li>`;
        } else {
            pagination += `<li class="page-item"><a class="page-link" href="#" onclick="load${type === 'device-pool' ? 'DevicePool' : 'AccountLibrary'}(${i})">${i}</a></li>`;
        }
    }
    
    // 下一页
    if (current < pages) {
        pagination += `<li class="page-item"><a class="page-link" href="#" onclick="load${type === 'device-pool' ? 'DevicePool' : 'AccountLibrary'}(${current + 1})">下一页</a></li>`;
    }
    
    container.innerHTML = pagination;
}

// 全选/取消全选
function toggleSelectAll(type) {
    const selectAll = document.getElementById(`select-all-${type}`);
    const checkboxes = document.querySelectorAll(`.${type}-checkbox`);
    
    checkboxes.forEach(checkbox => {
        checkbox.checked = selectAll.checked;
    });
}

// 快速操作函数
async function createNewPhone() {
    const modal = new bootstrap.Modal(document.getElementById('createPhoneModal'));
    modal.show();
}

async function batchRegister() {
    try {
        const response = await fetch('/api/devices/need-register');
        const data = await response.json();
        
        if (data.success && data.data.length > 0) {
            const deviceIds = data.data.map(device => device.id);
            
            const response = await fetch('/api/scripts/register', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ deviceIds })
            });
            
            const result = await response.json();
            showAlert(result.message, result.success ? 'success' : 'danger');
            
            if (result.success) {
                loadDevicePool();
                loadDashboard();
            }
        } else {
            showAlert('没有需要注册的设备', 'warning');
        }
        
    } catch (error) {
        console.error('批量注册失败:', error);
        showAlert('批量注册失败', 'danger');
    }
}

async function batchNurture() {
    try {
        const response = await fetch('/api/devices/need-nurture');
        const data = await response.json();
        
        if (data.success && data.data.length > 0) {
            const accountIds = data.data.map(account => account.id);
            
            const response = await fetch('/api/scripts/watch-video', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ accountIds })
            });
            
            const result = await response.json();
            showAlert(result.message, result.success ? 'success' : 'danger');
            
            if (result.success) {
                loadAccountLibrary();
                loadDashboard();
            }
        } else {
            showAlert('没有需要养号的账号', 'warning');
        }
        
    } catch (error) {
        console.error('批量养号失败:', error);
        showAlert('批量养号失败', 'danger');
    }
}

async function triggerDailyTask() {
    try {
        const response = await fetch('/api/tasks/daily', {
            method: 'POST'
        });
        
        const result = await response.json();
        showAlert(result.message, result.success ? 'success' : 'danger');
        
    } catch (error) {
        console.error('触发每日任务失败:', error);
        showAlert('触发每日任务失败', 'danger');
    }
}

// 脚本执行相关函数
function showCreatePhoneModal() {
    createNewPhone();
}

function showRegisterModal() {
    // 这里可以显示注册参数配置模态框
    batchRegister();
}

function showFollowModal() {
    const modal = new bootstrap.Modal(document.getElementById('followModal'));
    modal.show();
}

function showWatchVideoModal() {
    // 这里可以显示刷视频参数配置模态框
    batchNurture();
}

function showEditBioModal() {
    const modal = new bootstrap.Modal(document.getElementById('editBioModal'));
    modal.show();
}

// 执行编辑Bio
async function executeEditBio() {
    try {
        const form = document.getElementById('editBioForm');
        const formData = new FormData(form);
        
        const params = {
            serverHost: formData.get('serverHost'),
            pkgName: formData.get('pkgName')
        };
        
        console.log('执行编辑Bio脚本，参数:', params);
        
        const response = await fetch('/api/scripts/edit-bio', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(params)
        });
        
        const result = await response.json();
        
        if (result.success) {
            // 关闭模态框
            const modal = bootstrap.Modal.getInstance(document.getElementById('editBioModal'));
            modal.hide();
            
            // 跳转到监控页面
            showSection('edit-bio-monitor');
            
            // 显示成功提示
            showEditBioAlert('success', '编辑Bio脚本已提交执行！进程ID: ' + result.pid);
            
            // 立即刷新一次状态
            setTimeout(refreshEditBioStatus, 2000);
        } else {
            showAlert('执行失败: ' + result.message, 'danger');
        }
    } catch (error) {
        console.error('执行编辑Bio脚本失败:', error);
        showAlert('执行失败: ' + error.message, 'danger');
    }
}

// 执行创建云手机
async function executeCreatePhone() {
    try {
        const form = document.getElementById('createPhoneForm');
        const formData = new FormData(form);
        
        const params = {
            phonePrefix: formData.get('phonePrefix'),
            serverHost: formData.get('serverHost'),
            country: formData.get('country'),
            pkgName: formData.get('pkgName'),
            count: parseInt(formData.get('count')),
            scriptPath: formData.get('scriptPath') || './batch_create_phone.sh'
        };
        
        const response = await fetch('/api/scripts/create-phone', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(params)
        });
        
        const result = await response.json();
        
        if (result.success) {
            const message = `${result.message}\n创建的云手机: ${result.count}个`;
            showAlert(message, 'success');
            bootstrap.Modal.getInstance(document.getElementById('createPhoneModal')).hide();
            loadDevicePool();
            loadDashboard();
        } else {
            showAlert(result.message, 'danger');
        }
        
    } catch (error) {
        console.error('执行创建云手机失败:', error);
        showAlert('执行创建云手机失败', 'danger');
    }
}

// 执行关注
async function executeFollow() {
    try {
        const form = document.getElementById('followForm');
        const formData = new FormData(form);
        
        const targetUsername = formData.get('targetUsername');
        const accountSelection = formData.get('accountSelection');
        
        let accountIds = [];
        
        if (accountSelection === 'selected') {
            const checkboxes = document.querySelectorAll('.account-checkbox:checked');
            accountIds = Array.from(checkboxes).map(cb => parseInt(cb.value));
        } else {
            // 获取所有养号完成的账号
            const response = await fetch('/api/devices/nurtured');
            const data = await response.json();
            if (data.success) {
                accountIds = data.data.map(account => account.id);
            }
        }
        
        if (accountIds.length === 0) {
            showAlert('没有可操作的账号', 'warning');
            return;
        }
        
        const response = await fetch('/api/scripts/follow', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                accountIds,
                targetUsername
            })
        });
        
        const result = await response.json();
        showAlert(result.message, result.success ? 'success' : 'danger');
        
        if (result.success) {
            bootstrap.Modal.getInstance(document.getElementById('followModal')).hide();
            loadAccountLibrary();
        }
        
    } catch (error) {
        console.error('执行关注失败:', error);
        showAlert('执行关注失败', 'danger');
    }
}

// 任务管理相关函数
async function loadTaskManagement() {
    await getTaskStatus();
}

async function getTaskStatus() {
    try {
        const response = await fetch('/api/tasks/schedule');
        const data = await response.json();
        
        const container = document.getElementById('task-status-content');
        
        if (data.success) {
            const status = data.data;
            container.innerHTML = `
                <div class="row">
                    <div class="col-md-4">
                        <div class="card">
                            <div class="card-body text-center">
                                <h5 class="card-title">待注册设备</h5>
                                <h2 class="text-primary">${status.devicesNeedRegister}</h2>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-4">
                        <div class="card">
                            <div class="card-body text-center">
                                <h5 class="card-title">养号中账号</h5>
                                <h2 class="text-warning">${status.accountsNeedNurture}</h2>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-4">
                        <div class="card">
                            <div class="card-body text-center">
                                <h5 class="card-title">养号完成账号</h5>
                                <h2 class="text-success">${status.nurturedAccounts}</h2>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="mt-3">
                    <small class="text-muted">最后更新: ${formatDateTime(status.lastUpdateTime)}</small>
                </div>
            `;
        } else {
            container.innerHTML = '<div class="alert alert-danger">获取任务状态失败</div>';
        }
        
    } catch (error) {
        console.error('获取任务状态失败:', error);
        document.getElementById('task-status-content').innerHTML = '<div class="alert alert-danger">获取任务状态失败</div>';
    }
}

async function getTaskConfig() {
    try {
        const response = await fetch('/api/tasks/config');
        const data = await response.json();
        
        if (data.success) {
            const config = data.data;
            showAlert(`定时任务配置: ${config.dailyTaskDescription}`, 'info');
        }
        
    } catch (error) {
        console.error('获取任务配置失败:', error);
        showAlert('获取任务配置失败', 'danger');
    }
}

// 统计相关函数
async function loadStatistics() {
    await loadDevicePoolStats();
    await loadAccountLibraryStats();
    await loadOverallStats();
}

async function loadDevicePoolStats() {
    try {
        const response = await fetch('/api/statistics/device-pool');
        const data = await response.json();
        
        const container = document.getElementById('device-pool-stats');
        
        if (data.success) {
            const stats = data.data;
            container.innerHTML = `
                <div class="row">
                    <div class="col-6">
                        <h6>总设备数</h6>
                        <h4 class="text-primary">${stats.totalDevices}</h4>
                    </div>
                    <div class="col-6">
                        <h6>待注册</h6>
                        <h4 class="text-warning">${stats.needRegisterCount}</h4>
                    </div>
                </div>
            `;
        } else {
            container.innerHTML = '<div class="alert alert-danger">加载失败</div>';
        }
        
    } catch (error) {
        console.error('加载设备池统计失败:', error);
        document.getElementById('device-pool-stats').innerHTML = '<div class="alert alert-danger">加载失败</div>';
    }
}

async function loadAccountLibraryStats() {
    try {
        const response = await fetch('/api/statistics/account-library');
        const data = await response.json();
        
        const container = document.getElementById('account-library-stats');
        
        if (data.success) {
            const stats = data.data;
            container.innerHTML = `
                <div class="row">
                    <div class="col-6">
                        <h6>总账号数</h6>
                        <h4 class="text-primary">${stats.totalAccounts}</h4>
                    </div>
                    <div class="col-6">
                        <h6>养号完成</h6>
                        <h4 class="text-success">${stats.nurturedStats ? stats.nurturedStats[1]?.count || 0 : 0}</h4>
                    </div>
                </div>
            `;
        } else {
            container.innerHTML = '<div class="alert alert-danger">加载失败</div>';
        }
        
    } catch (error) {
        console.error('加载账号库统计失败:', error);
        document.getElementById('account-library-stats').innerHTML = '<div class="alert alert-danger">加载失败</div>';
    }
}

async function loadOverallStats() {
    try {
        const response = await fetch('/api/statistics/overview');
        const data = await response.json();
        
        const container = document.getElementById('overall-stats');
        
        if (data.success) {
            const stats = data.data;
            container.innerHTML = `
                <div class="row">
                    <div class="col-md-3">
                        <div class="card">
                            <div class="card-body text-center">
                                <h5 class="card-title">注册转化率</h5>
                                <h3 class="text-primary">${stats.registerRate}%</h3>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card">
                            <div class="card-body text-center">
                                <h5 class="card-title">养号完成率</h5>
                                <h3 class="text-success">${stats.nurtureRate}%</h3>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card">
                            <div class="card-body text-center">
                                <h5 class="card-title">设备总数</h5>
                                <h3 class="text-info">${stats.totalDevices}</h3>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-3">
                        <div class="card">
                            <div class="card-body text-center">
                                <h5 class="card-title">账号总数</h5>
                                <h3 class="text-warning">${stats.totalAccounts}</h3>
                            </div>
                        </div>
                    </div>
                </div>
            `;
        } else {
            container.innerHTML = '<div class="alert alert-danger">加载失败</div>';
        }
        
    } catch (error) {
        console.error('加载整体统计失败:', error);
        document.getElementById('overall-stats').innerHTML = '<div class="alert alert-danger">加载失败</div>';
    }
}

// 工具函数
function getStatusBadgeClass(status) {
    switch(status) {
        case 0: return 'bg-success';
        case 1: return 'bg-danger';
        case 2: return 'bg-warning';
        default: return 'bg-secondary';
    }
}

function getStatusText(status) {
    switch(status) {
        case 0: return '正常';
        case 1: return '封号';
        case 2: return '冷却';
        default: return '未知';
    }
}

function formatDateTime(dateTimeStr) {
    if (!dateTimeStr) return '-';
    const date = new Date(dateTimeStr);
    return date.toLocaleString('zh-CN');
}

function showAlert(message, type) {
    const alertContainer = document.getElementById('alert-container');
    const alertId = 'alert-' + Date.now();
    
    const alertHtml = `
        <div id="${alertId}" class="alert alert-${type} alert-dismissible fade show alert-custom" role="alert">
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    `;
    
    alertContainer.insertAdjacentHTML('beforeend', alertHtml);
    
    // 5秒后自动移除
    setTimeout(() => {
        const alert = document.getElementById(alertId);
        if (alert) {
            alert.remove();
        }
    }, 5000);
}

// 批量操作函数
async function batchRegisterSelected() {
    const checkboxes = document.querySelectorAll('.pool-checkbox:checked');
    if (checkboxes.length === 0) {
        showAlert('请先选择要注册的设备', 'warning');
        return;
    }
    
    const deviceIds = Array.from(checkboxes).map(cb => parseInt(cb.value));
    
    try {
        const response = await fetch('/api/scripts/register', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ deviceIds })
        });
        
        const result = await response.json();
        showAlert(result.message, result.success ? 'success' : 'danger');
        
        if (result.success) {
            loadDevicePool();
            loadDashboard();
        }
        
    } catch (error) {
        console.error('批量注册失败:', error);
        showAlert('批量注册失败', 'danger');
    }
}

async function batchNurtureSelected() {
    const checkboxes = document.querySelectorAll('.account-checkbox:checked');
    if (checkboxes.length === 0) {
        showAlert('请先选择要养号的账号', 'warning');
        return;
    }
    
    const accountIds = Array.from(checkboxes).map(cb => parseInt(cb.value));
    
    try {
        const response = await fetch('/api/scripts/watch-video', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ accountIds })
        });
        
        const result = await response.json();
        showAlert(result.message, result.success ? 'success' : 'danger');
        
        if (result.success) {
            loadAccountLibrary();
            loadDashboard();
        }
        
    } catch (error) {
        console.error('批量养号失败:', error);
        showAlert('批量养号失败', 'danger');
    }
}

async function batchFollowSelected() {
    const checkboxes = document.querySelectorAll('.account-checkbox:checked');
    if (checkboxes.length === 0) {
        showAlert('请先选择要关注的账号', 'warning');
        return;
    }
    
    // 这里可以弹出输入框让用户输入要关注的用户名
    const targetUsername = prompt('请输入要关注的用户名:');
    if (!targetUsername) {
        return;
    }
    
    const accountIds = Array.from(checkboxes).map(cb => parseInt(cb.value));
    
    try {
        const response = await fetch('/api/scripts/follow', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ accountIds, targetUsername })
        });
        
        const result = await response.json();
        showAlert(result.message, result.success ? 'success' : 'danger');
        
        if (result.success) {
            loadAccountLibrary();
        }
        
    } catch (error) {
        console.error('批量关注失败:', error);
        showAlert('批量关注失败', 'danger');
    }
}

// ==================== 注册脚本监控相关函数 ====================

let registerAutoRefreshInterval = null;
let editBioAutoRefreshInterval = null;

// 执行注册脚本
async function executeRegisterScript() {
    try {
        const response = await fetch('/api/scripts/register', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });
        
        const data = await response.json();
        
        if (data.success) {
            showRegisterAlert('success', '注册脚本已成功提交执行！PID: ' + (data.pid || '获取中...'));
            setTimeout(refreshRegisterStatus, 2000);
        } else {
            showRegisterAlert('danger', data.message || '执行失败');
        }
    } catch (error) {
        showRegisterAlert('danger', '请求失败: ' + error.message);
    }
}

// 刷新注册脚本状态
async function refreshRegisterStatus() {
    try {
        const response = await fetch('/api/scripts/register/status');
        const data = await response.json();
        
        if (data.success) {
            updateRegisterUI(data);
        } else {
            showRegisterAlert('danger', '获取状态失败: ' + data.message);
        }
    } catch (error) {
        showRegisterAlert('danger', '请求失败: ' + error.message);
    }
}

// 更新注册监控UI
function updateRegisterUI(data) {
    // 更新状态
    const statusText = document.getElementById('register-status-text');
    const statusBadge = document.getElementById('register-status-badge');
    
    const statusMap = {
        'running': { text: '运行中', class: 'bg-primary', badge: 'RUNNING' },
        'completed': { text: '已完成', class: 'bg-success', badge: 'COMPLETED' },
        'error': { text: '错误', class: 'bg-danger', badge: 'ERROR' },
        'timeout': { text: '超时', class: 'bg-warning', badge: 'TIMEOUT' },
        'not_running': { text: '未运行', class: 'bg-secondary', badge: 'NOT_RUNNING' }
    };
    
    const status = statusMap[data.status] || statusMap['not_running'];
    statusText.textContent = status.text;
    statusBadge.textContent = status.badge;
    statusBadge.className = 'badge ' + status.class;
    
    // 更新其他信息
    document.getElementById('register-pid-text').textContent = data.pid || '-';
    document.getElementById('register-start-time').textContent = data.startTime ? 
        new Date(data.startTime).toLocaleString('zh-CN') : '-';
    document.getElementById('register-running-time').textContent = data.runningTime ? 
        data.runningTime + ' 分钟' : '-';
    document.getElementById('register-log-file').textContent = data.logFile || '-';
    
    // 更新日志
    const logContent = document.getElementById('register-log-content');
    if (data.logContent) {
        logContent.textContent = data.logContent;
        // 自动滚动到底部
        logContent.scrollTop = logContent.scrollHeight;
    } else {
        logContent.textContent = '暂无日志内容';
    }
}

// 显示注册监控提示
function showRegisterAlert(type, message) {
    const container = document.getElementById('register-alert-container');
    const alertClass = type === 'success' ? 'alert-success' : 'alert-danger';
    container.innerHTML = `<div class="alert ${alertClass} alert-dismissible fade show" role="alert">
        ${message}
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    </div>`;
    
    setTimeout(() => {
        container.innerHTML = '';
    }, 5000);
}

// 切换自动刷新
function toggleRegisterAutoRefresh() {
    const checkbox = document.getElementById('register-auto-refresh');
    
    if (checkbox.checked) {
        startRegisterAutoRefresh();
    } else {
        stopRegisterAutoRefresh();
    }
}

// 开始自动刷新
function startRegisterAutoRefresh() {
    refreshRegisterStatus(); // 立即刷新一次
    registerAutoRefreshInterval = setInterval(refreshRegisterStatus, 5000); // 每5秒刷新
}

// 停止自动刷新
function stopRegisterAutoRefresh() {
    if (registerAutoRefreshInterval) {
        clearInterval(registerAutoRefreshInterval);
        registerAutoRefreshInterval = null;
    }
}

// ==================== 编辑Bio脚本监控相关函数 ====================

// 刷新编辑Bio脚本状态
async function refreshEditBioStatus() {
    try {
        const response = await fetch('/api/scripts/edit-bio/status');
        const data = await response.json();
        
        if (data.success) {
            updateEditBioUI(data);
        } else {
            showEditBioAlert('danger', '获取状态失败: ' + data.message);
        }
    } catch (error) {
        showEditBioAlert('danger', '请求失败: ' + error.message);
    }
}

// 更新编辑Bio监控UI
function updateEditBioUI(data) {
    // 更新状态
    const statusText = document.getElementById('edit-bio-status-text');
    const statusBadge = document.getElementById('edit-bio-status-badge');
    
    const statusMap = {
        'running': { text: '运行中', class: 'bg-primary', badge: 'RUNNING' },
        'completed': { text: '已完成', class: 'bg-success', badge: 'COMPLETED' },
        'error': { text: '错误', class: 'bg-danger', badge: 'ERROR' },
        'timeout': { text: '超时', class: 'bg-warning', badge: 'TIMEOUT' },
        'not_running': { text: '未运行', class: 'bg-secondary', badge: 'NOT_RUNNING' }
    };
    
    const status = statusMap[data.status] || statusMap['not_running'];
    statusText.textContent = status.text;
    statusBadge.textContent = status.badge;
    statusBadge.className = 'badge ' + status.class;
    
    // 更新进程ID
    document.getElementById('edit-bio-pid-text').textContent = data.pid || '-';
    
    // 更新开始时间
    if (data.startTime) {
        const startTime = new Date(data.startTime);
        document.getElementById('edit-bio-start-time').textContent = 
            startTime.toLocaleString('zh-CN', { hour12: false });
    } else {
        document.getElementById('edit-bio-start-time').textContent = '-';
    }
    
    // 更新运行时长
    if (data.runningTime) {
        document.getElementById('edit-bio-running-time').textContent = data.runningTime + ' 分钟';
    } else {
        document.getElementById('edit-bio-running-time').textContent = '-';
    }
    
    // 更新日志文件路径
    document.getElementById('edit-bio-log-file').textContent = data.logFile || '-';
    
    // 更新日志内容
    const logContent = document.getElementById('edit-bio-log-content');
    if (data.logContent) {
        logContent.textContent = data.logContent;
        // 自动滚动到底部
        logContent.scrollTop = logContent.scrollHeight;
    } else {
        logContent.textContent = data.status === 'not_running' ? 
            '脚本未运行，请点击"执行编辑Bio脚本"按钮开始' : '等待获取日志...';
    }
}

// 显示编辑Bio提示
function showEditBioAlert(type, message) {
    const container = document.getElementById('edit-bio-alert-container');
    const alertClass = type === 'success' ? 'alert-success' : 'alert-danger';
    container.innerHTML = `<div class="alert ${alertClass} alert-dismissible fade show" role="alert">
        ${message}
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    </div>`;
}

// 切换自动刷新
function toggleEditBioAutoRefresh() {
    const checkbox = document.getElementById('edit-bio-auto-refresh');
    
    if (checkbox.checked) {
        startEditBioAutoRefresh();
    } else {
        stopEditBioAutoRefresh();
    }
}

// 开始自动刷新
function startEditBioAutoRefresh() {
    refreshEditBioStatus(); // 立即刷新一次
    editBioAutoRefreshInterval = setInterval(refreshEditBioStatus, 5000); // 每5秒刷新
}

// 停止自动刷新
function stopEditBioAutoRefresh() {
    if (editBioAutoRefreshInterval) {
        clearInterval(editBioAutoRefreshInterval);
        editBioAutoRefreshInterval = null;
    }
}

// ==================== 自动养号相关函数 ====================

let autoNurtureRefreshInterval = null;
let currentMonitoringTaskId = null;

/**
 * 启动自动养号任务
 */
function startAutoNurture() {
    const form = document.getElementById('autoNurtureForm');
    const formData = new FormData(form);
    
    // 构建请求数据
    const config = {
        rounds: parseInt(formData.get('rounds')),
        groupSize: parseInt(formData.get('groupSize')),
        phoneServerIp: formData.get('phoneServerIp'),
        groupTimeout: parseInt(formData.get('groupTimeout')),
        allowedCountries: formData.get('allowedCountries').split(',').map(c => c.trim())
    };
    
    console.log('启动自动养号任务，配置:', config);
    
    fetch('/api/auto-nurture/start', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(config)
    })
    .then(response => response.json())
    .then(data => {
        if (data.success) {
            alert('自动养号任务已启动！\n任务ID: ' + data.taskId);
            // 刷新任务列表
            loadAutoNurtureTasks();
            // 开始监控任务
            monitorTask(data.taskId);
        } else {
            alert('启动失败: ' + data.message);
        }
    })
    .catch(error => {
        console.error('启动任务失败:', error);
        alert('启动失败: ' + error.message);
    });
}

/**
 * 加载自动养号任务列表
 */
function loadAutoNurtureTasks() {
    fetch('/api/auto-nurture/tasks')
        .then(response => response.json())
        .then(result => {
            if (result.success) {
                updateTaskList(result.data);
            } else {
                console.error('加载任务列表失败:', result.message);
            }
        })
        .catch(error => {
            console.error('加载任务列表失败:', error);
        });
}

/**
 * 更新任务列表
 */
function updateTaskList(tasks) {
    const tbody = document.getElementById('taskListBody');
    
    if (!tasks || tasks.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="text-center text-muted">暂无任务</td></tr>';
        return;
    }
    
    let html = '';
    tasks.forEach(task => {
        const statusBadge = getStatusBadge(task.status);
        const progress = task.progress || 0;
        const currentRound = task.currentRound || 0;
        const totalRounds = task.totalRounds || 3;
        
        let summaryText = '-';
        if (task.summary) {
            const s = task.summary;
            summaryText = `成功:${s.success || 0} 失败:${s.failed || 0} IP失败:${s.ip_failed || 0}`;
        }
        
        html += `
            <tr>
                <td><small>${task.taskId}</small></td>
                <td>${statusBadge}</td>
                <td>
                    <div class="progress" style="min-width: 100px;">
                        <div class="progress-bar" role="progressbar" style="width: ${progress}%">${progress}%</div>
                    </div>
                </td>
                <td>${currentRound}/${totalRounds}</td>
                <td><small>${formatDateTime(task.startTime)}</small></td>
                <td><small>${task.endTime ? formatDateTime(task.endTime) : '-'}</small></td>
                <td><small>${summaryText}</small></td>
                <td>
                    <button class="btn btn-sm btn-primary" onclick="viewTaskDetail('${task.taskId}')">
                        <i class="bi bi-eye"></i> 查看
                    </button>
                </td>
            </tr>
        `;
    });
    
    tbody.innerHTML = html;
}

/**
 * 查看任务详情
 */
function viewTaskDetail(taskId) {
    console.log('查看任务详情:', taskId);
    currentMonitoringTaskId = taskId;
    
    // 显示详情容器
    document.getElementById('taskDetailContainer').style.display = 'block';
    document.getElementById('currentTaskId').textContent = taskId;
    
    // 立即刷新一次
    refreshTaskDetail(taskId);
    
    // 如果任务正在运行，启动自动刷新
    startAutoRefreshTaskDetail(taskId);
    
    // 滚动到详情区域
    document.getElementById('taskDetailContainer').scrollIntoView({ behavior: 'smooth' });
}

/**
 * 刷新任务详情
 */
function refreshTaskDetail(taskId) {
    fetch(`/api/auto-nurture/status/${taskId}`)
        .then(response => response.json())
        .then(result => {
            if (result.success) {
                updateTaskDetailUI(result.data);
                
                // 如果任务已完成，停止自动刷新
                if (result.data.status === 'COMPLETED' || result.data.status === 'FAILED') {
                    stopAutoRefreshTaskDetail();
                }
            } else {
                console.error('获取任务详情失败:', result.message);
            }
        })
        .catch(error => {
            console.error('获取任务详情失败:', error);
        });
}

/**
 * 更新任务详情UI
 */
function updateTaskDetailUI(task) {
    // 状态
    const statusBadge = getStatusBadge(task.status);
    document.getElementById('task-detail-status').innerHTML = statusBadge;
    
    // 进度
    const progress = task.progress || 0;
    document.getElementById('task-detail-progress').textContent = progress + '%';
    document.getElementById('task-detail-progress-bar').style.width = progress + '%';
    document.getElementById('task-detail-progress-bar').textContent = progress + '%';
    
    // 统计
    const summary = task.summary || {};
    document.getElementById('task-detail-total').textContent = summary.total || 0;
    document.getElementById('task-detail-success').textContent = summary.success || 0;
    document.getElementById('task-detail-failed').textContent = summary.failed || 0;
    
    const other = (summary.ip_failed || 0) + (summary.log_out || 0) + 
                  (summary.black_list || 0) + (summary.follow_all || 0);
    document.getElementById('task-detail-other').textContent = other;
    
    // 配置信息
    const config = task.config || {};
    let configHtml = `
        <div>执行轮次: ${config.rounds || 3}轮</div>
        <div>每组设备数: ${config.groupSize || 10}个</div>
        <div>云手机服务器: ${config.phoneServerIp || '10.7.107.224'}</div>
        <div>允许IP地区: ${(config.allowedCountries || ['US', 'CA']).join(', ')}</div>
        <div>组超时: ${config.groupTimeout || 40}分钟</div>
    `;
    document.getElementById('task-detail-config').innerHTML = configHtml;
    
    // 统计摘要
    let summaryHtml = `
        <div class="row">
            <div class="col-md-4">总设备数: <strong>${summary.total || 0}</strong></div>
            <div class="col-md-4">关注成功: <strong class="text-success">${summary.success || 0}</strong></div>
            <div class="col-md-4">关注全部: <strong class="text-info">${summary.follow_all || 0}</strong></div>
        </div>
        <div class="row mt-2">
            <div class="col-md-4">IP检查失败: <strong class="text-warning">${summary.ip_failed || 0}</strong></div>
            <div class="col-md-4">账号被封: <strong class="text-danger">${summary.log_out || 0}</strong></div>
            <div class="col-md-4">黑名单: <strong class="text-muted">${summary.black_list || 0}</strong></div>
        </div>
        <div class="row mt-2">
            <div class="col-md-4">执行失败: <strong class="text-danger">${summary.failed || 0}</strong></div>
        </div>
    `;
    document.getElementById('task-detail-summary').innerHTML = summaryHtml;
}

/**
 * 开始监控任务（用于新创建的任务）
 */
function monitorTask(taskId) {
    viewTaskDetail(taskId);
}

/**
 * 开始自动刷新任务详情
 */
function startAutoRefreshTaskDetail(taskId) {
    stopAutoRefreshTaskDetail(); // 先停止之前的
    autoNurtureRefreshInterval = setInterval(() => {
        refreshTaskDetail(taskId);
    }, 5000); // 每5秒刷新
}

/**
 * 停止自动刷新任务详情
 */
function stopAutoRefreshTaskDetail() {
    if (autoNurtureRefreshInterval) {
        clearInterval(autoNurtureRefreshInterval);
        autoNurtureRefreshInterval = null;
    }
}

/**
 * 获取状态徽章
 */
function getStatusBadge(status) {
    const badges = {
        'PENDING': '<span class="badge bg-secondary">等待中</span>',
        'RUNNING': '<span class="badge bg-primary">运行中</span>',
        'COMPLETED': '<span class="badge bg-success">已完成</span>',
        'FAILED': '<span class="badge bg-danger">失败</span>',
        'TIMEOUT': '<span class="badge bg-warning">超时</span>'
    };
    return badges[status] || '<span class="badge bg-secondary">' + status + '</span>';
}

/**
 * 格式化日期时间
 */
function formatDateTime(dateTimeStr) {
    if (!dateTimeStr) return '-';
    try {
        const date = new Date(dateTimeStr);
        return date.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
    } catch (e) {
        return dateTimeStr;
    }
}

// 全局变量：TT注册任务监控
let currentTtRegisterTaskId = null; // 当前正在运行的任务ID
/**
 * 开始批量注册TT账号
 */
async function startTtRegister(event) {
    const phoneIdInput = document.getElementById('phoneIdInput');
    const serverIpInput = document.getElementById('serverIpInput');
    const targetCountInput = document.getElementById('targetCountInput');
    
    // 获取输入值
    const phoneId = phoneIdInput.value.trim();
    const serverIp = serverIpInput.value.trim();
    const targetCount = parseInt(targetCountInput.value.trim());
    
    // 验证输入
    if (!phoneId) {
        showAlert('请输入云手机ID', 'warning');
        return;
    }
    
    if (!serverIp) {
        showAlert('请输入服务器IP', 'warning');
        return;
    }
    
    if (!targetCount || targetCount < 1 || targetCount > 1000) {
        showAlert('请输入有效的目标账号数量（1-1000）', 'warning');
        return;
    }
    
    // 显示确认对话框
    const confirmMsg = `确认使用设备 ${phoneId} 注册 ${targetCount} 个TT账号？\n\n服务器: ${serverIp}\n目标数量: ${targetCount}`;
    if (!confirm(confirmMsg)) {
        return;
    }
    
    // 获取TikTok版本目录（必填）
    const tiktokVersionDir = document.getElementById('tiktokVersionDirInput').value.trim();
    if (!tiktokVersionDir) {
        showAlert('请输入TikTok版本目录', 'warning');
        return;
    }
    
    // 收集ResetPhoneEnv参数
    const resetParams = {};
    const country = document.getElementById('countryInput').value.trim();
    const sdk = document.getElementById('sdkInput').value.trim();
    const imagePath = document.getElementById('imagePathInput').value.trim();
    const gaidTag = document.getElementById('gaidTagInput').value.trim();
    let dynamicIpChannel = document.getElementById('dynamicIpChannelInput').value.trim();
    const staticIpChannel = document.getElementById('staticIpChannelInput').value.trim();
    const biz = document.getElementById('bizInput').value.trim();
    
    // 如果动态IP渠道为空，从三个选项中随机选择一个
    if (!dynamicIpChannel) {
        const channels = ['ipidea', 'closeli', 'ipbiubiu'];
        dynamicIpChannel = channels[Math.floor(Math.random() * channels.length)];
        console.log('动态IP渠道为空，随机选择: ' + dynamicIpChannel);
    }
    
    // 只有当参数不为空时才添加到resetParams中
    if (country) resetParams.country = country;
    if (sdk) resetParams.sdk = sdk;
    if (imagePath) resetParams.imagePath = imagePath;
    if (gaidTag) resetParams.gaidTag = gaidTag;
    resetParams.dynamicIpChannel = dynamicIpChannel; // 总是设置动态IP渠道（已确保不为空）
    if (staticIpChannel) resetParams.staticIpChannel = staticIpChannel;
    if (biz) resetParams.biz = biz;
    
    // 禁用按钮，显示加载状态
    const submitBtn = event ? event.target : document.getElementById('startRegisterBtn');
    if (!submitBtn) {
        console.error('找不到提交按钮');
        return;
    }
    const originalText = submitBtn.innerHTML;
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> 启动中...';
    
    try {
        
        // 调用API
        const requestBody = {
            phoneId: phoneId,
            serverIp: serverIp,
            targetCount: targetCount,
            tiktokVersionDir: tiktokVersionDir
        };
        
        // 如果有resetParams参数，添加到请求体中
        if (Object.keys(resetParams).length > 0) {
            requestBody.resetParams = resetParams;
        }
        
        const response = await fetch('/api/tt-register/repeat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(requestBody)
        });
        
        const result = await response.json();
        
        if (result.success && result.taskId) {
            showAlert('单设备重复注册任务已启动！任务ID: ' + result.taskId, 'success');
            // 保存当前任务ID
            currentTtRegisterTaskId = result.taskId;
            // 显示停止按钮
            const stopBtn = document.getElementById('stopRegisterBtn');
            if (stopBtn) {
                stopBtn.style.display = 'inline-block';
                stopBtn.style.visibility = 'visible';
                stopBtn.disabled = false;
                stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止任务';
            }
            // 开始监控任务
            monitorTtRegisterTask(result.taskId);
        } else {
            showAlert('注册任务启动失败: ' + (result.message || '未知错误'), 'danger');
        }
        
    } catch (error) {
        console.error('批量注册失败:', error);
        showAlert('批量注册失败: ' + error.message, 'danger');
    } finally {
        // 恢复按钮
        submitBtn.disabled = false;
        submitBtn.innerHTML = originalText;
    }
}

/**
 * 停止TT注册任务
 */
async function stopTtRegisterTask() {
    if (!currentTtRegisterTaskId) {
        showAlert('没有正在运行的任务', 'warning');
        return;
    }
    
    const confirmMsg = `确认停止任务 ${currentTtRegisterTaskId}？\n\n停止后任务将不再继续执行，已完成的注册将保留。`;
    if (!confirm(confirmMsg)) {
        return;
    }
    
    const stopBtn = document.getElementById('stopRegisterBtn');
    const originalText = stopBtn ? stopBtn.innerHTML : '';
    if (stopBtn) {
        stopBtn.disabled = true;
        stopBtn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> 停止中...';
    }
    
    try {
        const response = await fetch(`/api/tt-register/stop/${currentTtRegisterTaskId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });
        
        if (!response.ok) {
            throw new Error(`HTTP错误: ${response.status}`);
        }
        
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务已成功停止', 'success');
            // 停止自动刷新
            stopTtRegisterRefresh();
            // 刷新一次状态以显示最新状态
            refreshTtRegisterTaskStatus(currentTtRegisterTaskId);
            // 隐藏停止按钮
            if (stopBtn) {
                stopBtn.style.display = 'none';
                stopBtn.style.visibility = 'hidden';
                stopBtn.disabled = false;
                stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止任务';
            }
            currentTtRegisterTaskId = null;
        } else {
            showAlert('停止任务失败: ' + (result.message || '未知错误'), 'danger');
            if (stopBtn) {
                stopBtn.disabled = false;
                stopBtn.innerHTML = originalText;
            }
        }
    } catch (error) {
        console.error('停止任务失败:', error);
        showAlert('停止任务失败: ' + error.message, 'danger');
        if (stopBtn) {
            stopBtn.disabled = false;
            stopBtn.innerHTML = originalText;
        }
    }
}

/**
 * 监控TT注册任务
 */
function monitorTtRegisterTask(taskId) {
    // 保存当前任务ID
    currentTtRegisterTaskId = taskId;
    
    // 显示结果容器
    const container = document.getElementById('ttRegisterResultContainer');
    if (container) {
        container.style.display = 'block';
    }
    
    // 显示停止按钮
    const stopBtn = document.getElementById('stopRegisterBtn');
    if (stopBtn) {
        stopBtn.style.display = 'inline-block';
        stopBtn.style.visibility = 'visible';
        stopBtn.disabled = false;
        stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止任务';
    }
    
    // 立即刷新一次
    refreshTtRegisterTaskStatus(taskId);
    
    // 启动自动刷新（每5秒刷新一次）
    stopTtRegisterRefresh(); // 先停止之前的
    ttRegisterRefreshInterval = setInterval(() => {
        refreshTtRegisterTaskStatus(taskId);
    }, 5000); // 每5秒刷新
}

/**
 * 刷新TT注册任务状态
 */
function refreshTtRegisterTaskStatus(taskId) {
    fetch(`/api/tt-register/status/${taskId}`)
        .then(response => response.json())
        .then(result => {
            if (result.success) {
                displayTtRegisterTaskStatus(result);
                
                // 如果任务已完成、失败或已停止，停止自动刷新并隐藏停止按钮
                const status = result.status;
                if (status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED') {
                    stopTtRegisterRefresh();
                    const stopBtn = document.getElementById('stopRegisterBtn');
                    if (stopBtn) {
                        stopBtn.style.display = 'none';
                        stopBtn.style.visibility = 'hidden';
                    }
                    currentTtRegisterTaskId = null;
                }
            } else {
                console.error('获取任务状态失败:', result.message);
            }
        })
        .catch(error => {
            console.error('获取任务状态失败:', error);
        });
}

/**
 * 停止TT注册任务刷新
 */
function stopTtRegisterRefresh() {
    if (ttRegisterRefreshInterval) {
        clearInterval(ttRegisterRefreshInterval);
        ttRegisterRefreshInterval = null;
    }
}

/**
 * 显示TT注册任务状态
 */
function displayTtRegisterTaskStatus(taskStatus) {
    const content = document.getElementById('ttRegisterResultContent');
    // 构建状态HTML
    const statusBadge = getTtRegisterStatusBadge(taskStatus.status);
    const progress = taskStatus.progress || 0;
    const totalCount = taskStatus.totalCount || 0;
    const successCount = taskStatus.successCount || 0;
    const failCount = taskStatus.failCount || 0;
    
    let html = `
        <div class="alert alert-info">
            <h5><i class="bi bi-info-circle"></i> 任务状态</h5>
            <div class="row">
                <div class="col-md-3">
                    <strong>任务ID:</strong> <code>${taskStatus.taskId}</code>
                </div>
                <div class="col-md-2">
                    <strong>状态:</strong> ${statusBadge}
                </div>
                <div class="col-md-2">
                    <strong>进度:</strong> ${progress}%
                </div>
                <div class="col-md-2">
                    <strong>成功:</strong> <span class="text-success">${successCount}</span>
                </div>
                <div class="col-md-3">
                    <strong>失败:</strong> <span class="text-danger">${failCount}</span>
                </div>
            </div>
            <div class="progress mt-2" style="height: 25px;">
                <div class="progress-bar" role="progressbar" style="width: ${progress}%">${progress}%</div>
            </div>
            ${taskStatus.startTime ? `<div class="mt-2"><small class="text-muted">开始时间: ${formatDateTime(taskStatus.startTime)}</small></div>` : ''}
            ${taskStatus.endTime ? `<div><small class="text-muted">结束时间: ${formatDateTime(taskStatus.endTime)}</small></div>` : ''}
        </div>
    `;
    
    // 显示提示信息（日志在应用日志中）
    html += `
        <div class="alert alert-warning">
            <i class="bi bi-info-circle"></i> 任务日志在应用日志中，请通过SSH查看应用日志文件，搜索任务ID: <code>${taskStatus.taskId}</code>
        </div>
    `;
    
    content.innerHTML = html;
}

/**
 * 获取TT注册状态徽章
 */
function getTtRegisterStatusBadge(status) {
    const badges = {
        'RUNNING': '<span class="badge bg-primary">运行中</span>',
        'COMPLETED': '<span class="badge bg-success">已完成</span>',
        'FAILED': '<span class="badge bg-danger">失败</span>'
    };
    return badges[status] || '<span class="badge bg-secondary">' + status + '</span>';
}

/**
 * HTML转义函数
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * 清空批量注册结果
 */
function clearTtRegisterResult() {
    // 停止自动刷新
    stopTtRegisterRefresh();
    
    // 清空结果容器
    const container = document.getElementById('ttRegisterResultContainer');
    const content = document.getElementById('ttRegisterResultContent');
    if (container) {
        container.style.display = 'none';
    }
    if (content) {
        content.innerHTML = '';
    }
    
    // 隐藏停止按钮
    const stopBtn = document.getElementById('stopRegisterBtn');
    if (stopBtn) {
        stopBtn.style.display = 'none';
        stopBtn.style.visibility = 'hidden';
        stopBtn.disabled = false;
    }
    
    // 清除当前任务ID
    currentTtRegisterTaskId = null;
    
    // 显示提示
    showAlert('结果已清空', 'info');
}

// ========== 多设备并行注册相关函数 ==========
let currentParallelTtRegisterTaskId = null; // 当前正在运行的并行注册任务ID（用于显示详细结果）
let parallelTasksRefreshInterval = null; // 任务列表刷新定时器
let parallelTtRegisterRefreshInterval = null; // 并行注册任务刷新定时器

/**
 * 开始多设备并行注册TT账号
 */
async function startParallelTtRegister(event) {
    const phoneIdsInput = document.getElementById('parallelPhoneIdsInput');
    const serverIpInput = document.getElementById('parallelServerIpInput');
    const maxConcurrencyInput = document.getElementById('maxConcurrencyInput');
    const targetCountPerDeviceInput = document.getElementById('targetCountPerDeviceInput');
    
    // 获取输入值
    const phoneIdsText = phoneIdsInput.value.trim();
    const serverIp = serverIpInput.value.trim();
    const maxConcurrency = parseInt(maxConcurrencyInput.value.trim());
    const targetCountPerDevice = parseInt(targetCountPerDeviceInput.value.trim());
    
    // 验证输入
    if (!phoneIdsText) {
        showAlert('请输入云手机ID列表', 'warning');
        return;
    }
    
    // 解析云手机ID列表（每行一个）
    const phoneIds = phoneIdsText.split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0);
    
    if (phoneIds.length === 0) {
        showAlert('请输入至少一个云手机ID', 'warning');
        return;
    }
    
    if (!serverIp) {
        showAlert('请输入服务器IP', 'warning');
        return;
    }
    
    if (!maxConcurrency || maxConcurrency < 1 || maxConcurrency > 50) {
        showAlert('请输入有效的最大并发数（1-50）', 'warning');
        return;
    }
    
    if (targetCountPerDevice < 0 || targetCountPerDevice > 1000) {
        showAlert('请输入有效的每个设备目标账号数（0表示无限循环，1-1000表示固定次数）', 'warning');
        return;
    }
    
    // 计算总目标账号数（0表示无限循环）
    const totalTargetCount = targetCountPerDevice === 0 ? -1 : phoneIds.length * targetCountPerDevice;
    const targetCountDisplay = targetCountPerDevice === 0 ? '无限循环' : targetCountPerDevice;
    const totalCountDisplay = totalTargetCount === -1 ? '无限' : totalTargetCount;
    
    // 显示确认对话框
    const confirmMsg = `确认使用 ${phoneIds.length} 个设备并行注册TT账号？\n\n服务器: ${serverIp}\n设备数量: ${phoneIds.length}\n每个设备目标账号数: ${targetCountDisplay}\n总目标账号数: ${totalCountDisplay}\n最大并发数: ${maxConcurrency}`;
    if (!confirm(confirmMsg)) {
        return;
    }
    
    // 获取TikTok版本目录（必填）
    const tiktokVersionDir = document.getElementById('parallelTiktokVersionDirInput').value.trim();
    if (!tiktokVersionDir) {
        showAlert('请输入TikTok版本目录', 'warning');
        return;
    }
    
    // 收集ResetPhoneEnv参数
    const resetParams = {};
    const country = document.getElementById('parallelCountryInput').value.trim();
    const sdk = document.getElementById('parallelSdkInput').value.trim();
    const imagePath = document.getElementById('parallelImagePathInput').value.trim();
    const gaidTag = document.getElementById('parallelGaidTagInput').value.trim();
    let dynamicIpChannel = document.getElementById('parallelDynamicIpChannelInput').value.trim();
    const staticIpChannel = document.getElementById('parallelStaticIpChannelInput').value.trim();
    const biz = document.getElementById('parallelBizInput').value.trim();
    
    // 如果动态IP渠道为空，从三个选项中随机选择一个
    if (!dynamicIpChannel) {
        const channels = ['ipidea', 'closeli', 'ipbiubiu'];
        dynamicIpChannel = channels[Math.floor(Math.random() * channels.length)];
        console.log('动态IP渠道为空，随机选择: ' + dynamicIpChannel);
    }
    
    // 只有当参数不为空时才添加到resetParams中
    if (country) resetParams.country = country;
    if (sdk) resetParams.sdk = sdk;
    if (imagePath) resetParams.imagePath = imagePath;
    if (gaidTag) resetParams.gaidTag = gaidTag;
    resetParams.dynamicIpChannel = dynamicIpChannel; // 总是设置动态IP渠道（已确保不为空）
    if (staticIpChannel) resetParams.staticIpChannel = staticIpChannel;
    if (biz) resetParams.biz = biz;
    
    // 禁用按钮，显示加载状态
    const submitBtn = event ? event.target : document.getElementById('startParallelRegisterBtn');
    if (!submitBtn) {
        console.error('找不到提交按钮');
        return;
    }
    const originalText = submitBtn.innerHTML;
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> 启动中...';
    
    try {
        // 调用API
        const requestBody = {
            phoneIds: phoneIds,
            serverIp: serverIp,
            maxConcurrency: maxConcurrency,
            targetCountPerDevice: targetCountPerDevice,
            tiktokVersionDir: tiktokVersionDir
        };
        
        // 如果有resetParams参数，添加到请求体中
        if (Object.keys(resetParams).length > 0) {
            requestBody.resetParams = resetParams;
        }
        
        const response = await fetch('/api/tt-register/parallel', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(requestBody)
        });
        
        const result = await response.json();
        
        if (result.success && result.taskId) {
            showAlert('多设备并行注册任务已启动！任务ID: ' + result.taskId, 'success');
            // 保存当前任务ID（用于显示详细结果）
            currentParallelTtRegisterTaskId = result.taskId;
            
            // 恢复启动按钮（但保持可用状态）
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<i class="bi bi-play-fill"></i> 开始并行注册';
            
            // 显示停止按钮（用于停止当前显示的任务）
            const stopBtn = document.getElementById('stopParallelRegisterBtn');
            if (stopBtn) {
                stopBtn.style.display = 'inline-block';
                stopBtn.style.visibility = 'visible';
                stopBtn.disabled = false;
                stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止当前任务';
            }
            
            // 开始监控任务（显示详细结果）
            monitorParallelTtRegisterTask(result.taskId);
            // 开始刷新任务列表（显示所有运行中的任务）
            startParallelTasksListRefresh();
        } else {
            showAlert('并行注册任务启动失败: ' + (result.message || '未知错误'), 'danger');
            // 恢复按钮
            submitBtn.disabled = false;
            submitBtn.innerHTML = originalText;
        }
        
    } catch (error) {
        console.error('并行注册失败:', error);
        showAlert('并行注册失败: ' + error.message, 'danger');
        // 恢复按钮
        submitBtn.disabled = false;
        submitBtn.innerHTML = originalText;
    }
}

/**
 * 停止多设备并行注册任务
 */
async function stopParallelTtRegisterTask() {
    if (!currentParallelTtRegisterTaskId) {
        showAlert('没有正在运行的任务', 'warning');
        return;
    }
    
    const confirmMsg = `确认停止任务 ${currentParallelTtRegisterTaskId}？\n\n停止后任务将不再继续执行，已完成的注册将保留。`;
    if (!confirm(confirmMsg)) {
        return;
    }
    
    const stopBtn = document.getElementById('stopParallelRegisterBtn');
    const originalText = stopBtn ? stopBtn.innerHTML : '';
    if (stopBtn) {
        stopBtn.disabled = true;
        stopBtn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> 停止中...';
    }
    
    try {
        const response = await fetch(`/api/tt-register/stop/${currentParallelTtRegisterTaskId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });
        
        if (!response.ok) {
            throw new Error(`HTTP错误: ${response.status}`);
        }
        
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务已成功停止', 'success');
            // 停止自动刷新
            stopParallelTtRegisterRefresh();
            // 刷新一次状态以显示最新状态
            refreshParallelTtRegisterTaskStatus(currentParallelTtRegisterTaskId);
            // 隐藏停止按钮
            if (stopBtn) {
                stopBtn.style.display = 'none';
                stopBtn.style.visibility = 'hidden';
                stopBtn.disabled = false;
                stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止任务';
            }
            currentParallelTtRegisterTaskId = null;
        } else {
            showAlert('停止任务失败: ' + (result.message || '未知错误'), 'danger');
            if (stopBtn) {
                stopBtn.disabled = false;
                stopBtn.innerHTML = originalText;
            }
        }
    } catch (error) {
        console.error('停止任务失败:', error);
        showAlert('停止任务失败: ' + error.message, 'danger');
        if (stopBtn) {
            stopBtn.disabled = false;
            stopBtn.innerHTML = originalText;
        }
    }
}

/**
 * 监控多设备并行注册任务
 */
function monitorParallelTtRegisterTask(taskId) {
    // 保存当前任务ID
    currentParallelTtRegisterTaskId = taskId;
    
    // 显示结果容器
    const container = document.getElementById('ttParallelRegisterResultContainer');
    if (container) {
        container.style.display = 'block';
    }
    
    // 显示停止按钮
    const stopBtn = document.getElementById('stopParallelRegisterBtn');
    if (stopBtn) {
        stopBtn.style.display = 'inline-block';
        stopBtn.style.visibility = 'visible';
        stopBtn.disabled = false;
        stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止任务';
    }
    
    // 立即刷新一次
    refreshParallelTtRegisterTaskStatus(taskId);
    
    // 启动自动刷新（每5秒刷新一次）
    stopParallelTtRegisterRefresh(); // 先停止之前的
    parallelTtRegisterRefreshInterval = setInterval(() => {
        refreshParallelTtRegisterTaskStatus(taskId);
    }, 5000); // 每5秒刷新
}

/**
 * 刷新多设备并行注册任务状态
 */
function refreshParallelTtRegisterTaskStatus(taskId) {
    fetch(`/api/tt-register/status/${taskId}`)
        .then(response => response.json())
        .then(result => {
            if (result.success) {
                displayParallelTtRegisterTaskStatus(result);
                
                // 如果任务已完成、失败或已停止，停止自动刷新并隐藏停止按钮
                const status = result.status;
                if (status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED') {
                    stopParallelTtRegisterRefresh();
                    const stopBtn = document.getElementById('stopParallelRegisterBtn');
                    if (stopBtn) {
                        stopBtn.style.display = 'none';
                        stopBtn.style.visibility = 'hidden';
                    }
                    currentParallelTtRegisterTaskId = null;
                }
            } else {
                console.error('获取任务状态失败:', result.message);
            }
        })
        .catch(error => {
            console.error('获取任务状态失败:', error);
        });
}

/**
 * 停止多设备并行注册任务刷新
 */
function stopParallelTtRegisterRefresh() {
    if (parallelTtRegisterRefreshInterval) {
        clearInterval(parallelTtRegisterRefreshInterval);
        parallelTtRegisterRefreshInterval = null;
    }
}

/**
 * 显示多设备并行注册任务状态
 */
function displayParallelTtRegisterTaskStatus(taskStatus) {
    const content = document.getElementById('ttParallelRegisterResultContent');
    // 构建状态HTML
    const statusBadge = getTtRegisterStatusBadge(taskStatus.status);
    const progress = taskStatus.progress || 0;
    const totalCount = taskStatus.totalCount || 0;
    const successCount = taskStatus.successCount || 0;
    const failCount = taskStatus.failCount || 0;
    
    let html = `
        <div class="alert alert-info">
            <h5><i class="bi bi-info-circle"></i> 任务状态</h5>
            <div class="row">
                <div class="col-md-3">
                    <strong>任务ID:</strong> <code>${taskStatus.taskId}</code>
                </div>
                <div class="col-md-2">
                    <strong>状态:</strong> ${statusBadge}
                </div>
                <div class="col-md-2">
                    <strong>进度:</strong> ${progress.toFixed(1)}%
                </div>
                <div class="col-md-2">
                    <strong>成功:</strong> <span class="text-success">${successCount}</span>
                </div>
                <div class="col-md-3">
                    <strong>失败:</strong> <span class="text-danger">${failCount}</span>
                </div>
            </div>
            <div class="progress mt-2" style="height: 25px;">
                <div class="progress-bar" role="progressbar" style="width: ${progress}%">${progress.toFixed(1)}%</div>
            </div>
            ${taskStatus.startTime ? `<div class="mt-2"><small class="text-muted">开始时间: ${formatDateTime(taskStatus.startTime)}</small></div>` : ''}
            ${taskStatus.endTime ? `<div><small class="text-muted">结束时间: ${formatDateTime(taskStatus.endTime)}</small></div>` : ''}
        </div>
    `;
    
    // 显示提示信息（日志在应用日志中）
    html += `
        <div class="alert alert-warning">
            <i class="bi bi-info-circle"></i> 任务日志在应用日志中，请通过SSH查看应用日志文件，搜索任务ID: <code>${taskStatus.taskId}</code>
        </div>
    `;
    
    content.innerHTML = html;
}

/**
 * 清空多设备并行注册结果
 */
function clearParallelTtRegisterResult() {
    // 停止自动刷新
    stopParallelTtRegisterRefresh();
    
    // 清空结果容器
    const container = document.getElementById('ttParallelRegisterResultContainer');
    const content = document.getElementById('ttParallelRegisterResultContent');
    if (container) {
        container.style.display = 'none';
    }
    if (content) {
        content.innerHTML = '';
    }
    
    // 隐藏停止按钮
    const stopBtn = document.getElementById('stopParallelRegisterBtn');
    if (stopBtn) {
        stopBtn.style.display = 'none';
        stopBtn.style.visibility = 'hidden';
        stopBtn.disabled = false;
    }
    
    // 清除当前任务ID
    currentParallelTtRegisterTaskId = null;
    
    // 显示提示
    showAlert('结果已清空', 'info');
}

/**
 * 开始刷新任务列表
 */
function startParallelTasksListRefresh() {
    // 显示任务列表容器
    const container = document.getElementById('parallelTasksListContainer');
    if (container) {
        container.style.display = 'block';
        container.style.visibility = 'visible';
    }
    
    // 立即刷新一次
    refreshParallelTasksList();
    
    // 启动自动刷新（每5秒刷新一次）
    stopParallelTasksListRefresh();
    parallelTasksRefreshInterval = setInterval(() => {
        refreshParallelTasksList();
    }, 5000);
}

/**
 * 停止刷新任务列表
 */
function stopParallelTasksListRefresh() {
    if (parallelTasksRefreshInterval) {
        clearInterval(parallelTasksRefreshInterval);
        parallelTasksRefreshInterval = null;
    }
}

/**
 * 刷新任务列表
 */
function refreshParallelTasksList() {
    fetch('/api/tt-register/tasks')
        .then(response => response.json())
        .then(result => {
            if (result.success && result.data) {
                displayParallelTasksList(result.data);
            }
        })
        .catch(error => {
            console.error('刷新任务列表失败:', error);
        });
}

/**
 * 显示任务列表
 */
function displayParallelTasksList(tasks) {
    const content = document.getElementById('parallelTasksListContent');
    if (!content) return;
    
    // 确保容器可见
    const container = document.getElementById('parallelTasksListContainer');
    if (container) {
        container.style.display = 'block';
        container.style.visibility = 'visible';
    }
    
    // 过滤出运行中的任务
    const runningTasks = tasks.filter(task => 
        task.status === 'RUNNING' || task.status === 'STOPPING'
    );
    
    if (runningTasks.length === 0) {
        content.innerHTML = '<p class="text-muted">暂无运行中的任务</p>';
        return;
    }
    
    let html = '<div class="table-responsive"><table class="table table-hover">';
    html += '<thead><tr>';
    html += '<th>任务ID</th>';
    html += '<th>服务器IP</th>';
    html += '<th>设备数量</th>';
    html += '<th>状态</th>';
    html += '<th>成功/失败/总数</th>';
    html += '<th>开始时间</th>';
    html += '<th>操作</th>';
    html += '</tr></thead><tbody>';
    
    runningTasks.forEach(task => {
        const phoneIds = task.phoneIds || [];
        const deviceCount = phoneIds.length;
        const successCount = task.successCount || 0;
        const failCount = task.failCount || 0;
        const totalCount = task.totalCount || deviceCount;
        const status = task.status || 'UNKNOWN';
        const statusBadge = status === 'RUNNING' 
            ? '<span class="badge bg-success">运行中</span>'
            : status === 'STOPPING'
            ? '<span class="badge bg-warning">停止中</span>'
            : '<span class="badge bg-secondary">' + status + '</span>';
        
        html += '<tr>';
        html += '<td><code>' + (task.taskId || 'N/A') + '</code></td>';
        html += '<td>' + (task.serverIp || 'N/A') + '</td>';
        html += '<td>' + deviceCount + '</td>';
        html += '<td>' + statusBadge + '</td>';
        html += '<td><span class="text-success">' + successCount + '</span> / <span class="text-danger">' + failCount + '</span> / ' + totalCount + '</td>';
        html += '<td>' + (task.startTime || 'N/A') + '</td>';
        html += '<td>';
        html += '<button class="btn btn-sm btn-danger" onclick="stopParallelTask(\'' + task.taskId + '\')" title="停止任务">';
        html += '<i class="bi bi-stop-fill"></i> 停止';
        html += '</button>';
        html += ' <button class="btn btn-sm btn-info" onclick="viewParallelTaskDetail(\'' + task.taskId + '\')" title="查看详情">';
        html += '<i class="bi bi-eye"></i> 详情';
        html += '</button>';
        html += '</td>';
        html += '</tr>';
    });
    
    html += '</tbody></table></div>';
    content.innerHTML = html;
}

/**
 * 停止指定任务
 */
async function stopParallelTask(taskId) {
    if (!confirm('确认停止任务 ' + taskId + '？')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/tt-register/stop/${taskId}`, {
            method: 'POST'
        });
        
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务停止请求已发送', 'success');
            // 立即刷新任务列表
            refreshParallelTasksList();
        } else {
            showAlert('停止任务失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('停止任务失败:', error);
        showAlert('停止任务失败: ' + error.message, 'danger');
    }
}

/**
 * 查看任务详情
 */
function viewParallelTaskDetail(taskId) {
    // 设置为当前任务并显示详细结果
    currentParallelTtRegisterTaskId = taskId;
    monitorParallelTtRegisterTask(taskId);
    
    // 显示结果容器
    const container = document.getElementById('ttParallelRegisterResultContainer');
    if (container) {
        container.style.display = 'block';
    }
    
    // 更新停止按钮
    const stopBtn = document.getElementById('stopParallelRegisterBtn');
    if (stopBtn) {
        stopBtn.style.display = 'inline-block';
        stopBtn.style.visibility = 'visible';
        stopBtn.disabled = false;
        stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止当前任务';
    }
}

// ==================== Outlook邮箱注册相关函数 ====================

let currentOutlookParallelRegisterTaskId = null;
let outlookParallelTasksRefreshInterval = null;

/**
 * 开始Outlook邮箱注册（多设备并行）
 */
async function startOutlookParallelRegister(event) {
    const phoneIdsInput = document.getElementById('outlookParallelPhoneIdsInput');
    const serverIpInput = document.getElementById('outlookParallelServerIpInput');
    const maxConcurrencyInput = document.getElementById('outlookMaxConcurrencyInput');
    const targetCountPerDeviceInput = document.getElementById('outlookTargetCountPerDeviceInput');
    
    const phoneIdsText = phoneIdsInput.value.trim();
    const serverIp = serverIpInput.value.trim();
    const maxConcurrency = parseInt(maxConcurrencyInput.value.trim());
    const targetCountPerDevice = parseInt(targetCountPerDeviceInput.value.trim());
    
    if (!phoneIdsText) {
        showAlert('请输入云手机ID列表', 'warning');
        return;
    }
    
    const phoneIds = phoneIdsText.split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0);
    
    if (phoneIds.length === 0) {
        showAlert('请输入至少一个云手机ID', 'warning');
        return;
    }
    
    if (!serverIp) {
        showAlert('请输入服务器IP', 'warning');
        return;
    }
    
    if (!maxConcurrency || maxConcurrency < 1 || maxConcurrency > 50) {
        showAlert('请输入有效的最大并发数（1-50）', 'warning');
        return;
    }
    
    if (targetCountPerDevice < 0 || targetCountPerDevice > 1000) {
        showAlert('请输入有效的每个设备目标账号数（0表示无限循环，1-1000表示固定次数）', 'warning');
        return;
    }
    
    const totalTargetCount = targetCountPerDevice === 0 ? -1 : phoneIds.length * targetCountPerDevice;
    const targetCountDisplay = targetCountPerDevice === 0 ? '无限循环' : targetCountPerDevice;
    const totalCountDisplay = totalTargetCount === -1 ? '无限' : totalTargetCount;
    
    const confirmMsg = `确认使用 ${phoneIds.length} 个设备并行注册Outlook邮箱账号？\n\n服务器: ${serverIp}\n设备数量: ${phoneIds.length}\n每个设备目标账号数: ${targetCountDisplay}\n总目标账号数: ${totalCountDisplay}\n最大并发数: ${maxConcurrency}`;
    if (!confirm(confirmMsg)) {
        return;
    }
    
    const tiktokVersionDir = document.getElementById('outlookParallelTiktokVersionDirInput').value.trim();
    if (!tiktokVersionDir) {
        showAlert('请输入TikTok版本目录', 'warning');
        return;
    }
    
    const resetParams = {};
    const country = document.getElementById('outlookParallelCountryInput').value.trim();
    const sdk = document.getElementById('outlookParallelSdkInput').value.trim();
    const imagePath = document.getElementById('outlookParallelImagePathInput').value.trim();
    const gaidTag = document.getElementById('outlookParallelGaidTagInput').value.trim();
    let dynamicIpChannel = document.getElementById('outlookParallelDynamicIpChannelInput').value.trim();
    const staticIpChannel = document.getElementById('outlookParallelStaticIpChannelInput').value.trim();
    const biz = document.getElementById('outlookParallelBizInput').value.trim();
    
    if (!dynamicIpChannel) {
        const channels = ['ipidea', 'closeli', 'ipbiubiu'];
        dynamicIpChannel = channels[Math.floor(Math.random() * channels.length)];
    }
    
    if (country) resetParams.country = country;
    if (sdk) resetParams.sdk = sdk;
    if (imagePath) resetParams.imagePath = imagePath;
    if (gaidTag) resetParams.gaidTag = gaidTag;
    resetParams.dynamicIpChannel = dynamicIpChannel;
    if (staticIpChannel) resetParams.staticIpChannel = staticIpChannel;
    if (biz) resetParams.biz = biz;
    
    const submitBtn = event ? event.target : document.getElementById('startOutlookParallelRegisterBtn');
    if (!submitBtn) {
        console.error('找不到提交按钮');
        return;
    }
    const originalText = submitBtn.innerHTML;
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> 启动中...';
    
    try {
        const requestBody = {
            phoneIds: phoneIds,
            serverIp: serverIp,
            maxConcurrency: maxConcurrency,
            targetCountPerDevice: targetCountPerDevice,
            tiktokVersionDir: tiktokVersionDir
        };
        
        if (Object.keys(resetParams).length > 0) {
            requestBody.resetParams = resetParams;
        }
        
        const response = await fetch('/api/tt-register/outlook/parallel', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(requestBody)
        });
        
        const result = await response.json();
        
        if (result.success && result.taskId) {
            showAlert('Outlook邮箱注册任务已启动！任务ID: ' + result.taskId, 'success');
            currentOutlookParallelRegisterTaskId = result.taskId;
            
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<i class="bi bi-play-fill"></i> 开始Outlook注册';
            
            const stopBtn = document.getElementById('stopOutlookParallelRegisterBtn');
            if (stopBtn) {
                stopBtn.style.display = 'inline-block';
                stopBtn.style.visibility = 'visible';
                stopBtn.disabled = false;
                stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止当前任务';
            }
            
            monitorOutlookParallelRegisterTask(result.taskId);
            startOutlookParallelTasksListRefresh();
        } else {
            showAlert('Outlook注册任务启动失败: ' + (result.message || '未知错误'), 'danger');
            submitBtn.disabled = false;
            submitBtn.innerHTML = originalText;
        }
        
    } catch (error) {
        console.error('Outlook注册失败:', error);
        showAlert('Outlook注册失败: ' + error.message, 'danger');
        submitBtn.disabled = false;
        submitBtn.innerHTML = originalText;
    }
}

/**
 * 停止Outlook注册任务
 */
async function stopOutlookParallelRegisterTask() {
    if (!currentOutlookParallelRegisterTaskId) {
        showAlert('没有正在运行的任务', 'warning');
        return;
    }
    
    const confirmMsg = `确认停止任务 ${currentOutlookParallelRegisterTaskId}？`;
    if (!confirm(confirmMsg)) {
        return;
    }
    
    const stopBtn = document.getElementById('stopOutlookParallelRegisterBtn');
    const originalText = stopBtn ? stopBtn.innerHTML : '';
    if (stopBtn) {
        stopBtn.disabled = true;
        stopBtn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> 停止中...';
    }
    
    try {
        const response = await fetch(`/api/tt-register/stop/${currentOutlookParallelRegisterTaskId}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });
        
        if (!response.ok) {
            throw new Error(`HTTP错误: ${response.status}`);
        }
        
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务停止请求已发送', 'success');
            if (stopBtn) {
                stopBtn.disabled = false;
                stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止中...';
            }
        } else {
            showAlert('停止任务失败: ' + (result.message || '未知错误'), 'danger');
            if (stopBtn) {
                stopBtn.disabled = false;
                stopBtn.innerHTML = originalText;
            }
        }
        
    } catch (error) {
        console.error('停止任务失败:', error);
        showAlert('停止任务失败: ' + error.message, 'danger');
        if (stopBtn) {
            stopBtn.disabled = false;
            stopBtn.innerHTML = originalText;
        }
    }
}

/**
 * 监控Outlook注册任务
 */
function monitorOutlookParallelRegisterTask(taskId) {
    stopOutlookParallelRegisterRefresh();
    const interval = setInterval(() => {
        refreshOutlookParallelRegisterTaskStatus(taskId);
    }, 3000);
    window['outlookParallelRegisterRefreshInterval'] = interval;
}

/**
 * 刷新Outlook注册任务状态
 */
function refreshOutlookParallelRegisterTaskStatus(taskId) {
    fetch(`/api/tt-register/status/${taskId}`)
        .then(response => response.json())
        .then(result => {
            if (result.success && result.data) {
                displayOutlookParallelRegisterTaskStatus(result.data);
            }
        })
        .catch(error => {
            console.error('刷新任务状态失败:', error);
        });
}

/**
 * 显示Outlook注册任务状态
 */
function displayOutlookParallelRegisterTaskStatus(taskData) {
    const container = document.getElementById('outlookParallelRegisterResultContainer');
    const content = document.getElementById('outlookParallelRegisterResultContent');
    if (!container || !content) return;
    
    container.style.display = 'block';
    
    const status = taskData.status || 'UNKNOWN';
    const successCount = taskData.successCount || 0;
    const failCount = taskData.failCount || 0;
    const totalCount = taskData.totalCount || 0;
    const progress = taskData.progress || 0;
    const deviceResults = taskData.deviceResults || [];
    
    let html = '<div class="alert alert-info">';
    html += '<h5><i class="bi bi-info-circle"></i> 任务状态</h5>';
    html += '<p><strong>任务ID:</strong> <code>' + taskData.taskId + '</code></p>';
    html += '<p><strong>状态:</strong> <span class="badge bg-' + (status === 'RUNNING' ? 'success' : status === 'COMPLETED' ? 'primary' : 'danger') + '">' + status + '</span></p>';
    html += '<p><strong>成功:</strong> <span class="text-success">' + successCount + '</span> | ';
    html += '<strong>失败:</strong> <span class="text-danger">' + failCount + '</span>';
    if (totalCount > 0) {
        html += ' | <strong>总数:</strong> ' + totalCount + ' | <strong>进度:</strong> ' + progress + '%';
    }
    html += '</p>';
    html += '</div>';
    
    if (deviceResults.length > 0) {
        html += '<h5><i class="bi bi-list-check"></i> 设备详情</h5>';
        html += '<div class="table-responsive"><table class="table table-hover">';
        html += '<thead><tr><th>设备ID</th><th>状态</th><th>成功/失败/目标</th><th>轮次详情</th></tr></thead><tbody>';
        
        deviceResults.forEach(device => {
            const deviceStatus = device.status || 'UNKNOWN';
            const deviceSuccessCount = device.successCount || 0;
            const deviceFailCount = device.failCount || 0;
            const deviceTargetCount = device.targetCount || 0;
            const roundStatuses = device.roundStatuses || [];
            
            html += '<tr>';
            html += '<td><code>' + (device.phoneId || 'N/A') + '</code></td>';
            html += '<td><span class="badge bg-' + (deviceStatus.includes('SUCCESS') ? 'success' : deviceStatus.includes('FAILED') ? 'danger' : 'warning') + '">' + deviceStatus + '</span></td>';
            html += '<td><span class="text-success">' + deviceSuccessCount + '</span> / <span class="text-danger">' + deviceFailCount + '</span> / ' + (deviceTargetCount === -1 ? '无限' : deviceTargetCount) + '</td>';
            html += '<td><ul class="mb-0">';
            roundStatuses.forEach(roundStatus => {
                html += '<li>' + roundStatus + '</li>';
            });
            html += '</ul></td>';
            html += '</tr>';
        });
        
        html += '</tbody></table></div>';
    }
    
    content.innerHTML = html;
    
    if (status === 'COMPLETED' || status === 'STOPPED' || status === 'FAILED') {
        stopOutlookParallelRegisterRefresh();
    }
}

/**
 * 停止刷新Outlook注册任务状态
 */
function stopOutlookParallelRegisterRefresh() {
    if (window['outlookParallelRegisterRefreshInterval']) {
        clearInterval(window['outlookParallelRegisterRefreshInterval']);
        window['outlookParallelRegisterRefreshInterval'] = null;
    }
}

/**
 * 清空Outlook注册结果
 */
function clearOutlookParallelRegisterResult() {
    stopOutlookParallelRegisterRefresh();
    
    const container = document.getElementById('outlookParallelRegisterResultContainer');
    const content = document.getElementById('outlookParallelRegisterResultContent');
    if (container) {
        container.style.display = 'none';
    }
    if (content) {
        content.innerHTML = '';
    }
    
    const stopBtn = document.getElementById('stopOutlookParallelRegisterBtn');
    if (stopBtn) {
        stopBtn.style.display = 'none';
        stopBtn.style.visibility = 'hidden';
        stopBtn.disabled = false;
    }
    
    currentOutlookParallelRegisterTaskId = null;
    showAlert('结果已清空', 'info');
}

/**
 * 开始刷新Outlook任务列表
 */
function startOutlookParallelTasksListRefresh() {
    const container = document.getElementById('outlookParallelTasksListContainer');
    if (container) {
        container.style.display = 'block';
        container.style.visibility = 'visible';
    }
    
    refreshOutlookParallelTasksList();
    stopOutlookParallelTasksListRefresh();
    outlookParallelTasksRefreshInterval = setInterval(() => {
        refreshOutlookParallelTasksList();
    }, 5000);
}

/**
 * 停止刷新Outlook任务列表
 */
function stopOutlookParallelTasksListRefresh() {
    if (outlookParallelTasksRefreshInterval) {
        clearInterval(outlookParallelTasksRefreshInterval);
        outlookParallelTasksRefreshInterval = null;
    }
}

/**
 * 刷新Outlook任务列表
 */
function refreshOutlookParallelTasksList() {
    fetch('/api/tt-register/tasks')
        .then(response => response.json())
        .then(result => {
            if (result.success && result.data) {
                displayOutlookParallelTasksList(result.data);
            }
        })
        .catch(error => {
            console.error('刷新任务列表失败:', error);
        });
}

/**
 * 显示Outlook任务列表
 */
function displayOutlookParallelTasksList(tasks) {
    const content = document.getElementById('outlookParallelTasksListContent');
    if (!content) return;
    
    const container = document.getElementById('outlookParallelTasksListContainer');
    if (container) {
        container.style.display = 'block';
        container.style.visibility = 'visible';
    }
    
    // 过滤出Outlook相关的运行中任务
    const runningTasks = tasks.filter(task => 
        (task.status === 'RUNNING' || task.status === 'STOPPING') && 
        task.taskId && task.taskId.startsWith('OUTLOOK_PARALLEL_REGISTER_')
    );
    
    if (runningTasks.length === 0) {
        content.innerHTML = '<p class="text-muted">暂无运行中的Outlook注册任务</p>';
        return;
    }
    
    let html = '<div class="table-responsive"><table class="table table-hover">';
    html += '<thead><tr>';
    html += '<th>任务ID</th>';
    html += '<th>服务器IP</th>';
    html += '<th>设备数量</th>';
    html += '<th>状态</th>';
    html += '<th>成功/失败/总数</th>';
    html += '<th>开始时间</th>';
    html += '<th>操作</th>';
    html += '</tr></thead><tbody>';
    
    runningTasks.forEach(task => {
        const phoneIds = task.phoneIds || [];
        const deviceCount = phoneIds.length;
        const successCount = task.successCount || 0;
        const failCount = task.failCount || 0;
        const totalCount = task.totalCount || deviceCount;
        const status = task.status || 'UNKNOWN';
        const statusBadge = status === 'RUNNING' 
            ? '<span class="badge bg-success">运行中</span>'
            : status === 'STOPPING'
            ? '<span class="badge bg-warning">停止中</span>'
            : '<span class="badge bg-secondary">' + status + '</span>';
        
        html += '<tr>';
        html += '<td><code>' + (task.taskId || 'N/A') + '</code></td>';
        html += '<td>' + (task.serverIp || 'N/A') + '</td>';
        html += '<td>' + deviceCount + '</td>';
        html += '<td>' + statusBadge + '</td>';
        html += '<td><span class="text-success">' + successCount + '</span> / <span class="text-danger">' + failCount + '</span> / ' + totalCount + '</td>';
        html += '<td>' + (task.startTime || 'N/A') + '</td>';
        html += '<td>';
        html += '<button class="btn btn-sm btn-danger" onclick="stopOutlookParallelTask(\'' + task.taskId + '\')" title="停止任务">';
        html += '<i class="bi bi-stop-fill"></i> 停止';
        html += '</button>';
        html += ' <button class="btn btn-sm btn-info" onclick="viewOutlookParallelTaskDetail(\'' + task.taskId + '\')" title="查看详情">';
        html += '<i class="bi bi-eye"></i> 详情';
        html += '</button>';
        html += '</td>';
        html += '</tr>';
    });
    
    html += '</tbody></table></div>';
    content.innerHTML = html;
}

/**
 * 停止指定Outlook任务
 */
async function stopOutlookParallelTask(taskId) {
    if (!confirm('确认停止任务 ' + taskId + '？')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/tt-register/stop/${taskId}`, {
            method: 'POST'
        });
        
        const result = await response.json();
        
        if (result.success) {
            showAlert('任务停止请求已发送', 'success');
            refreshOutlookParallelTasksList();
        } else {
            showAlert('停止任务失败: ' + (result.message || '未知错误'), 'danger');
        }
    } catch (error) {
        console.error('停止任务失败:', error);
        showAlert('停止任务失败: ' + error.message, 'danger');
    }
}

/**
 * 查看Outlook任务详情
 */
function viewOutlookParallelTaskDetail(taskId) {
    currentOutlookParallelRegisterTaskId = taskId;
    monitorOutlookParallelRegisterTask(taskId);
    
    const container = document.getElementById('outlookParallelRegisterResultContainer');
    if (container) {
        container.style.display = 'block';
    }
    
    const stopBtn = document.getElementById('stopOutlookParallelRegisterBtn');
    if (stopBtn) {
        stopBtn.style.display = 'inline-block';
        stopBtn.style.visibility = 'visible';
        stopBtn.disabled = false;
        stopBtn.innerHTML = '<i class="bi bi-stop-fill"></i> 停止当前任务';
    }
}
