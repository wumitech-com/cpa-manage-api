// 每日注册统计（总览页）
(function() {
  var trendBars = document.getElementById('trend-bars');
  var trafficBars = document.getElementById('traffic-trend-bars');
  var dateInput = document.getElementById('overview-date');
  var forceMockPage = /(?:\?|&)mock=1(?:&|$)/.test(location.search || '');
  var MOCK_TWOFA_DETAIL = {
    androidVersionDist: [{ value: '14', count: 926, percent: 50.14 }, { value: '13', count: 911, percent: 49.32 }, { value: '30', count: 10, percent: 0.54 }],
    behaviorDist: [{ value: 'normal', count: 1023, percent: 55.39 }, { value: 'skip', count: 824, percent: 44.61 }],
    tiktokVersionDist: [{ value: '43.9.3', count: 1120, percent: 60.64 }, { value: '43.1.15', count: 225, percent: 12.18 }, { value: '43.6.3', count: 197, percent: 10.67 }, { value: '43.1.4', count: 175, percent: 9.47 }, { value: '43.1.1', count: 130, percent: 7.04 }],
    countryDist: [{ value: 'US', count: 1807, percent: 97.83 }, { value: 'BR', count: 40, percent: 2.17 }],
    phoneServerIpDist: [{ value: '10.7.117.117', count: 337, percent: 18.25 }, { value: '10.7.169.151', count: 326, percent: 17.65 }, { value: '10.7.59.169', count: 321, percent: 17.38 }, { value: '10.7.136.129', count: 274, percent: 14.83 }, { value: '10.7.8.64', count: 268, percent: 14.51 }, { value: '10.7.184.117', count: 238, percent: 12.89 }, { value: '10.13.151.168', count: 73, percent: 3.95 }, { value: '10.13.161.137', count: 10, percent: 0.54 }],
    trafficTotal: 44031584401,
    trafficAvgPerSuccess: 23839515.11,
    trafficTotalAll: 52100800128,
    serverHourly2fa: [
      { serverIp: '10.7.117.117', total: 337, hourly: [{ hour: '08', count: 22 }, { hour: '09', count: 35 }, { hour: '10', count: 41 }, { hour: '11', count: 29 }, { hour: '12', count: 24 }, { hour: '13', count: 31 }] },
      { serverIp: '10.7.169.151', total: 326, hourly: [{ hour: '08', count: 18 }, { hour: '09', count: 32 }, { hour: '10', count: 37 }, { hour: '11', count: 30 }, { hour: '12', count: 28 }, { hour: '13', count: 26 }] }
    ]
  };
  function buildMockOverviewData(dateStr) {
    var d = dateStr || toYmd(new Date());
    return {
      todayRegister: 2579,
      todayRegisterSuccess: 2016,
      today2faSuccess: 1847,
      todayNeedRetention: 935,
      todayRegisterSuccessRate: 78.17,
      today2faSetupSuccessRate: 91.62,
      dailyTrend: [
        { date: d, label: '今天', register: 2579, twofa: 1847, retention: 935, trafficTotal: 44031584401, trafficAvg: 23839515.11 },
        { date: d, label: '昨天', register: 2488, twofa: 1765, retention: 902, trafficTotal: 41800311220, trafficAvg: 23682924.49 },
        { date: d, label: '3/16', register: 2396, twofa: 1698, retention: 876, trafficTotal: 40129004110, trafficAvg: 23633099.00 },
        { date: d, label: '3/15', register: 2318, twofa: 1630, retention: 841, trafficTotal: 38695410400, trafficAvg: 23739515.58 },
        { date: d, label: '3/14', register: 2280, twofa: 1588, retention: 803, trafficTotal: 37261033000, trafficAvg: 23464126.83 },
        { date: d, label: '3/13', register: 2212, twofa: 1522, retention: 775, trafficTotal: 35810021000, trafficAvg: 23528266.75 },
        { date: d, label: '3/12', register: 2169, twofa: 1480, retention: 744, trafficTotal: 34370019000, trafficAvg: 23222985.81 }
      ],
      twofaDetail: Object.assign({}, MOCK_TWOFA_DETAIL)
    };
  }
  if (!trendBars) return;
  var shared = window.ConsoleSharedUi;
  var format = shared && shared.format;
  var table = shared && shared.table;
  var network = shared && shared.network;
  var toYmd = format && format.toYmd;
  var fmtGb = format && format.fmtGb;
  function fmtNum(n) { return String(n == null ? 0 : n); }
  var renderDist = table && table.renderDist;
  var renderPieFromDist = table && table.renderPieFromDist;
  var bindTrendTooltip = table && table.bindTrendTooltip;
  var fetchJsonSafe = network && network.fetchJsonSafe;
  if (!toYmd || !fmtGb || !renderDist || !renderPieFromDist || !bindTrendTooltip || !fetchJsonSafe) return;
  function renderRatePie(percent, pieId, labelId, color) {
    var pieEl = document.getElementById(pieId), labelEl = document.getElementById(labelId);
    if (!pieEl || !labelEl) return;
    var p = Number(percent || 0); if (p < 0) p = 0; if (p > 100) p = 100;
    pieEl.style.background = 'conic-gradient(' + color + ' 0 ' + p + '%, #e5e7eb ' + p + '% 100%)';
    labelEl.innerHTML = '成功 ' + p + '%<br>未成功 ' + (Math.round((100 - p) * 100) / 100) + '%';
  }
  function renderServerHourly(list) {
    var wrap = document.getElementById('twofa-server-hourly-list');
    if (!wrap) return;
    list = list || [];
    if (!list.length) { wrap.innerHTML = '<div>暂无数据</div>'; return; }
    wrap.innerHTML = list.map(function(server) {
      var name = server.serverIp || '未知', total = server.total || 0, hourly = server.hourly || [];
      var points = hourly.filter(function(h) { return (h.count || 0) > 0; }).map(function(h) { return h.hour + ': ' + h.count; });
      var text = points.length ? points.join(' | ') : '全天 0';
      return '<div style="padding:8px 10px;border:1px solid rgba(251,113,133,0.18);border-radius:10px;background:rgba(255,255,255,0.78);"><div style="font-weight:600;color:var(--text);margin-bottom:4px;">' + name + ' <span style="color:var(--text-muted);font-weight:500;">(总计 ' + total + ')</span></div><div style="white-space:nowrap;overflow:auto;padding-bottom:2px;">' + text + '</div></div>';
    }).join('');
  }
  function render(data) {
    document.getElementById('stat-today-register').textContent = fmtNum(data.todayRegister);
    document.getElementById('stat-today-register-success').textContent = fmtNum(data.todayRegisterSuccess);
    document.getElementById('stat-today-2fa').textContent = fmtNum(data.today2faSuccess);
    document.getElementById('stat-today-retention').textContent = fmtNum(data.todayNeedRetention);
    document.getElementById('stat-register-success-rate').textContent = (data.todayRegisterSuccessRate != null ? data.todayRegisterSuccessRate : 0) + '%';
    document.getElementById('stat-twofa-setup-rate').textContent = (data.today2faSetupSuccessRate != null ? data.today2faSetupSuccessRate : 0) + '%';
    renderRatePie(data.todayRegisterSuccessRate != null ? data.todayRegisterSuccessRate : 0, 'register-success-rate-pie', 'register-success-rate-pie-label', '#fb7185');
    renderRatePie(data.today2faSetupSuccessRate != null ? data.today2faSetupSuccessRate : 0, 'twofa-setup-rate-pie', 'twofa-setup-rate-pie-label', '#6366f1');
    var twofaCard = document.querySelector('#overview-stats .stat-card.green');
    if (twofaCard) {
      if ((data.today2faSuccess || 0) > 0) twofaCard.classList.add('heartbeat');
      else twofaCard.classList.remove('heartbeat');
    }
    var detail = data.twofaDetail || null;
    var forceMock = /(?:\?|&)mock=1(?:&|$)/.test(location.search || '');
    var hasRealDist = detail && ((detail.androidVersionDist && detail.androidVersionDist.length) || (detail.behaviorDist && detail.behaviorDist.length) || (detail.tiktokVersionDist && detail.tiktokVersionDist.length) || (detail.countryDist && detail.countryDist.length) || (detail.phoneServerIpDist && detail.phoneServerIpDist.length));
    if (forceMock || !hasRealDist) detail = Object.assign({}, MOCK_TWOFA_DETAIL);
    if (detail) {
      renderDist(detail.androidVersionDist || [], 'twofa-android-list');
      renderDist(detail.behaviorDist || [], 'twofa-behavior-list');
      renderDist(detail.tiktokVersionDist || [], 'twofa-tiktok-list');
      renderDist(detail.countryDist || [], 'twofa-country-list');
      renderDist(detail.phoneServerIpDist || [], 'twofa-server-ip-list');
      renderPieFromDist(detail.androidVersionDist || [], 'twofa-android-pie', 'twofa-android-legend');
      renderPieFromDist(detail.behaviorDist || [], 'twofa-behavior-pie', 'twofa-behavior-legend');
      renderPieFromDist(detail.tiktokVersionDist || [], 'twofa-tiktok-pie', 'twofa-tiktok-legend');
      renderPieFromDist(detail.countryDist || [], 'twofa-country-pie', 'twofa-country-legend');
      renderPieFromDist(detail.phoneServerIpDist || [], 'twofa-server-pie', 'twofa-server-legend');
      renderServerHourly(detail.serverHourly2fa || []);
      var trafficTotalEl = document.getElementById('traffic-total');
      var trafficAvgEl = document.getElementById('traffic-avg');
      var trafficTotalAllEl = document.getElementById('traffic-total-all');
      if (trafficTotalEl) trafficTotalEl.textContent = detail.trafficTotal != null ? fmtGb(detail.trafficTotal) : '-';
      if (trafficAvgEl) trafficAvgEl.textContent = detail.trafficAvgPerSuccess != null ? fmtGb(detail.trafficAvgPerSuccess) : '-';
      if (trafficTotalAllEl) trafficTotalAllEl.textContent = detail.trafficTotalAll != null ? fmtGb(detail.trafficTotalAll) : '-';
    }
    var trend = data.dailyTrend || [];
    var maxVal = Math.max(1, Math.max.apply(null, trend.map(function(d) { return Math.max(d.register || 0, d.twofa || 0, d.retention || 0); })));
    trendBars.innerHTML = trend.map(function(d, i) {
      var pctReg = maxVal > 0 ? ((d.register || 0) / maxVal * 100) : 0;
      var pct2fa = maxVal > 0 ? ((d.twofa || 0) / maxVal * 100) : 0;
      var pctRet = maxVal > 0 ? ((d.retention || 0) / maxVal * 100) : 0;
      var label = d.label || d.date;
      var title = label ? label.replace(/"/g, '&quot;') : '';
      return '<div class="trend-day' + (i === 6 ? ' today' : '') + '" data-register="' + (d.register || 0) + '" data-twofa="' + (d.twofa || 0) + '" data-retention="' + (d.retention || 0) + '" data-label="' + title + '"><div class="trend-day-bars"><div class="trend-day-bar reg" style="height:' + pctReg + '%"></div><div class="trend-day-bar twofa" style="height:' + pct2fa + '%"></div><div class="trend-day-bar ret" style="height:' + pctRet + '%"></div></div><div class="vals">' + (d.register || 0) + ' / ' + (d.twofa || 0) + ' / ' + (d.retention || 0) + '</div><span class="label">' + (label || '') + '</span></div>';
    }).join('');
    bindTrendTooltip(trendBars, '.trend-day', function(el) {
      var reg = el.getAttribute('data-register') || '0';
      var twofa = el.getAttribute('data-twofa') || '0';
      var ret = el.getAttribute('data-retention') || '0';
      var label = el.getAttribute('data-label') || '';
      return (label ? label + '<br>' : '') + '注册: ' + reg + ' · 2FA: ' + twofa + ' · 留存: ' + ret;
    });
    if (trafficBars) {
      var maxTraffic = Math.max(1, Math.max.apply(null, trend.map(function(d) { return d.trafficTotal || 0; })));
      trafficBars.innerHTML = trend.map(function(d, i) {
        var total = d.trafficTotal || 0, avgT = d.trafficAvg || 0, label = d.label || d.date, title = label ? label.replace(/"/g, '&quot;') : '';
        var pctTotal = maxTraffic > 0 ? (total / maxTraffic * 100) : 0, pctAvg = maxTraffic > 0 ? (avgT / maxTraffic * 100) : 0;
        return '<div class="trend-day' + (i === 6 ? ' today' : '') + '" data-total="' + total + '" data-avg="' + avgT + '" data-label="' + title + '"><div class="trend-day-bars"><div class="trend-day-bar reg" style="height:' + pctTotal + '%"></div><div class="trend-day-bar ret" style="height:' + pctAvg + '%"></div></div><div class="vals">' + fmtGb(total) + ' / ' + fmtGb(avgT) + '</div><span class="label">' + (label || '') + '</span></div>';
      }).join('');
      bindTrendTooltip(trafficBars, '.trend-day', function(el) {
        var label = el.getAttribute('data-label') || '';
        var total = el.getAttribute('data-total') || '0';
        var avgT = el.getAttribute('data-avg') || '0';
        return (label ? label + '<br>' : '') + '总流量: ' + fmtGb(total) + ' · 单个平均: ' + fmtGb(avgT);
      });
    }
  }
  function loadDailyRegister(dateStr) {
    if (forceMockPage) { render(buildMockOverviewData(dateStr)); return; }
    var qs = dateStr ? ('?date=' + encodeURIComponent(dateStr)) : '';
    var model = { todayRegister: 0, todayRegisterSuccess: 0, today2faSuccess: 0, todayNeedRetention: 0, todayRegisterSuccessRate: 0, today2faSetupSuccessRate: 0, dailyTrend: [], twofaDetail: null };
    render(model);
    fetchJsonSafe('/api/statistics/daily-register/overview' + qs).then(function(res) {
      if (res && res.success && res.data) {
        Object.assign(model, res.data);
        render(model);
      }
    });
    fetchJsonSafe('/api/statistics/daily-register/trend' + qs).then(function(res) {
      if (res && res.success && res.data) {
        model.dailyTrend = res.data.dailyTrend || [];
        render(model);
      }
    });
    fetchJsonSafe('/api/statistics/daily-register/detail' + qs).then(function(res) {
      if (res && res.success && res.data) {
        model.twofaDetail = res.data.twofaDetail || null;
        render(model);
      }
    });
  }
  if (dateInput) {
    var todayStr = toYmd(new Date());
    var fpOverview = flatpickr(dateInput, { locale: 'zh', dateFormat: 'Y-m-d', defaultDate: todayStr, maxDate: todayStr, allowInput: false, onChange: function(sel, dateStr) { if (dateStr) loadDailyRegister(dateStr); } });
    var btnToday = document.getElementById('overview-btn-today');
    if (btnToday) btnToday.addEventListener('click', function() {
      var t = toYmd(new Date());
      fpOverview.setDate(t, true);
      loadDailyRegister(t);
    });
  }
  var overviewLoaded = false;
  function ensureOverviewLoaded() {
    if (overviewLoaded) return;
    overviewLoaded = true;
    loadDailyRegister(toYmd(new Date()));
  }
  window.addEventListener('page:changed', function(e) {
    if (!e || !e.detail || e.detail.pageId !== 'overview') return;
    ensureOverviewLoaded();
  });
  var overviewPage = document.getElementById('page-overview');
  if (overviewPage && overviewPage.classList.contains('active')) ensureOverviewLoaded();
})();
