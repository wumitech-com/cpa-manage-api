// 任务列表页：列表 + 编辑/恢复
(function() {
  var tbody = document.getElementById('task-table-body');
  var editTaskId = document.getElementById('edit-task-id');
  var taskPageInfo = document.getElementById('task-page-info');
  var taskPrevBtn = document.getElementById('task-prev');
  var taskNextBtn = document.getElementById('task-next');
  var taskPageSizeSel = document.getElementById('task-page-size');
  var taskFilterPhoneIdEl = document.getElementById('task-filter-phoneid');
  var taskFilterStatusEl = document.getElementById('task-filter-status');
  var taskFilterServerIpEl = document.getElementById('task-filter-serverip');
  var taskResetBtn = document.getElementById('task-reset-btn');
  var taskTotalCountEl = document.getElementById('task-total-count');
  var taskQueryBtn = document.getElementById('task-query-btn');
  var forceMockPage = /(?:\?|&)mock=1(?:&|$)/.test(location.search || '');
  var taskPage = 1;
  var taskSize = taskPageSizeSel ? parseInt(taskPageSizeSel.value, 10) || 50 : 50;
  var taskTotalPages = 1;
  if (!tbody || !editTaskId) return;
  var shared = window.ConsoleSharedUi;
  var format = shared && shared.format;
  var network = shared && shared.network;
  var table = shared && shared.table;
  var feedback = shared && shared.feedback;
  var fmtTime = format && format.fmtTime;
  var fetchJson = network && network.fetchJson;
  var fetchJsonSafe = network && network.fetchJsonSafe;
  var buildErrorMessage = network && network.buildErrorMessage;
  var renderTableMessage = table && table.renderTableMessage;
  var updatePagerState = table && table.updatePagerState;
  var TABLE_TEXT = table && table.TABLE_TEXT;
  var notify = feedback && feedback.notify;
  if (!fmtTime || !fetchJson || !fetchJsonSafe || !renderTableMessage || !buildErrorMessage || !updatePagerState || !notify || !TABLE_TEXT) return;

  function buildMockTasks() {
    var servers = ['10.7.8.64', '10.7.59.169', '10.7.117.117', '10.7.169.151', '10.7.136.129'];
    var statuses = ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'STOPPED'];
    var tasks = [];
    var base = new Date();
    for (var i = 1; i <= 45; i++) {
      var status = statuses[i % statuses.length];
      if (i % 7 === 0) status = 'RUNNING';
      if (i % 9 === 0) status = 'PENDING';
      var serverIp = servers[i % servers.length];
      var phoneId = 'tt_farm_' + serverIp.replace(/\./g, '_') + '_' + String(i * 10).padStart(4, '0');
      var phoneIds = [phoneId];
      var successCount = i % 12;
      var failCount = (i + 3) % 4;
      if (status === 'PENDING') { successCount = 0; failCount = 0; }
      if (status === 'RUNNING') { successCount = (i % 10); failCount = (i % 3); }
      var totalCount = successCount + failCount;
      var start = new Date(base.getTime() - i * 3600 * 1000);
      var startIso = start.toISOString().slice(0, 19);
      var end = null;
      if (status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED') {
        var endDt = new Date(start.getTime() + (i % 5 + 1) * 3600 * 1000);
        end = endDt.toISOString().slice(0, 19);
      }
      tasks.push({
        taskId: 'task_' + String(i).padStart(3, '0'),
        status: status,
        serverIp: serverIp,
        phoneId: phoneId,
        phoneIds: phoneIds,
        successCount: status === 'PENDING' ? null : successCount,
        failCount: status === 'PENDING' ? null : failCount,
        totalCount: status === 'PENDING' ? null : totalCount,
        startTime: startIso,
        endTime: end,
        pid: null,
        logFile: null
      });
    }
    tasks.sort(function(a, b) { return (b.startTime || '').localeCompare(a.startTime || ''); });
    return tasks;
  }

  function renderTaskList(list, total) {
    updateTaskPager(total);
    if (!list.length) {
      renderTableMessage(tbody, 6, TABLE_TEXT.EMPTY_TASK);
      return;
    }
    tbody.innerHTML = list.map(function(t) {
      var rowCls = (t.status === 'RUNNING' ? ' class="task-running"' : '');
      var phoneIdsArr = (t.phoneIds && t.phoneIds.length) ? t.phoneIds : [];
      var phoneText = t.phoneId || (phoneIdsArr.length ? phoneIdsArr[0] : '');
      var phoneTitle = phoneText || '-';
      var startTime = t.startTime || t.createdAt;
      var endTime = t.endTime || t.updatedAt;
      return '<tr' + rowCls + ' data-task-id="' + t.taskId + '">' +
        '<td>' + statusBadge(t.status) + '</td>' +
        '<td><span class="cell-truncate" title="' + (t.serverIp || '') + '">' + (t.serverIp || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + phoneTitle + '">' + (phoneText || '-') + '</span></td>' +
        '<td>' + fmtTime(startTime) + '</td>' +
        '<td>' + fmtTime(endTime) + '</td>' +
        '<td><button type="button" class="ui-btn ghost btn-stop-task" data-id="' + t.taskId + '">停止</button></td>' +
        '</tr>';
    }).join('');

    tbody.querySelectorAll('tr').forEach(function(tr) {
      tr.addEventListener('click', function(e) {
        if (e.target && e.target.classList.contains('btn-stop-task')) return;
        var tid = this.getAttribute('data-task-id');
        if (!tid) return;
        editTaskId.value = tid;
      });
    });

    tbody.querySelectorAll('.btn-stop-task').forEach(function(btn) {
      btn.addEventListener('click', function(e) {
        e.stopPropagation();
        var tid = this.getAttribute('data-id');
        if (!tid) return;
        if (forceMockPage) {
          notify('Mock 模式：停止功能不执行');
          return;
        }
        fetchJson('/api/tt-register/stop/' + encodeURIComponent(tid), { method: 'POST' })
          .then(function(res2) {
            notify(res2.message || (res2.success ? '任务已停止' : '停止失败'));
            loadTasks();
          })
          .catch(function() { notify('停止任务失败，请稍后重试'); });
      });
    });
  }

  function statusBadge(status) {
    var cls = 'status ';
    switch (status) {
      case 'RUNNING': cls += 'running'; break;
      case 'COMPLETED': cls += 'completed'; break;
      case 'PENDING': cls += 'pending'; break;
      case 'FAILED': cls += 'failed'; break;
      case 'STOPPED': cls += 'stopped'; break;
      default: cls += 'pending';
    }
    return '<span class="' + cls + '">' + (status || '-') + '</span>';
  }
  function updateTaskPager(total) {
    var pager = updatePagerState({
      page: taskPage,
      size: taskSize,
      total: total,
      pageInfoEl: taskPageInfo,
      prevBtn: taskPrevBtn,
      nextBtn: taskNextBtn,
      totalCountEl: taskTotalCountEl
    });
    taskPage = pager.page;
    taskTotalPages = pager.totalPages;
  }

  function loadTasks() {
    if (forceMockPage) {
      var all = buildMockTasks();
      var phoneIdFilter = taskFilterPhoneIdEl && taskFilterPhoneIdEl.value ? taskFilterPhoneIdEl.value.trim() : '';
      var statusFilter = taskFilterStatusEl && taskFilterStatusEl.value ? taskFilterStatusEl.value : '';
      var serverIpFilter = taskFilterServerIpEl && taskFilterServerIpEl.value ? taskFilterServerIpEl.value.trim() : '';
      var filtered = all.filter(function(t) {
        var ok = true;
        if (statusFilter && statusFilter !== 'ALL') ok = ok && String(t.status || '') === statusFilter;
        if (serverIpFilter) ok = ok && String(t.serverIp || '') === serverIpFilter;
        if (phoneIdFilter) {
          var onePhone = t.phoneId || (t.phoneIds && t.phoneIds.length ? t.phoneIds[0] : '');
          ok = ok && String(onePhone || '') === phoneIdFilter;
        }
        return ok;
      });
      var total = filtered.length;
      var startIdx = (taskPage - 1) * taskSize;
      var list = filtered.slice(startIdx, startIdx + taskSize);
      renderTaskList(list, total);
      return;
    }

    var url = '/api/tt-register/tasks?page=' + encodeURIComponent(taskPage) + '&size=' + encodeURIComponent(taskSize);
    var statusFilter = taskFilterStatusEl && taskFilterStatusEl.value ? taskFilterStatusEl.value : '';
    if (statusFilter && statusFilter !== 'ALL') url += '&status=' + encodeURIComponent(statusFilter);
    var serverIpFilter = taskFilterServerIpEl && taskFilterServerIpEl.value ? taskFilterServerIpEl.value.trim() : '';
    if (serverIpFilter) url += '&serverIp=' + encodeURIComponent(serverIpFilter);
    var phoneIdFilter = taskFilterPhoneIdEl && taskFilterPhoneIdEl.value ? taskFilterPhoneIdEl.value.trim() : '';
    if (phoneIdFilter) url += '&phoneId=' + encodeURIComponent(phoneIdFilter);
    url = url.replace('/api/tt-register/tasks', '/api/tt-register/task/list');

    fetchJsonSafe(url)
      .then(function(res) {
        if (!res || !res.success) {
          renderTableMessage(tbody, 6, buildErrorMessage('加载失败', res), 'var(--error)');
          updateTaskPager(0);
          return;
        }
        var data = res.data || {};
        renderTaskList(data.list || [], data.total != null ? data.total : 0);
      })
      .catch(function() {
        renderTableMessage(tbody, 6, TABLE_TEXT.LOAD_FAILED, 'var(--error)');
        updateTaskPager(0);
      });
  }

  function collectEditForm() {
    var data = { taskId: editTaskId.value.trim() };
    var v;
    v = document.getElementById('edit-country').value.trim(); if (v) data.country = v;
    v = document.getElementById('edit-sdk').value.trim(); if (v) data.sdk = v;
    v = document.getElementById('edit-image-path').value.trim(); if (v) data.imagePath = v;
    v = document.getElementById('edit-gaid-tag').value.trim(); if (v) data.gaidTag = v;
    v = document.getElementById('edit-dynamic-ip').value.trim(); if (v) data.dynamicIpChannel = v;
    v = document.getElementById('edit-static-ip').value.trim(); if (v) data.staticIpChannel = v;
    v = document.getElementById('edit-biz').value.trim(); if (v) data.biz = v;
    v = document.getElementById('edit-target-count').value.trim(); if (v) data.targetCount = v;
    return data;
  }

  var btnSave = document.getElementById('btn-task-save');
  var btnResume = document.getElementById('btn-task-resume');
  if (taskPageSizeSel) taskPageSizeSel.addEventListener('change', function() {
    var v = parseInt(this.value, 10);
    if (!v || v < 1) v = 50;
    taskSize = v;
  });
  if (taskQueryBtn) taskQueryBtn.addEventListener('click', function() {
    var v = taskPageSizeSel ? parseInt(taskPageSizeSel.value, 10) : taskSize;
    if (!v || v < 1) v = 50;
    taskSize = v;
    taskPage = 1;
    loadTasks();
  });
  if (taskResetBtn) taskResetBtn.addEventListener('click', function() {
    if (taskFilterStatusEl) taskFilterStatusEl.value = 'ALL';
    if (taskFilterServerIpEl) taskFilterServerIpEl.value = '';
    if (taskFilterPhoneIdEl) taskFilterPhoneIdEl.value = '';
    taskPage = 1;
    loadTasks();
  });
  if (taskPrevBtn) taskPrevBtn.addEventListener('click', function() { if (taskPage > 1) { taskPage--; loadTasks(); } });
  if (taskNextBtn) taskNextBtn.addEventListener('click', function() { if (taskPage < taskTotalPages) { taskPage++; loadTasks(); } });

  if (btnSave) {
    btnSave.addEventListener('click', function() {
      if (!editTaskId.value.trim()) { notify('请先在上面的列表中点击一个任务～'); return; }
      fetchJson('/api/tt-register/task/update', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(collectEditForm())
      })
        .then(function(res) { notify(res.message || (res.success ? '保存成功' : '保存失败')); })
        .catch(function() { notify('保存失败，请稍后重试'); });
    });
  }

  if (btnResume) {
    btnResume.addEventListener('click', function() {
      var tid = editTaskId.value.trim();
      if (!tid) { notify('请先在上面的列表中点击一个任务～'); return; }
      fetchJson('/api/tt-register/task/resume/' + encodeURIComponent(tid), { method: 'POST' })
        .then(function(res) {
          notify(res.message || (res.success ? '任务已恢复' : '恢复失败'));
          loadTasks();
        })
        .catch(function() { notify('恢复失败，请稍后重试'); });
    });
  }

  var taskLoaded = false;
  window.addEventListener('page:changed', function(e) {
    if (!e || !e.detail || e.detail.pageId !== 'accounts') return;
    if (taskLoaded) return;
    taskLoaded = true;
    loadTasks();
  });
  var accountsPage = document.getElementById('page-accounts');
  if (accountsPage && accountsPage.classList.contains('active')) {
    taskLoaded = true;
    loadTasks();
  }
})();
