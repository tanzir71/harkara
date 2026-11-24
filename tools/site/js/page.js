// Inlined at the end of every page. The language toggle saves the choice; the SHA-256 Copy button
// appears only when the clipboard API exists. Both are plain links/text without JS.
(function (d, n) {
  d.addEventListener('click', function (e) {
    var t = e.target.closest && e.target.closest('[data-lang],[data-copy]');
    if (!t) return;
    if (t.dataset.lang) {
      try { localStorage.setItem('harkara.lang', t.dataset.lang); } catch (x) {}
      return;
    }
    n.clipboard.writeText(t.dataset.copy).then(function () {
      var o = t.textContent;
      t.textContent = t.dataset.copied;
      setTimeout(function () { t.textContent = o; }, 2000);
    });
  });
  if (n.clipboard) [].forEach.call(d.querySelectorAll('[data-copy]'), function (b) { b.hidden = false; });
})(document, navigator);
