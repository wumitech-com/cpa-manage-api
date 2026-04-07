// 控制台模块统一引导器
(function() {
  var modules = [
    '/console/modules/page-switch.js',
    '/console/modules/shared/core.js',
    '/console/modules/shared/format.js',
    '/console/modules/shared/network.js',
    '/console/modules/shared/table.js',
    '/console/modules/shared/feedback.js',
    '/console/modules/overview-page.js',
    '/console/modules/block-rate-page.js',
    '/console/modules/task-page.js',
    '/console/modules/account-window-pages.js',
    '/console/modules/device-retention-pages.js'
  ];

  modules.forEach(function(src) {
    var moduleScript = document.createElement('script');
    moduleScript.src = src;
    moduleScript.async = false;
    document.head.appendChild(moduleScript);
  });
})();
