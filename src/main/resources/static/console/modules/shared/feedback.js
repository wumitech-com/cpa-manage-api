// 共享 UI：反馈/按钮/提示
(function() {
  var root = window.ConsoleSharedUi || {};
  root.feedback = root.feedback || {};

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

  root.feedback.withButtonLoading = withButtonLoading;
  root.feedback.resetFileLabelWithInput = resetFileLabelWithInput;
  root.feedback.notify = notify;
  root.feedback.showInlineNotice = showInlineNotice;

  // flat aliases
  root.withButtonLoading = withButtonLoading;
  root.resetFileLabelWithInput = resetFileLabelWithInput;
  root.notify = notify;
  root.showInlineNotice = showInlineNotice;

  window.ConsoleSharedUi = root;
})();
