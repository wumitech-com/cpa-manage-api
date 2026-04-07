// 封号率统计 + 最近7天趋势
(function() {
  var dateInput = document.getElementById('block-rate-date');
  var trendBars = document.getElementById('block-rate-trend-bars');
  var forceMockPage = /(?:\?|&)mock=1(?:&|$)/.test(location.search || '');
  var MOCK_BLOCK_RATE_DATA = {
    nextDay: { baseDate: '2026-03-18', total: 1847, blocked: 213, blockRate: 11.53, label: '次日封号率' },
    threeDay: { baseDate: '2026-03-16', total: 1698, blocked: 285, blockRate: 16.78, label: '3天封号率' },
    sevenDay: { baseDate: '2026-03-12', total: 1480, blocked: 356, blockRate: 24.05, label: '7天封号率' },
    blockedDetail: {
      androidVersionDist: [{ value: '14', count: 438, percent: 51.59 }, { value: '13', count: 386, percent: 45.46 }, { value: '12', count: 25, percent: 2.95 }],
      behaviorDist: [{ value: 'nickname_skip', count: 382, percent: 44.99 }, { value: 'nickname_normal', count: 301, percent: 35.45 }, { value: 'bio_skip', count: 102, percent: 12.01 }, { value: 'bio_normal', count: 64, percent: 7.55 }],
      tiktokVersionDist: [{ value: '43.9.3', count: 472, percent: 55.59 }, { value: '43.1.15', count: 178, percent: 20.97 }, { value: '43.6.3', count: 112, percent: 13.19 }, { value: '43.1.4', count: 87, percent: 10.25 }],
      countryDist: [{ value: 'US', count: 804, percent: 94.7 }, { value: 'BR', count: 45, percent: 5.3 }],
      phoneServerIpDist: [{ value: '10.7.117.117', count: 164, percent: 19.32 }, { value: '10.7.169.151', count: 153, percent: 18.02 }, { value: '10.7.59.169', count: 149, percent: 17.55 }, { value: '10.7.136.129', count: 122, percent: 14.37 }, { value: '10.7.8.64', count: 114, percent: 13.43 }, { value: '10.7.184.117', count: 103, percent: 12.13 }, { value: '10.13.151.168', count: 32, percent: 3.77 }, { value: '10.13.161.137', count: 12, percent: 1.41 }]
    },
    blockedDetailByPeriod: {
      nextDay: {
        androidVersionDist: [{ value: '14', count: 91, percent: 42.72 }, { value: '13', count: 108, percent: 50.7 }, { value: '12', count: 14, percent: 6.57 }],
        behaviorDist: [{ value: 'nickname_skip', count: 95, percent: 44.6 }, { value: 'nickname_normal', count: 76, percent: 35.68 }, { value: 'bio_skip', count: 25, percent: 11.74 }, { value: 'bio_normal', count: 17, percent: 7.98 }],
        tiktokVersionDist: [{ value: '43.9.3', count: 118, percent: 55.4 }, { value: '43.1.15', count: 44, percent: 20.66 }, { value: '43.6.3', count: 28, percent: 13.15 }, { value: '43.1.4', count: 23, percent: 10.8 }],
        countryDist: [{ value: 'US', count: 201, percent: 94.37 }, { value: 'BR', count: 12, percent: 5.63 }],
        phoneServerIpDist: [{ value: '10.7.117.117', count: 43, percent: 20.19 }, { value: '10.7.169.151', count: 39, percent: 18.31 }, { value: '10.7.59.169', count: 37, percent: 17.37 }]
      },
      threeDay: {
        androidVersionDist: [{ value: '14', count: 152, percent: 53.33 }, { value: '13', count: 122, percent: 42.81 }, { value: '12', count: 11, percent: 3.86 }],
        behaviorDist: [{ value: 'nickname_skip', count: 123, percent: 43.16 }, { value: 'nickname_normal', count: 104, percent: 36.49 }, { value: 'bio_skip', count: 36, percent: 12.63 }, { value: 'bio_normal', count: 22, percent: 7.72 }],
        tiktokVersionDist: [{ value: '43.9.3', count: 165, percent: 57.89 }, { value: '43.1.15', count: 60, percent: 21.05 }, { value: '43.6.3', count: 35, percent: 12.28 }, { value: '43.1.4', count: 25, percent: 8.77 }],
        countryDist: [{ value: 'US', count: 269, percent: 94.39 }, { value: 'BR', count: 16, percent: 5.61 }],
        phoneServerIpDist: [{ value: '10.7.117.117', count: 56, percent: 19.65 }, { value: '10.7.169.151', count: 51, percent: 17.89 }, { value: '10.7.59.169', count: 49, percent: 17.19 }]
      },
      sevenDay: {
        androidVersionDist: [{ value: '14', count: 195, percent: 54.78 }, { value: '13', count: 156, percent: 43.82 }, { value: '12', count: 5, percent: 1.4 }],
        behaviorDist: [{ value: 'nickname_skip', count: 164, percent: 46.07 }, { value: 'nickname_normal', count: 121, percent: 33.99 }, { value: 'bio_skip', count: 41, percent: 11.52 }, { value: 'bio_normal', count: 30, percent: 8.43 }],
        tiktokVersionDist: [{ value: '43.9.3', count: 189, percent: 53.09 }, { value: '43.1.15', count: 74, percent: 20.79 }, { value: '43.6.3', count: 49, percent: 13.76 }, { value: '43.1.4', count: 44, percent: 12.36 }],
        countryDist: [{ value: 'US', count: 334, percent: 93.82 }, { value: 'BR', count: 22, percent: 6.18 }],
        phoneServerIpDist: [{ value: '10.7.117.117', count: 65, percent: 18.26 }, { value: '10.7.169.151', count: 63, percent: 17.7 }, { value: '10.7.59.169', count: 63, percent: 17.7 }]
      }
    }
  };
  var MOCK_BLOCK_TREND_DATA = {
    trend: [
      { date: '2026-03-13', label: '03-13', total: 1501, blocked: 301, blockRate: 20.05 },
      { date: '2026-03-14', label: '03-14', total: 1564, blocked: 287, blockRate: 18.35 },
      { date: '2026-03-15', label: '03-15', total: 1622, blocked: 274, blockRate: 16.89 },
      { date: '2026-03-16', label: '03-16', total: 1698, blocked: 285, blockRate: 16.78 },
      { date: '2026-03-17', label: '03-17', total: 1765, blocked: 262, blockRate: 14.84 },
      { date: '2026-03-18', label: '03-18', total: 1847, blocked: 213, blockRate: 11.53 },
      { date: '2026-03-19', label: '03-19', total: 1901, blocked: 198, blockRate: 10.42 }
    ]
  };
  var shared = window.ConsoleSharedUi;
  var format = shared && shared.format;
  var table = shared && shared.table;
  var network = shared && shared.network;
  var toYmd = format && format.toYmd;
  var fmtDateStr = format && format.fmtDateStr;
  var renderDist = table && table.renderDist;
  var renderPieFromDist = table && table.renderPieFromDist;
  var bindTrendTooltip = table && table.bindTrendTooltip;
  var fetchJsonSafe = network && network.fetchJsonSafe;
  if (!toYmd || !fmtDateStr || !renderDist || !renderPieFromDist || !bindTrendTooltip || !fetchJsonSafe) return;
  function fillDescDatesFromDate(ymd) {
    var p = (ymd || toYmd(new Date())).split('-');
    if (p.length < 3) return;
    var base = new Date(parseInt(p[0], 10), parseInt(p[1], 10) - 1, parseInt(p[2], 10));
    function addDays(n) { var x = new Date(base); x.setDate(x.getDate() - n); return x.getMonth() + 1 + '月' + x.getDate() + '日'; }
    var el1 = document.getElementById('desc-date-1');
    var el3 = document.getElementById('desc-date-3');
    var el7 = document.getElementById('desc-date-7');
    if (el1) el1.textContent = addDays(1);
    if (el3) el3.textContent = addDays(3);
    if (el7) el7.textContent = addDays(7);
  }
  function renderRateDist(list, elId) {
    var el = document.getElementById(elId);
    if (!el) return;
    list = list || [];
    if (!list.length) { el.innerHTML = '<div style="color:var(--text-muted);">暂无数据</div>'; return; }
    el.innerHTML = list.map(function(item, idx) {
      var name = item.value || '未知';
      var blocked = item.count || 0;
      var total = item.totalCount || 0;
      var rate = item.percent != null ? Number(item.percent) : 0;
      if (rate < 0) rate = 0;
      if (rate > 100) rate = 100;
      return '<div class="item-row"><div class="row-head"><div class="left"><span class="rank">' + (idx + 1) + '</span><span class="name" title="' + name + '">' + name + '</span></div><span class="meta">' + blocked + '/' + total + ' · ' + rate + '%</span></div><div class="bar"><div class="fill" style="width:' + rate + '%"></div></div></div>';
    }).join('');
  }
  function renderMissingRateHint(elId) {
    var el = document.getElementById(elId);
    if (!el) return;
    el.innerHTML = '<div style="color:var(--text-muted);">后端未返回对应封号率字段，请升级后端版本</div>';
  }
  function renderBlockRate(data) {
    var items = [
      { key: 'nextDay', rateId: 'block-next-day-rate', detailId: 'block-next-day-detail', descId: 'desc-date-1' },
      { key: 'threeDay', rateId: 'block-three-day-rate', detailId: 'block-three-day-detail', descId: 'desc-date-3' },
      { key: 'sevenDay', rateId: 'block-seven-day-rate', detailId: 'block-seven-day-detail', descId: 'desc-date-7' }
    ];
    items.forEach(function(it) {
      var d = data[it.key];
      if (!d) return;
      document.getElementById(it.rateId).textContent = (d.blockRate != null ? d.blockRate : 0) + '%';
      document.getElementById(it.detailId).textContent = d.baseDate + ' 共' + d.total + ' 封' + d.blocked;
      var descEl = document.getElementById(it.descId);
      if (descEl) descEl.textContent = fmtDateStr(d.baseDate);
    });
    ['block-next-day-rate', 'block-three-day-rate', 'block-seven-day-rate'].forEach(function(id) {
      var el = document.getElementById(id);
      if (!el) return;
      var card = el.closest('.stat-card');
      if (!card) return;
      var val = parseFloat((el.textContent || '').replace('%', '')) || 0;
      if (val >= 30) card.classList.add('alert');
      else card.classList.remove('alert');
    });
    var selectedKey = window.__blockPeriodKey || 'nextDay';
    var currentItem = data[selectedKey] || null;
    var labelEl = document.getElementById('block-period-label');
    var blockedEl = document.getElementById('block-period-blocked');
    var totalEl = document.getElementById('block-period-total');
    var rateEl = document.getElementById('block-period-rate');
    if (currentItem) {
      if (labelEl) labelEl.textContent = currentItem.label || selectedKey;
      if (blockedEl) blockedEl.textContent = currentItem.blocked != null ? currentItem.blocked : 0;
      if (totalEl) totalEl.textContent = currentItem.total != null ? currentItem.total : 0;
      if (rateEl) rateEl.textContent = (currentItem.blockRate != null ? currentItem.blockRate : 0) + '%';
    }
    var byPeriod = data.blockedDetailByPeriod || {};
    var bd = byPeriod[selectedKey] || data.blockedDetail || {};
    renderDist(bd.androidVersionDist || [], 'block-android-list');
    renderDist(bd.behaviorDist || [], 'block-behavior-list');
    renderDist(bd.tiktokVersionDist || [], 'block-tiktok-list');
    renderDist(bd.countryDist || [], 'block-country-list');
    renderDist(bd.phoneServerIpDist || [], 'block-server-ip-list');
    var hasRateDistFields = Object.prototype.hasOwnProperty.call(bd, 'androidVersionRateDist') || Object.prototype.hasOwnProperty.call(bd, 'behaviorRateDist') || Object.prototype.hasOwnProperty.call(bd, 'tiktokVersionRateDist') || Object.prototype.hasOwnProperty.call(bd, 'countryRateDist') || Object.prototype.hasOwnProperty.call(bd, 'phoneServerIpRateDist');
    if (hasRateDistFields) {
      renderRateDist(bd.androidVersionRateDist || [], 'block-rate-android-list');
      renderRateDist(bd.behaviorRateDist || [], 'block-rate-behavior-list');
      renderRateDist(bd.tiktokVersionRateDist || [], 'block-rate-tiktok-list');
      renderRateDist(bd.countryRateDist || [], 'block-rate-country-list');
      renderRateDist(bd.phoneServerIpRateDist || [], 'block-rate-server-ip-list');
    } else {
      renderMissingRateHint('block-rate-android-list');
      renderMissingRateHint('block-rate-behavior-list');
      renderMissingRateHint('block-rate-tiktok-list');
      renderMissingRateHint('block-rate-country-list');
      renderMissingRateHint('block-rate-server-ip-list');
    }
    renderPieFromDist(bd.androidVersionDist || [], 'block-android-pie', 'block-android-legend');
    renderPieFromDist(bd.behaviorDist || [], 'block-behavior-pie', 'block-behavior-legend');
    renderPieFromDist(bd.tiktokVersionDist || [], 'block-tiktok-pie', 'block-tiktok-legend');
    renderPieFromDist(bd.countryDist || [], 'block-country-pie', 'block-country-legend');
    renderPieFromDist(bd.phoneServerIpDist || [], 'block-server-pie', 'block-server-legend');
    var btnMap = { nextDay: document.getElementById('btn-block-next-day'), threeDay: document.getElementById('btn-block-three-day'), sevenDay: document.getElementById('btn-block-seven-day') };
    Object.keys(btnMap).forEach(function(k) {
      var b = btnMap[k];
      if (!b) return;
      if (k === selectedKey) { b.classList.remove('ghost'); b.classList.add('primary'); }
      else { b.classList.remove('primary'); b.classList.add('ghost'); }
    });
  }
  function renderBlockTrend(data) {
    if (!trendBars) return;
    var list = (data && data.trend) || [];
    if (!list.length) {
      trendBars.innerHTML = '<div style="color:var(--text-muted);padding:20px;text-align:center;">暂无趋势数据 ♡</div>';
      return;
    }
    var maxRate = Math.max(1, Math.max.apply(null, list.map(function(it) { return (it.blockRate != null ? it.blockRate : 0); })));
    trendBars.innerHTML = list.map(function(it, idx) {
      var rate = it.blockRate != null ? it.blockRate : 0;
      var pct = maxRate > 0 ? (rate / maxRate * 100) : 0;
      var label = it.label || it.date;
      return '<div class="trend-day' + (idx === list.length - 1 ? ' today' : '') + '" data-rate="' + rate + '" data-total="' + (it.total || 0) + '" data-blocked="' + (it.blocked || 0) + '" data-label="' + (label || '') + '"><div class="trend-day-bars"><div class="trend-day-bar twofa" style="height:' + pct + '%"></div></div><div class="vals">' + rate + '%</div><span class="label">' + (label || '') + '</span></div>';
    }).join('');
    bindTrendTooltip(trendBars, '.trend-day', function(el) {
      var label = el.getAttribute('data-label') || '';
      var rate = el.getAttribute('data-rate') || '0';
      var total = el.getAttribute('data-total') || '0';
      var blocked = el.getAttribute('data-blocked') || '0';
      return (label ? label + '<br>' : '') + '封号率: ' + rate + '% · 总数: ' + total + ' · 封禁: ' + blocked;
    });
  }
  function loadBlockRate(dateStr) {
    if (forceMockPage) {
      renderBlockRate(MOCK_BLOCK_RATE_DATA);
      renderBlockTrend(MOCK_BLOCK_TREND_DATA);
      fillDescDatesFromDate(dateStr);
      return;
    }
    var url = '/api/statistics/block-rate';
    if (dateStr) url += '?date=' + encodeURIComponent(dateStr);
    fetchJsonSafe(url).then(function(res) {
      if (res && res.success && res.data) {
        renderBlockRate(res.data);
      } else {
        document.getElementById('block-next-day-rate').textContent = '—';
        document.getElementById('block-three-day-rate').textContent = '—';
        document.getElementById('block-seven-day-rate').textContent = '—';
        fillDescDatesFromDate(dateStr);
      }
    });
    var trendUrl = '/api/statistics/block-rate-trend';
    if (dateStr) trendUrl += '?date=' + encodeURIComponent(dateStr);
    if (trendBars) {
      fetchJsonSafe(trendUrl).then(function(res) {
        if (res && res.success && res.data) renderBlockTrend(res.data);
        else renderBlockTrend(null);
      });
    }
  }
  if (dateInput) {
    var todayStr = toYmd(new Date());
    var fpBlock = flatpickr(dateInput, {
      locale: 'zh',
      dateFormat: 'Y-m-d',
      defaultDate: todayStr,
      maxDate: todayStr,
      allowInput: false,
      onChange: function(sel, dateStr) { if (dateStr) loadBlockRate(dateStr); }
    });
    var brBtnToday = document.getElementById('block-rate-btn-today');
    if (brBtnToday) brBtnToday.addEventListener('click', function() {
      var t = toYmd(new Date());
      fpBlock.setDate(t, true);
      loadBlockRate(t);
    });
  }
  function bindBlockPeriodButtons() {
    var m = { 'btn-block-next-day': 'nextDay', 'btn-block-three-day': 'threeDay', 'btn-block-seven-day': 'sevenDay' };
    Object.keys(m).forEach(function(id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('click', function() {
        window.__blockPeriodKey = m[id];
        var d = dateInput && dateInput.value ? dateInput.value : toYmd(new Date());
        loadBlockRate(d);
      });
    });
  }
  window.__blockPeriodKey = 'nextDay';
  bindBlockPeriodButtons();
  var blockLoaded = false;
  window.addEventListener('page:changed', function(e) {
    if (!e || !e.detail || e.detail.pageId !== 'block-rate') return;
    if (blockLoaded) return;
    blockLoaded = true;
    loadBlockRate(toYmd(new Date()));
  });
})();
