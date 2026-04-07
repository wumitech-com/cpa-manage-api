// 查看设备：按 phone_id + gaid 恢复并返回连接命令
(function() {
  var phoneEl = document.getElementById('di-phone-id');
  var gaidEl = document.getElementById('di-gaid');
  var runBtn = document.getElementById('di-run-btn');
  var resetBtn = document.getElementById('di-reset-btn');
  var resultEl = document.getElementById('di-result');
  if (!phoneEl || !gaidEl || !runBtn || !resultEl) return;
  var shared = window.ConsoleSharedUi;
  var format = shared && shared.format;
  var network = shared && shared.network;
  var table = shared && shared.table;
  var feedback = shared && shared.feedback;
  var esc = format && format.esc;
  var fetchJson = network && network.fetchJson;
  var fetchJsonSafe = network && network.fetchJsonSafe;
  var buildErrorMessage = network && network.buildErrorMessage;
  var renderTableMessage = table && table.renderTableMessage;
  var updatePagerState = table && table.updatePagerState;
  var TABLE_TEXT = table && table.TABLE_TEXT;
  var withButtonLoading = feedback && feedback.withButtonLoading;
  if (!esc || !fetchJson || !fetchJsonSafe || !renderTableMessage || !buildErrorMessage || !updatePagerState || !withButtonLoading || !TABLE_TEXT) return;

  function renderMessage(msg, isError) {
    resultEl.innerHTML = '<div style="color:' + (isError ? 'var(--error)' : '#475569') + ';">' + esc(msg || '') + '</div>';
  }

  function renderSuccess(data, message) {
    var tunnel = data && data.tunnelCommand ? String(data.tunnelCommand) : '';
    var adb = data && data.adbConnectCommand ? String(data.adbConnectCommand) : '';
    var tips = (data && data.tips && Array.isArray(data.tips)) ? data.tips : [
      '第一步：先在本机执行端口转发命令',
      '第二步：执行 adb connect 命令连接设备'
    ];
    resultEl.innerHTML =
      '<div style="margin-bottom:8px;color:#0F766E;">' + esc(message || '恢复成功') + '</div>' +
      '<div style="margin-bottom:6px;"><strong>步骤提示：</strong></div>' +
      '<ol style="margin:0 0 10px 20px;padding:0;">' +
        tips.map(function(t){ return '<li style="margin:2px 0;">' + esc(t) + '</li>'; }).join('') +
      '</ol>' +
      '<div style="margin:8px 0 4px;color:#64748B;">端口转发命令</div>' +
      '<div style="margin:0 0 6px;display:flex;justify-content:flex-end;">' +
        '<button type="button" id="di-copy-tunnel" class="ui-btn ghost" style="height:26px;padding:2px 10px;font-size:0.76rem;border-radius:999px;">复制端口转发命令</button>' +
      '</div>' +
      '<pre style="margin:0;padding:10px;border:1px solid #E5E7EB;border-radius:10px;background:#F8FAFC;white-space:pre-wrap;word-break:break-all;">' + esc(tunnel) + '</pre>' +
      '<div style="margin:10px 0 4px;color:#64748B;">ADB 连接命令</div>' +
      '<div style="margin:0 0 6px;display:flex;justify-content:flex-end;">' +
        '<button type="button" id="di-copy-adb" class="ui-btn ghost" style="height:26px;padding:2px 10px;font-size:0.76rem;border-radius:999px;">复制ADB命令</button>' +
      '</div>' +
      '<pre style="margin:0;padding:10px;border:1px solid #E5E7EB;border-radius:10px;background:#F8FAFC;white-space:pre-wrap;word-break:break-all;">' + esc(adb) + '</pre>' +
      '<div style="margin-top:10px;color:#94A3B8;">server_ip: ' + esc(data && data.serverIp || '-') + '，adb_port: ' + esc(data && data.adbPort || '-') + '</div>' +
      '<div id="di-copy-tip" style="margin-top:6px;color:#0F766E;"></div>';

    var copyTunnelBtn = document.getElementById('di-copy-tunnel');
    var copyAdbBtn = document.getElementById('di-copy-adb');
    var copyTipEl = document.getElementById('di-copy-tip');
    function showCopyTip(msg, ok) {
      if (!copyTipEl) return;
      copyTipEl.style.color = ok ? '#0F766E' : 'var(--error)';
      copyTipEl.textContent = msg || '';
    }
    function copyText(text, okMsg) {
      if (!navigator.clipboard || !text) {
        showCopyTip('复制失败：浏览器不支持或命令为空', false);
        return;
      }
      navigator.clipboard.writeText(text)
        .then(function() { showCopyTip(okMsg, true); })
        .catch(function() { showCopyTip('复制失败，请手动复制', false); });
    }
    if (copyTunnelBtn) copyTunnelBtn.addEventListener('click', function() { copyText(tunnel, '已复制端口转发命令'); });
    if (copyAdbBtn) copyAdbBtn.addEventListener('click', function() { copyText(adb, '已复制ADB连接命令'); });
  }

  function run() {
    var phoneId = (phoneEl.value || '').trim();
    var gaid = (gaidEl.value || '').trim();
    if (!phoneId || !gaid) {
      renderMessage('phone_id 和 gaid 不能为空', true);
      return;
    }
    renderMessage('正在恢复环境并查询端口，请稍候…', false);
    withButtonLoading(runBtn, null, function() {
      return fetchJson('/api/tt-register/device/inspect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phoneId: phoneId, gaid: gaid })
      }).then(function(res) {
        if (!res || !res.success) {
          renderMessage((res && res.message) || '操作失败', true);
          return;
        }
        renderSuccess(res.data || {}, res.message || '恢复成功');
      });
    }).catch(function() {
      renderMessage('请求失败，请稍后重试', true);
    });
  }

  runBtn.addEventListener('click', run);
  gaidEl.addEventListener('keydown', function(e) { if (e.key === 'Enter') run(); });
  if (resetBtn) {
    resetBtn.addEventListener('click', function() {
      phoneEl.value = '';
      gaidEl.value = '';
      renderMessage('请先输入 phone_id 和 gaid，再点击“恢复并生成命令”。', false);
    });
  }
})();

