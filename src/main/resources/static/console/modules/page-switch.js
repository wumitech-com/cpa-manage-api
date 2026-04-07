// 页面切换控制
(function() {
  var navItems = document.querySelectorAll('.nav-item[data-page]');
  var currentPage = 'overview';
  if (!navItems || !navItems.length) return;

  function switchPage(pageId) {
    if (pageId === currentPage) return;
    var targetPage = document.getElementById('page-' + pageId);
    var currentPageEl = document.getElementById('page-' + currentPage);
    if (!targetPage || !currentPageEl) return;

    currentPageEl.classList.add('leaving');
    setTimeout(function() {
      currentPageEl.classList.remove('active', 'leaving');
      targetPage.classList.add('active');
      currentPage = pageId;
      navItems.forEach(function(nav) {
        nav.classList.toggle('active', nav.getAttribute('data-page') === pageId);
      });
      window.dispatchEvent(new CustomEvent('page:changed', { detail: { pageId: pageId } }));
    }, 350);
  }

  navItems.forEach(function(nav) {
    nav.addEventListener('click', function() {
      var page = this.getAttribute('data-page');
      if (page) switchPage(page);
    });
  });

  window.dispatchEvent(new CustomEvent('page:changed', { detail: { pageId: currentPage } }));
})();
