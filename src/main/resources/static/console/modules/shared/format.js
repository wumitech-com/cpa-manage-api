// 共享 UI：格式化/转义
(function() {
  var root = window.ConsoleSharedUi || {};
  root.format = root.format || {};

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

  root.format.esc = esc;
  root.format.toYmd = toYmd;
  root.format.fmtTime = fmtTime;
  root.format.fmtDateStr = fmtDateStr;
  root.format.fmtGb = fmtGb;

  // flat aliases
  root.esc = esc;
  root.toYmd = toYmd;
  root.fmtTime = fmtTime;
  root.fmtDateStr = fmtDateStr;
  root.fmtGb = fmtGb;

  window.ConsoleSharedUi = root;
})();
