// 共享 UI：网络请求
(function() {
  var root = window.ConsoleSharedUi || {};
  root.network = root.network || {};

  function fetchJson(url, options) {
    return fetch(url, options).then(function(r) { return r.json(); });
  }

  function fetchJsonSafe(url, options) {
    return fetchJson(url, options).catch(function() { return null; });
  }

  function buildErrorMessage(prefix, response, fallback) {
    var p = prefix || '加载失败';
    var fb = fallback || '未知错误';
    var msg = response && response.message ? String(response.message) : fb;
    return p + '：' + msg;
  }

  root.network.fetchJson = fetchJson;
  root.network.fetchJsonSafe = fetchJsonSafe;
  root.network.buildErrorMessage = buildErrorMessage;

  // flat aliases
  root.fetchJson = fetchJson;
  root.fetchJsonSafe = fetchJsonSafe;
  root.buildErrorMessage = buildErrorMessage;

  window.ConsoleSharedUi = root;
})();
