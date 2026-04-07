// 账号管理页：筛选 + 列表 + 分页
(function() {
  var pageEl = document.getElementById('page-account-manage');
  var tbody = document.getElementById('am-table-body');
  var pageInfo = document.getElementById('am-page-info');
  var prevBtn = document.getElementById('am-prev');
  var nextBtn = document.getElementById('am-next');
  var pageSizeSel = document.getElementById('am-page-size');
  var totalCountEl = document.getElementById('am-total-count');
  var queryBtn = document.getElementById('am-query-btn');
  var resetBtn = document.getElementById('am-reset-btn');
  var exportBtn = document.getElementById('am-export-btn');
  var importFile = document.getElementById('am-import-file');
  var importResult = document.getElementById('am-import-result');
  var startDateEl = document.getElementById('am-filter-start-date');
  var endDateEl = document.getElementById('am-filter-end-date');
  var statusEl = document.getElementById('am-filter-status');
  var accountTypeEl = document.getElementById('am-filter-account-type');
  var loginMethodEl = document.getElementById('am-filter-login-method');
  var registerMethodEl = document.getElementById('am-filter-register-method');
  var emailEl = document.getElementById('am-filter-email');
  var countryEl = document.getElementById('am-filter-country');
  var regionEl = document.getElementById('am-filter-region');
  var noteEl = document.getElementById('am-filter-note');

  if (!tbody) return;

  var page = 1;
  var size = pageSizeSel ? parseInt(pageSizeSel.value, 10) || 10 : 10;
  var totalPages = 1;
  var loaded = false;
  var forceMockPage = /(?:\?|&)mock=1(?:&|$)/.test(location.search || '');
  var shared = window.ConsoleSharedUi;
  var format = shared && shared.format;
  var network = shared && shared.network;
  var table = shared && shared.table;
  var feedback = shared && shared.feedback;
  var toYmd = format && format.toYmd;
  var fmtTime = format && format.fmtTime;
  var esc = format && format.esc;
  var fetchJson = network && network.fetchJson;
  var fetchJsonSafe = network && network.fetchJsonSafe;
  var buildErrorMessage = network && network.buildErrorMessage;
  var renderTableMessage = table && table.renderTableMessage;
  var updatePagerState = table && table.updatePagerState;
  var TABLE_TEXT = table && table.TABLE_TEXT;
  var withButtonLoading = feedback && feedback.withButtonLoading;
  var resetFileLabelWithInput = feedback && feedback.resetFileLabelWithInput;
  var notify = feedback && feedback.notify;
  var showInlineNotice = feedback && feedback.showInlineNotice;
  if (!toYmd || !fmtTime || !esc || !fetchJson || !fetchJsonSafe || !renderTableMessage || !buildErrorMessage || !updatePagerState || !withButtonLoading || !resetFileLabelWithInput || !notify || !showInlineNotice || !TABLE_TEXT) return;

  function isUnsupportedFilterSelected() {
    var statusV = statusEl ? statusEl.value : 'ALL';
    var accountTypeV = accountTypeEl ? accountTypeEl.value : 'ALL';
    var loginMethodV = loginMethodEl ? loginMethodEl.value : '2fa';
    var registerMethodV = registerMethodEl ? registerMethodEl.value : '云手机';
    var supportStatus = (statusV === 'ALL' || statusV === '封号' || statusV === '已售' || statusV === '可售');
    var supportAccountType = (accountTypeV === 'ALL' || accountTypeV === '满月白' || accountTypeV === '小白号');
    var supportLoginMethod = (loginMethodV === '2fa');
    var supportRegisterMethod = (registerMethodV === '云手机');
    return !(supportStatus && supportAccountType && supportLoginMethod && supportRegisterMethod);
  }

  function updatePager(total, totalAccurate) {
    var pager = updatePagerState({
      page: page,
      size: size,
      total: total,
      pageInfoEl: pageInfo,
      prevBtn: prevBtn,
      nextBtn: nextBtn,
      totalCountEl: totalCountEl,
      totalAccurate: totalAccurate
    });
    page = pager.page;
    totalPages = pager.totalPages;
  }

  function renderRows(list, total, totalAccurate) {
    updatePager(total, totalAccurate);
    if (!list || !list.length) {
      renderTableMessage(tbody, 13, TABLE_TEXT.EMPTY_ACCOUNT);
      return;
    }
    tbody.innerHTML = list.map(function(row) {
      var status = row.status || '-';
      var statusCls = 'am-status-unknown';
      if (status === '可售') statusCls = 'am-status-saleable';
      else if (status === '已售') statusCls = 'am-status-sold';
      else if (status === '封号') statusCls = 'am-status-blocked';
      return '<tr>' +
        '<td>' + esc(fmtTime(row.createdAt || row.registerDate)) + '</td>' +
        '<td><span class="am-status-chip ' + statusCls + '">' + esc(status) + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.username || '-') + '">' + esc(row.username || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.password || '-') + '">' + esc(row.password || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.email || '-') + '">' + esc(row.email || '-') + '</span></td>' +
        '<td>' + esc(row.loginMethod || '2fa') + '</td>' +
        '<td>' + esc(row.accountType || '-') + '</td>' +
        '<td><span class="cell-truncate" title="' + esc(row.note || '-') + '">' + esc(row.note || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.ip || '-') + '">' + esc(row.ip || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.state || '-') + '">' + esc(row.state || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.city || '-') + '">' + esc(row.city || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.model || '-') + '">' + esc(row.model || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.androidVersion || '-') + '">' + esc(row.androidVersion || '-') + '</span></td>' +
        '</tr>';
    }).join('');
  }

  function buildUrl() {
    var params = [];
    params.push('page=' + encodeURIComponent(page));
    params.push('size=' + encodeURIComponent(size));
    if (startDateEl && startDateEl.value) params.push('startDate=' + encodeURIComponent(startDateEl.value));
    if (endDateEl && endDateEl.value) params.push('endDate=' + encodeURIComponent(endDateEl.value));
    if (accountTypeEl && accountTypeEl.value && accountTypeEl.value !== 'ALL') params.push('accountType=' + encodeURIComponent(accountTypeEl.value));
    if (emailEl && emailEl.value.trim()) params.push('email=' + encodeURIComponent(emailEl.value.trim()));
    if (countryEl && countryEl.value.trim()) params.push('country=' + encodeURIComponent(countryEl.value.trim().toUpperCase()));
    if (regionEl && regionEl.value.trim()) params.push('region=' + encodeURIComponent(regionEl.value.trim()));
    if (noteEl && noteEl.value.trim()) params.push('note=' + encodeURIComponent(noteEl.value.trim()));
    if (statusEl && statusEl.value && statusEl.value !== 'ALL') params.push('status=' + encodeURIComponent(statusEl.value));
    return '/api/tt-register/account/list?' + params.join('&');
  }

  function buildMockAccountList() {
    var statuses = ['可售','可售','可售','已售','封号'];
    var types = ['小白号','小白号','满月白'];
    var countries = ['US','US','US','BR','JP'];
    var regions = ['North Carolina','California','Texas','São Paulo','Tokyo'];
    var emails = ['outlook.com','gmail.com','hotmail.com','yahoo.com'];
    var list = [];
    var now = new Date();
    for (var i = 0; i < 32; i++) {
      var d = new Date(now.getTime() - i * 86400000 * (1 + (i % 3)));
      var ymd = toYmd(d) + ' ' + String(8 + (i % 12)).padStart(2,'0') + ':' + String(i % 60).padStart(2,'0') + ':00';
      var s = statuses[i % statuses.length];
      var t = (s === '可售' || s === '已售') ? types[i % types.length] : '-';
      var co = countries[i % countries.length];
      var rg = regions[i % regions.length];
      var em = 'user' + (1000 + i) + '@' + emails[i % emails.length];
      var pw = 'Tt@' + String(100000 + i * 7).slice(0,6) + '!';
      var un = 'tt_' + String(10000 + i * 13);
      list.push({
        id: 1000 + i, createdAt: ymd, status: s, username: un, password: pw, email: em, loginMethod: '2fa',
        accountType: t, note: i % 4 === 0 ? '优质号源' : '', ip: '103.' + (10 + i) + '.22.' + (i + 1),
        state: rg, city: (rg.split(' ')[1] || rg), model: 'SC-51A', androidVersion: '13', country: co
      });
    }
    return list;
  }

  function loadAccountManageList() {
    if (isUnsupportedFilterSelected()) { renderRows([], 0, true); return; }
    if (forceMockPage) {
      var all = buildMockAccountList();
      var startV = startDateEl ? startDateEl.value : '';
      var endV = endDateEl ? endDateEl.value : '';
      var emailV = emailEl ? emailEl.value.trim().toLowerCase() : '';
      var statusV = statusEl ? statusEl.value : 'ALL';
      var typeV = accountTypeEl ? accountTypeEl.value : 'ALL';
      var countryV = countryEl ? countryEl.value.trim().toUpperCase() : '';
      var regionV = regionEl ? regionEl.value.trim().toLowerCase() : '';
      var noteV = noteEl ? noteEl.value.trim().toLowerCase() : '';
      var filtered = all.filter(function(r) {
        if (startV && r.createdAt < startV) return false;
        if (endV && r.createdAt.slice(0,10) > endV) return false;
        if (statusV && statusV !== 'ALL' && r.status !== statusV) return false;
        if (typeV && typeV !== 'ALL' && r.accountType !== typeV) return false;
        if (emailV && r.email.toLowerCase().indexOf(emailV) < 0) return false;
        if (countryV && r.country !== countryV) return false;
        if (regionV && r.state.toLowerCase().indexOf(regionV) < 0) return false;
        if (noteV && r.note.toLowerCase().indexOf(noteV) < 0) return false;
        return true;
      });
      var total = filtered.length;
      var from = (page - 1) * size;
      renderRows(filtered.slice(from, from + size), total, true);
      return;
    }
    var url = buildUrl();
    renderTableMessage(tbody, 13, TABLE_TEXT.LOADING);
    fetchJsonSafe(url)
      .then(function(res) {
        if (!res || !res.success || !res.data) {
          renderTableMessage(tbody, 13, esc(buildErrorMessage('加载失败', res)), 'var(--error)');
          updatePager(0, true);
          return;
        }
        var data = res.data || {};
        if (data.size && [10, 50, 100].indexOf(Number(data.size)) >= 0) {
          size = Number(data.size);
          if (pageSizeSel) pageSizeSel.value = String(size);
        }
        if (data.page) page = Number(data.page) || page;
        renderRows(data.list || [], data.total || 0, data.totalAccurate !== false);
      })
      .catch(function() {
        renderTableMessage(tbody, 13, TABLE_TEXT.LOAD_FAILED, 'var(--error)');
        updatePager(0, true);
      });
  }

  if (startDateEl) flatpickr(startDateEl, { locale: 'zh', dateFormat: 'Y-m-d', allowInput: false });
  if (endDateEl) flatpickr(endDateEl, { locale: 'zh', dateFormat: 'Y-m-d', allowInput: false });

  function buildExportUrl() {
    var params = [];
    if (startDateEl && startDateEl.value) params.push('startDate=' + encodeURIComponent(startDateEl.value));
    if (endDateEl && endDateEl.value) params.push('endDate=' + encodeURIComponent(endDateEl.value));
    if (accountTypeEl && accountTypeEl.value && accountTypeEl.value !== 'ALL') params.push('accountType=' + encodeURIComponent(accountTypeEl.value));
    if (emailEl && emailEl.value.trim()) params.push('email=' + encodeURIComponent(emailEl.value.trim()));
    if (countryEl && countryEl.value.trim()) params.push('country=' + encodeURIComponent(countryEl.value.trim().toUpperCase()));
    if (regionEl && regionEl.value.trim()) params.push('region=' + encodeURIComponent(regionEl.value.trim()));
    if (noteEl && noteEl.value.trim()) params.push('note=' + encodeURIComponent(noteEl.value.trim()));
    if (statusEl && statusEl.value && statusEl.value !== 'ALL') params.push('status=' + encodeURIComponent(statusEl.value));
    return '/api/tt-register/account/export?' + params.join('&');
  }

  function downloadCsv(rows) {
    var cols = ['createdAt','status','username','password','email','loginMethod','accountType','note','detail','country'];
    var headers = ['注册日期','状态','账号','密码','邮箱','登录方式','账号类别','备注','详情','国家'];
    var lines = [headers.join(',')];
    rows.forEach(function(r) {
      var row = cols.map(function(c) {
        var v = String(r[c] == null ? '' : r[c]);
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
          v = '"' + v.replace(/"/g, '""') + '"';
        }
        return v;
      });
      lines.push(row.join(','));
    });
    var bom = '\uFEFF';
    var blob = new Blob([bom + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = '账号列表_' + toYmd(new Date()) + '.csv';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  if (exportBtn) {
    exportBtn.addEventListener('click', function() {
      if (isUnsupportedFilterSelected()) { downloadCsv([]); return; }
      if (forceMockPage) { downloadCsv(buildMockAccountList()); return; }
      withButtonLoading(exportBtn, '导出中…', function() {
        return fetchJson(buildExportUrl()).then(function(res) {
          if (!res.success || !res.data) { notify(buildErrorMessage('导出失败', res)); return; }
          downloadCsv(res.data);
        });
      }, '⬇ 导出CSV').catch(function() {
        notify('导出请求失败，请稍后重试');
      });
    });
  }

  function showImportResult(res) {
    if (!importResult) return;
    var color = res.success ? '#15803d' : '#be123c';
    var bg = res.success ? '#f0fdf4' : '#fff1f2';
    var border = res.success ? '#bbf7d0' : '#fecdd3';
    var html = '<b style="color:' + color + '">' + esc(res.message || (res.success ? '导入完成' : '导入失败')) + '</b>';
    if (res.success) {
      html += '&emsp;新增 <b>' + (res.insertCount || 0) + '</b> 条&emsp;更新 <b>' + (res.updateCount || 0) + '</b> 条&emsp;跳过 <b>' + (res.skipCount || 0) + '</b> 条';
      if (res.errors && res.errors.length) {
        html += '<br><span style="color:#be123c;font-size:0.73rem;">' + res.errors.slice(0, 3).map(esc).join('；') + (res.errors.length > 3 ? '…' : '') + '</span>';
      }
    }
    showInlineNotice(importResult, {
      html: html,
      background: bg,
      borderColor: border,
      hideAfterMs: 8000
    });
  }

  function bindImportInput() {
    importFile = document.getElementById('am-import-file');
    if (!importFile) return;
    importFile.onchange = function() {
      var f = this.files && this.files[0];
      if (!f) return;
      if (forceMockPage) {
        showImportResult({ success: true, message: 'Mock模式：导入功能不执行', insertCount: 0, updateCount: 0, skipCount: 0 });
        this.value = '';
        return;
      }
      var label = document.getElementById('am-import-label');
      if (label) label.textContent = '导入中…';
      var form = new FormData();
      form.append('file', f);
      fetchJson('/api/tt-register/account/import', { method: 'POST', body: form })
        .then(function(res) {
          resetFileLabelWithInput(label, '⬆ 导入CSV', 'am-import-file', '.csv');
          bindImportInput();
          showImportResult(res);
          if (res.success && (res.insertCount > 0 || res.updateCount > 0)) { page = 1; loadAccountManageList(); }
        })
        .catch(function() {
          resetFileLabelWithInput(label, '⬆ 导入CSV', 'am-import-file', '.csv');
          bindImportInput();
          showImportResult({ success: false, message: '导入请求失败，请稍后重试' });
        });
    };
  }
  bindImportInput();

  if (pageSizeSel) pageSizeSel.addEventListener('change', function() { var v = parseInt(this.value, 10); if (!v || [10,50,100].indexOf(v)<0) v = 10; size = v; page = 1; loadAccountManageList(); });
  if (queryBtn) queryBtn.addEventListener('click', function() { page = 1; loadAccountManageList(); });
  if (resetBtn) resetBtn.addEventListener('click', function() {
    if (startDateEl) startDateEl.value = '';
    if (endDateEl) endDateEl.value = '';
    if (emailEl) emailEl.value = '';
    if (countryEl) countryEl.value = '';
    if (regionEl) regionEl.value = '';
    if (loginMethodEl) loginMethodEl.value = '2fa';
    if (registerMethodEl) registerMethodEl.value = '云手机';
    if (noteEl) noteEl.value = '';
    if (statusEl) statusEl.value = 'ALL';
    if (accountTypeEl) accountTypeEl.value = 'ALL';
    if (pageSizeSel) pageSizeSel.value = '10';
    size = 10; page = 1; loadAccountManageList();
  });
  if (prevBtn) prevBtn.addEventListener('click', function() { if (page > 1) { page--; loadAccountManageList(); } });
  if (nextBtn) nextBtn.addEventListener('click', function() { if (page < totalPages) { page++; loadAccountManageList(); } });

  window.addEventListener('page:changed', function(e) {
    if (!e || !e.detail || e.detail.pageId !== 'account-manage') return;
    if (loaded) return;
    loaded = true;
    var today = toYmd(new Date());
    if (startDateEl && !startDateEl.value) startDateEl.value = today;
    if (endDateEl && !endDateEl.value) endDateEl.value = today;
    loadAccountManageList();
  });
})();

// 开窗管理页：筛选 + 列表 + 分页
(function() {
  var tbody = document.getElementById('wm-table-body');
  var pageInfo = document.getElementById('wm-page-info');
  var prevBtn = document.getElementById('wm-prev');
  var nextBtn = document.getElementById('wm-next');
  var pageSizeSel = document.getElementById('wm-page-size');
  var totalCountEl = document.getElementById('wm-total-count');
  var queryBtn = document.getElementById('wm-query-btn');
  var resetBtn = document.getElementById('wm-reset-btn');
  var fanStartEl = document.getElementById('wm-filter-fan-start');
  var fanEndEl = document.getElementById('wm-filter-fan-end');
  var nurtureStartEl = document.getElementById('wm-filter-nurture-start');
  var nurtureEndEl = document.getElementById('wm-filter-nurture-end');
  var strategyEl = document.getElementById('wm-filter-strategy');
  var shopStatusEl = document.getElementById('wm-filter-shop-status');
  var deviceEl = document.getElementById('wm-filter-device');
  var countryEl = document.getElementById('wm-filter-country');
  var accountEl = document.getElementById('wm-filter-account');
  var noteEl = document.getElementById('wm-filter-note');
  if (!tbody) return;

  var page = 1;
  var size = pageSizeSel ? parseInt(pageSizeSel.value, 10) || 10 : 10;
  var totalPages = 1;
  var loaded = false;
  var shared = window.ConsoleSharedUi;
  var format = shared && shared.format;
  var network = shared && shared.network;
  var table = shared && shared.table;
  var esc = format && format.esc;
  var fmtTime = format && format.fmtTime;
  var fetchJsonSafe = network && network.fetchJsonSafe;
  var buildErrorMessage = network && network.buildErrorMessage;
  var renderTableMessage = table && table.renderTableMessage;
  var updatePagerState = table && table.updatePagerState;
  var TABLE_TEXT = table && table.TABLE_TEXT;
  if (!esc || !fmtTime || !fetchJsonSafe || !renderTableMessage || !buildErrorMessage || !updatePagerState || !TABLE_TEXT) return;
  function updatePager(total, totalAccurate) {
    var pager = updatePagerState({
      page: page,
      size: size,
      total: total,
      pageInfoEl: pageInfo,
      prevBtn: prevBtn,
      nextBtn: nextBtn,
      totalCountEl: totalCountEl,
      totalAccurate: totalAccurate
    });
    page = pager.page;
    totalPages = pager.totalPages;
  }
  function renderRows(list, total, totalAccurate) {
    updatePager(total, totalAccurate);
    if (!list || !list.length) { renderTableMessage(tbody, 8, TABLE_TEXT.EMPTY_WINDOW); return; }
    tbody.innerHTML = list.map(function(row) {
      return '<tr>' +
        '<td>' + esc(row.username || '-') + '</td>' +
        '<td>' + esc(fmtTime(row.fanDate)) + '</td>' +
        '<td>' + esc(fmtTime(row.nurtureDate)) + '</td>' +
        '<td>' + esc(row.nurtureStrategy || '-') + '</td>' +
        '<td>' + esc(row.shopStatus || '-') + '</td>' +
        '<td><span class="cell-truncate" title="' + esc(row.registerIp || '-') + '">' + esc(row.registerIp || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.registerEnv || '-') + '">' + esc(row.registerEnv || '-') + '</span></td>' +
        '<td><span class="cell-truncate" title="' + esc(row.note || '-') + '">' + esc(row.note || '-') + '</span></td>' +
        '</tr>';
    }).join('');
  }
  function buildUrl() {
    var params = [];
    params.push('page=' + encodeURIComponent(page));
    params.push('size=' + encodeURIComponent(size));
    if (fanStartEl && fanStartEl.value) params.push('fanStartDate=' + encodeURIComponent(fanStartEl.value));
    if (fanEndEl && fanEndEl.value) params.push('fanEndDate=' + encodeURIComponent(fanEndEl.value));
    if (nurtureStartEl && nurtureStartEl.value) params.push('nurtureStartDate=' + encodeURIComponent(nurtureStartEl.value));
    if (nurtureEndEl && nurtureEndEl.value) params.push('nurtureEndDate=' + encodeURIComponent(nurtureEndEl.value));
    if (strategyEl && strategyEl.value && strategyEl.value !== 'ALL') params.push('nurtureStrategy=' + encodeURIComponent(strategyEl.value));
    if (shopStatusEl && shopStatusEl.value && shopStatusEl.value !== 'ALL') params.push('shopStatus=' + encodeURIComponent(shopStatusEl.value));
    if (deviceEl && deviceEl.value && deviceEl.value !== 'ALL') params.push('nurtureDevice=' + encodeURIComponent(deviceEl.value));
    if (countryEl && countryEl.value && countryEl.value !== 'ALL') params.push('country=' + encodeURIComponent(countryEl.value));
    if (accountEl && accountEl.value.trim()) params.push('account=' + encodeURIComponent(accountEl.value.trim()));
    if (noteEl && noteEl.value.trim()) params.push('note=' + encodeURIComponent(noteEl.value.trim()));
    return '/api/tt-register/window/list?' + params.join('&');
  }
  function loadWindowList() {
    renderTableMessage(tbody, 8, TABLE_TEXT.LOADING);
    fetchJsonSafe(buildUrl())
      .then(function(res) {
        if (!res || !res.success || !res.data) {
          renderTableMessage(tbody, 8, esc(buildErrorMessage('加载失败', res)), 'var(--error)');
          updatePager(0, true);
          return;
        }
        var data = res.data || {};
        if (data.size && [10, 50, 100].indexOf(Number(data.size)) >= 0) {
          size = Number(data.size);
          if (pageSizeSel) pageSizeSel.value = String(size);
        }
        if (data.page) page = Number(data.page) || page;
        renderRows(data.list || [], data.total || 0, data.totalAccurate !== false);
      })
      .catch(function() {
        renderTableMessage(tbody, 8, TABLE_TEXT.LOAD_FAILED, 'var(--error)');
        updatePager(0, true);
      });
  }

  if (fanStartEl) flatpickr(fanStartEl, { locale: 'zh', dateFormat: 'Y-m-d', allowInput: false });
  if (fanEndEl) flatpickr(fanEndEl, { locale: 'zh', dateFormat: 'Y-m-d', allowInput: false });
  if (nurtureStartEl) flatpickr(nurtureStartEl, { locale: 'zh', dateFormat: 'Y-m-d', allowInput: false });
  if (nurtureEndEl) flatpickr(nurtureEndEl, { locale: 'zh', dateFormat: 'Y-m-d', allowInput: false });
  if (pageSizeSel) pageSizeSel.addEventListener('change', function() { var v = parseInt(this.value, 10); if (!v || [10,50,100].indexOf(v)<0) v = 10; size = v; page = 1; loadWindowList(); });
  if (queryBtn) queryBtn.addEventListener('click', function() { page = 1; loadWindowList(); });
  if (resetBtn) resetBtn.addEventListener('click', function() {
    if (fanStartEl) fanStartEl.value = '';
    if (fanEndEl) fanEndEl.value = '';
    if (nurtureStartEl) nurtureStartEl.value = '';
    if (nurtureEndEl) nurtureEndEl.value = '';
    if (strategyEl) strategyEl.value = 'ALL';
    if (shopStatusEl) shopStatusEl.value = 'ALL';
    if (deviceEl) deviceEl.value = 'ALL';
    if (countryEl) countryEl.value = 'ALL';
    if (accountEl) accountEl.value = '';
    if (noteEl) noteEl.value = '';
    if (pageSizeSel) pageSizeSel.value = '10';
    size = 10; page = 1; loadWindowList();
  });
  if (prevBtn) prevBtn.addEventListener('click', function() { if (page > 1) { page--; loadWindowList(); } });
  if (nextBtn) nextBtn.addEventListener('click', function() { if (page < totalPages) { page++; loadWindowList(); } });

  window.addEventListener('page:changed', function(e) {
    if (!e || !e.detail || e.detail.pageId !== 'window-manage') return;
    if (loaded) return;
    loaded = true;
    loadWindowList();
  });
})();
