// Inlined in the <head> of /en/. Never guesses; only follows a saved (or ?lang=) Banglish preference.
(function (l) {
  var K = 'harkara.lang', m = /[?&]lang=(bn|en)(&|$)/.exec(l.search), p;
  if (/bot|crawl|spider|slurp|preview/i.test(navigator.userAgent)) return;
  try { if (m) localStorage.setItem(K, m[1]); p = localStorage.getItem(K); } catch (e) {}
  if (p == 'bn') l.replace('../' + l.search.replace(/([?&])lang=[^&#]*&?/, '$1').replace(/[?&]$/, '') + l.hash);
})(location);
