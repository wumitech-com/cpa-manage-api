// 共享 UI：表格/分布/分页
(function() {
  var root = window.ConsoleSharedUi || {};
  root.table = root.table || {};

  var TABLE_TEXT = {
    EMPTY_ACCOUNT: '没有匹配的账号～',
    EMPTY_WINDOW: '没有匹配的数据～',
    EMPTY_TASK: '没有匹配的任务～',
    EMPTY_RETENTION: '这一天还没有留存记录～',
    LOADING: '加载中…',
    LOAD_FAILED: '加载失败，请稍后重试'
  };

  function renderDist(list, elId) {
    var el = document.getElementById(elId);
    if (!el) return;
    list = list || [];
    if (!list.length) {
      el.innerHTML = '<div style="color:var(--text-muted);">暂无数据</div>';
      return;
    }
    el.innerHTML = list.map(function(item, idx) {
      var name = item.value || '未知';
      var count = item.count || 0;
      var pct = item.percent != null ? Number(item.percent) : 0;
      if (pct < 0) pct = 0;
      if (pct > 100) pct = 100;
      return '<div class="item-row"><div class="row-head"><div class="left"><span class="rank">' + (idx + 1) +
        '</span><span class="name" title="' + name + '">' + name + '</span></div><span class="meta">' + count +
        ' · ' + pct + '%</span></div><div class="bar"><div class="fill" style="width:' + pct + '%"></div></div></div>';
    }).join('');
  }

  function renderPieFromDist(list, pieId, legendId) {
    var pieEl = document.getElementById(pieId);
    var legendEl = document.getElementById(legendId);
    if (!pieEl || !legendEl) return;
    list = list || [];
    if (!list.length) {
      pieEl.style.background = 'conic-gradient(#e5e7eb 0 100%)';
      pieEl.innerHTML = '';
      legendEl.innerHTML = '<div style="opacity:.8;">暂无数据</div>';
      return;
    }
    var palette = ['#fb7185', '#f59e0b', '#10b981', '#6366f1', '#06b6d4', '#a855f7', '#f43f5e', '#84cc16'];
    var top = list.slice(0, 6);
    var start = 0;
    var segs = [];
    var legend = [];
    top.forEach(function(item, i) {
      var p = item.percent != null ? Number(item.percent) : 0;
      var end = Math.min(100, start + p);
      var color = palette[i % palette.length];
      segs.push(color + ' ' + start + '% ' + end + '%');
      var name = item.value || '未知';
      var cnt = item.count || 0;
      legend.push('<div style="display:flex;align-items:center;justify-content:space-between;gap:8px;padding:2px 0;">' +
        '<div style="display:flex;align-items:center;gap:6px;min-width:0;"><span style="width:16px;height:16px;border-radius:999px;background:' +
        color + ';color:#fff;font-size:0.62rem;font-weight:700;display:inline-flex;align-items:center;justify-content:center;">' +
        (i + 1) + '</span><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--text);">' +
        name + '</span></div><span style="white-space:nowrap;color:var(--text-muted);font-size:0.72rem;">' + cnt +
        ' · ' + p + '%</span></div>');
      start = end;
    });
    if (start < 100) segs.push('#e5e7eb ' + start + '% 100%');
    pieEl.style.background = 'conic-gradient(' + segs.join(',') + ')';
    pieEl.innerHTML = '';
    legendEl.innerHTML = legend.join('');
  }

  function bindTrendTooltip(container, itemSelector, buildHtml) {
    if (!container || !itemSelector || typeof buildHtml !== 'function') return;
    var tooltip = document.createElement('div');
    tooltip.className = 'trend-tooltip';
    tooltip.style.display = 'none';
    container.appendChild(tooltip);
    container.querySelectorAll(itemSelector).forEach(function(el) {
      el.addEventListener('mouseenter', function(e) {
        tooltip.innerHTML = buildHtml(this) || '';
        tooltip.style.display = 'block';
        var rect = container.getBoundingClientRect();
        tooltip.style.left = (e.clientX - rect.left) + 'px';
        tooltip.style.top = '0px';
      });
      el.addEventListener('mousemove', function(e) {
        if (tooltip.style.display === 'none') return;
        var rect = container.getBoundingClientRect();
        tooltip.style.left = (e.clientX - rect.left) + 'px';
      });
      el.addEventListener('mouseleave', function() {
        tooltip.style.display = 'none';
      });
    });
  }

  function renderTableMessage(tbody, colspan, message, colorVar) {
    if (!tbody) return;
    var color = colorVar || 'var(--text-muted)';
    tbody.innerHTML = '<tr><td colspan="' + colspan + '" style="text-align:center;color:' + color + ';">' + (message || '') + '</td></tr>';
  }

  function updatePagerState(options) {
    options = options || {};
    var page = Number(options.page || 1);
    var size = Number(options.size || 10);
    var total = Number(options.total || 0);
    if (size < 1) size = 10;
    var totalPages = Math.max(1, Math.ceil(total / size));
    if (page > totalPages) page = totalPages;
    if (page < 1) page = 1;

    var pageInfoEl = options.pageInfoEl;
    var prevBtn = options.prevBtn;
    var nextBtn = options.nextBtn;
    var totalCountEl = options.totalCountEl;
    var totalAccurate = options.totalAccurate;

    if (pageInfoEl) pageInfoEl.textContent = '第 ' + page + ' / ' + totalPages + ' 页';
    if (prevBtn) prevBtn.disabled = page <= 1;
    if (nextBtn) nextBtn.disabled = page >= totalPages;
    if (totalCountEl) {
      var textBuilder = options.totalTextBuilder;
      if (typeof textBuilder === 'function') {
        totalCountEl.textContent = textBuilder(total, totalAccurate);
      } else {
        totalCountEl.textContent = (totalAccurate === false ? '约 ' : '共 ') + total + ' 条';
      }
    }
    return { page: page, totalPages: totalPages, total: total };
  }

  root.table.TABLE_TEXT = TABLE_TEXT;
  root.table.renderDist = renderDist;
  root.table.renderPieFromDist = renderPieFromDist;
  root.table.bindTrendTooltip = bindTrendTooltip;
  root.table.renderTableMessage = renderTableMessage;
  root.table.updatePagerState = updatePagerState;

  // flat aliases
  root.TABLE_TEXT = TABLE_TEXT;
  root.renderDist = renderDist;
  root.renderPieFromDist = renderPieFromDist;
  root.bindTrendTooltip = bindTrendTooltip;
  root.renderTableMessage = renderTableMessage;
  root.updatePagerState = updatePagerState;

  window.ConsoleSharedUi = root;
})();
