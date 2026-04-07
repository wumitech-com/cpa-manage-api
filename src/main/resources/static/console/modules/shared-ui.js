// 控制台共享 UI 渲染工具
(function() {
  var TABLE_TEXT = {
    EMPTY_ACCOUNT: '没有匹配的账号～',
    EMPTY_WINDOW: '没有匹配的数据～',
    EMPTY_TASK: '没有匹配的任务～',
    EMPTY_RETENTION: '这一天还没有留存记录～',
    LOADING: '加载中…',
    LOAD_FAILED: '加载失败，请稍后重试'
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function toYmd(d) {
    var y = d.getFullYear(), m = d.getMonth() + 1, day = d.getDate();
    return y + '-' + (m < 10 ? '0' : '') + m + '-' + (day < 10 ? '0' : '') + day;
  }

  function fmtTime(t) {
    return t ? String(t).replace('T', ' ').substring(0, 19) : '-';
  }

  function fmtDateStr(ymd) {
    if (!ymd) return '';
    var p = String(ymd).split('-');
    if (p.length >= 3) return p[1] + '月' + parseInt(p[2], 10) + '日';
    return ymd;
  }

  function fmtGb(bytesVal) {
    var n = Number(bytesVal);
    if (!isFinite(n) || n <= 0) return '0 GB';
    var gb = n / (1024 * 1024 * 1024);
    return (Math.round(gb * 100) / 100) + ' GB';
  }

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

  function fetchJson(url, options) {
    return fetch(url, options).then(function(r) { return r.json(); });
  }

  function fetchJsonSafe(url, options) {
    return fetchJson(url, options).catch(function() { return null; });
  }

  function renderTableMessage(tbody, colspan, message, colorVar) {
    if (!tbody) return;
    var color = colorVar || 'var(--text-muted)';
    tbody.innerHTML = '<tr><td colspan="' + colspan + '" style="text-align:center;color:' + color + ';">' + (message || '') + '</td></tr>';
  }

  function buildErrorMessage(prefix, response, fallback) {
    var p = prefix || '加载失败';
    var fb = fallback || '未知错误';
    var msg = response && response.message ? String(response.message) : fb;
    return p + '：' + msg;
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

  function withButtonLoading(button, loadingText, work, doneText) {
    if (!button || typeof work !== 'function') {
      return Promise.resolve().then(function() {
        return typeof work === 'function' ? work() : null;
      });
    }
    var originalText = button.textContent;
    button.disabled = true;
    if (typeof loadingText === 'string') button.textContent = loadingText;
    return Promise.resolve()
      .then(work)
      .finally(function() {
        button.disabled = false;
        if (typeof doneText === 'string') button.textContent = doneText;
        else button.textContent = originalText;
      });
  }

  function resetFileLabelWithInput(labelEl, buttonText, inputId, accept) {
    if (!labelEl) return;
    var safeText = buttonText || '选择文件';
    var safeId = inputId || 'file-input';
    var safeAccept = accept || '*/*';
    labelEl.innerHTML = safeText + '<input type="file" id="' + safeId + '" accept="' + safeAccept + '" style="display:none;" />';
  }

  function notify(message) {
    var text = message == null ? '' : String(message);
    if (!text) return;
    if (typeof document !== 'undefined' && document.body) {
      var styleId = 'console-toast-style';
      if (!document.getElementById(styleId)) {
        var styleEl = document.createElement('style');
        styleEl.id = styleId;
        styleEl.textContent =
          '.console-toast-wrap{position:fixed;right:20px;bottom:20px;z-index:9999;display:flex;flex-direction:column;gap:8px;max-width:min(420px,85vw);}' +
          '.console-toast{background:rgba(15,23,42,.9);color:#fff;padding:10px 12px;border-radius:10px;font-size:12px;line-height:1.5;box-shadow:0 10px 30px rgba(2,6,23,.25);opacity:0;transform:translateY(8px);transition:all .2s ease;}' +
          '.console-toast.show{opacity:1;transform:translateY(0);}';
        document.head.appendChild(styleEl);
      }
      var wrap = document.getElementById('console-toast-wrap');
      if (!wrap) {
        wrap = document.createElement('div');
        wrap.id = 'console-toast-wrap';
        wrap.className = 'console-toast-wrap';
        document.body.appendChild(wrap);
      }
      var toast = document.createElement('div');
      toast.className = 'console-toast';
      toast.textContent = text;
      wrap.appendChild(toast);
      setTimeout(function() { toast.classList.add('show'); }, 10);
      setTimeout(function() {
        toast.classList.remove('show');
        setTimeout(function() {
          if (toast.parentNode) toast.parentNode.removeChild(toast);
        }, 220);
      }, 2600);
      return;
    }
    if (typeof window !== 'undefined' && typeof window.alert === 'function') {
      window.alert(text);
    }
  }

  function showInlineNotice(targetEl, options) {
    if (!targetEl) return;
    options = options || {};
    var html = options.html || '';
    var background = options.background || '';
    var borderColor = options.borderColor || '';
    var hideAfterMs = Number(options.hideAfterMs || 0);
    targetEl.innerHTML = html;
    if (background) targetEl.style.background = background;
    if (borderColor) targetEl.style.borderColor = borderColor;
    targetEl.style.display = 'block';
    if (hideAfterMs > 0) {
      setTimeout(function() { targetEl.style.display = 'none'; }, hideAfterMs);
    }
  }

  var format = {
    esc: esc,
    toYmd: toYmd,
    fmtTime: fmtTime,
    fmtDateStr: fmtDateStr,
    fmtGb: fmtGb
  };
  var network = {
    fetchJson: fetchJson,
    fetchJsonSafe: fetchJsonSafe,
    buildErrorMessage: buildErrorMessage
  };
  var table = {
    TABLE_TEXT: TABLE_TEXT,
    renderDist: renderDist,
    renderPieFromDist: renderPieFromDist,
    bindTrendTooltip: bindTrendTooltip,
    renderTableMessage: renderTableMessage,
    updatePagerState: updatePagerState
  };
  var feedback = {
    withButtonLoading: withButtonLoading,
    resetFileLabelWithInput: resetFileLabelWithInput,
    notify: notify,
    showInlineNotice: showInlineNotice
  };

  window.ConsoleSharedUi = {
    // grouped namespaces (new, preferred)
    format: format,
    network: network,
    table: table,
    feedback: feedback,

    // flat aliases (backward compatible)
    TABLE_TEXT: TABLE_TEXT,
    esc: esc,
    toYmd: toYmd,
    fmtTime: fmtTime,
    fmtDateStr: fmtDateStr,
    fmtGb: fmtGb,
    renderDist: renderDist,
    renderPieFromDist: renderPieFromDist,
    bindTrendTooltip: bindTrendTooltip,
    fetchJson: fetchJson,
    fetchJsonSafe: fetchJsonSafe,
    renderTableMessage: renderTableMessage,
    buildErrorMessage: buildErrorMessage,
    updatePagerState: updatePagerState,
    withButtonLoading: withButtonLoading,
    resetFileLabelWithInput: resetFileLabelWithInput,
    notify: notify,
    showInlineNotice: showInlineNotice
  };
})();