// 留存信息：按日期加载留存记录
(function() {
  var dateInput = document.getElementById('retention-date');
  var tbody = document.getElementById('ret-tbody');
  var retPageInfo = document.getElementById('ret-page-info');
  var retPrevBtn = document.getElementById('ret-prev');
  var retNextBtn = document.getElementById('ret-next');
  var retPageSizeSel = document.getElementById('ret-page-size');
  var retQueryBtn = document.getElementById('ret-query-btn');
  var retPage = 1;
  var retSize = retPageSizeSel ? parseInt(retPageSizeSel.value, 10) || 50 : 50;
  var retTotalPages = 1;
  var shared = window.ConsoleSharedUi;
  var format = shared && shared.format;
  var network = shared && shared.network;
  var table = shared && shared.table;
  var toYmd = format && format.toYmd;
  var fetchJsonSafe = network && network.fetchJsonSafe;
  var buildErrorMessage = network && network.buildErrorMessage;
  var renderTableMessage = table && table.renderTableMessage;
  var updatePagerState = table && table.updatePagerState;
  var TABLE_TEXT = table && table.TABLE_TEXT;
  if (!toYmd || !fetchJsonSafe || !buildErrorMessage || !renderTableMessage || !updatePagerState || !TABLE_TEXT) return;
  function updateRetPager(total) {
    var pager = updatePagerState({
      page: retPage,
      size: retSize,
      total: total,
      pageInfoEl: retPageInfo,
      prevBtn: retPrevBtn,
      nextBtn: retNextBtn
    });
    retPage = pager.page;
    retTotalPages = pager.totalPages;
  }

  function loadRetention(dateStr) {
    if (!tbody) return;
    var params = [];
    if (dateStr) params.push('date=' + encodeURIComponent(dateStr));
    params.push('page=' + encodeURIComponent(retPage));
    params.push('size=' + encodeURIComponent(retSize));
    var url = '/api/statistics/retention-records?' + params.join('&');
    renderTableMessage(tbody, 7, TABLE_TEXT.LOADING);
    fetchJsonSafe(url)
      .then(function(res) {
        if (!res || !res.success || !res.data) {
          renderTableMessage(tbody, 7, buildErrorMessage('加载失败', res), 'var(--error)');
          updateRetPager(0);
          return;
        }
        var d = res.data;
        updateRetPager(d.total || 0);
        document.getElementById('ret-total').textContent = d.total || 0;
        document.getElementById('ret-script-ok').textContent = d.scriptSuccessCount || 0;
        document.getElementById('ret-success-rate').textContent = (d.successRate != null ? d.successRate : 0) + '%';
        document.getElementById('ret-backup-ok').textContent = d.backupSuccessCount || 0;
        document.getElementById('ret-backup-rate').textContent = (d.backupRate != null ? d.backupRate : 0) + '%';
        document.getElementById('ret-2fa-success').textContent = d.retention2faSuccess != null ? d.retention2faSuccess : 0;
        document.getElementById('ret-logout').textContent = d.retentionLogout != null ? d.retentionLogout : 0;
        var ct = d.cohortTotal != null ? d.cohortTotal : 0;
        var cb = d.cohortBlocked != null ? d.cohortBlocked : 0;
        var clo = d.cohortLogout != null ? d.cohortLogout : 0;
        var cr = d.cohortBlockRate != null ? d.cohortBlockRate + '%' : '0%';
        var act = d.allCohortTotal != null ? d.allCohortTotal : 0;
        var acb = d.allCohortBlocked != null ? d.allCohortBlocked : 0;
        var aclo = d.allCohortLogout != null ? d.allCohortLogout : 0;
        var acr = d.allCohortBlockRate != null ? d.allCohortBlockRate + '%' : '0%';
        var elCt = document.getElementById('ret-cohort-total');
        var elCb = document.getElementById('ret-cohort-blocked');
        var elClo = document.getElementById('ret-cohort-logout');
        var elCr = document.getElementById('ret-cohort-rate');
        var elAct = document.getElementById('ret-all-cohort-total');
        var elAcb = document.getElementById('ret-all-cohort-blocked');
        var elAclo = document.getElementById('ret-all-cohort-logout');
        var elAcr = document.getElementById('ret-all-cohort-rate');
        if (elCt) elCt.textContent = ct;
        if (elCb) elCb.textContent = cb;
        if (elClo) elClo.textContent = clo;
        if (elCr) elCr.textContent = cr;
        if (elAct) elAct.textContent = act;
        if (elAcb) elAcb.textContent = acb;
        if (elAclo) elAclo.textContent = aclo;
        if (elAcr) elAcr.textContent = acr;
        var heartsWrap = document.getElementById('ret-hearts');
        if (heartsWrap) {
          heartsWrap.innerHTML = '';
          if ((d.successRate || 0) >= 60) {
            var count = d.successRate >= 90 ? 3 : (d.successRate >= 75 ? 2 : 1);
            for (var i = 0; i < count; i++) {
              var span = document.createElement('span');
              span.textContent = '♡';
              span.style.left = (20 + i * 16) + '%';
              span.style.animationDelay = (i * 0.25) + 's';
              heartsWrap.appendChild(span);
            }
          }
        }
        var list = d.records || [];
        if (!list.length) {
          renderTableMessage(tbody, 7, TABLE_TEXT.EMPTY_RETENTION);
          return;
        }
        tbody.innerHTML = list.map(function(row) {
          var scriptOk = row.scriptSuccess === true || row.scriptSuccess === 1;
          var backupOk = row.backupSuccess === true || row.backupSuccess === 1;
          return '<tr>' +
            '<td>' + (row.taskId || '-') + '</td>' +
            '<td>' + (row.phoneServerIp || '-') + ' / ' + (row.phoneId || '-') + '</td>' +
            '<td>' + (row.accountRegisterId != null ? row.accountRegisterId : '-') + '</td>' +
            '<td>' + (row.gaid || '-') + '</td>' +
            '<td><span class="status ' + (scriptOk ? 'success' : 'failed') + '">' + (scriptOk ? '成功' : '失败') + '</span></td>' +
            '<td><span class="status ' + (backupOk ? 'success' : 'failed') + '">' + (backupOk ? '成功' : '失败') + '</span></td>' +
            '<td class="time">' + (row.createdAt || '-') + '</td>' +
          '</tr>';
        }).join('');
      })
      .catch(function() {
        renderTableMessage(tbody, 7, TABLE_TEXT.LOAD_FAILED, 'var(--error)');
        updateRetPager(0);
      });
  }

  if (dateInput) {
    var todayStr = toYmd(new Date());
    var fpRet = flatpickr(dateInput, {
      locale: 'zh',
      dateFormat: 'Y-m-d',
      defaultDate: todayStr,
      maxDate: todayStr,
      allowInput: false,
      onChange: function(sel, dateStr) { if (dateStr) loadRetention(dateStr); }
    });
    var btnToday = document.getElementById('retention-btn-today');
    if (btnToday) btnToday.addEventListener('click', function() {
      var t = toYmd(new Date());
      fpRet.setDate(t, true);
      retPage = 1;
      loadRetention(t);
    });
    if (retPageSizeSel) {
      retPageSizeSel.addEventListener('change', function() {
        var v = parseInt(this.value, 10);
        if (!v || v < 1) v = 50;
        retSize = v;
      });
    }
    if (retQueryBtn) {
      retQueryBtn.addEventListener('click', function() {
        var v = retPageSizeSel ? parseInt(retPageSizeSel.value, 10) : retSize;
        if (!v || v < 1) v = 50;
        retSize = v;
        retPage = 1;
        var d = dateInput.value || todayStr;
        loadRetention(d);
      });
    }
    if (retPrevBtn) {
      retPrevBtn.addEventListener('click', function() {
        if (retPage <= 1) return;
        retPage--;
        var d = dateInput.value || todayStr;
        loadRetention(d);
      });
    }
    if (retNextBtn) {
      retNextBtn.addEventListener('click', function() {
        if (retPage >= retTotalPages) return;
        retPage++;
        var d = dateInput.value || todayStr;
        loadRetention(d);
      });
    }
    var retentionLoaded = false;
    window.addEventListener('page:changed', function(e) {
      if (!e || !e.detail || e.detail.pageId !== 'retention') return;
      if (retentionLoaded) return;
      retentionLoaded = true;
      loadRetention(todayStr);
    });
  }
})();
