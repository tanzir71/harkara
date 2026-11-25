// Inlined in the <head> of / (Banglish). Decides whether this visitor should get /en/ instead (handover §7.2).
(function (w, l, n) {
  var K = 'harkara.lang', C = 'harkara.country', ls, ss, p, m;
  if (/bot|crawl|spider|slurp|preview/i.test(n.userAgent)) return;
  try { ls = w.localStorage; ss = w.sessionStorage; } catch (e) {}
  function get(s, k) { try { return s && s.getItem(k); } catch (e) {} }
  function set(s, k, v) { try { if (s) s.setItem(k, v); } catch (e) {} }
  var q = l.search.replace(/([?&])lang=[^&#]*&?/, '$1').replace(/[?&]$/, '');
  function en() { l.replace('en/' + q + l.hash); }
  m = /[?&]lang=(bn|en)(&|$)/.exec(l.search);
  if (m) {
    set(ls, K, m[1]);
    if (m[1] == 'en') en(); else if (w.history.replaceState) w.history.replaceState(null, '', l.pathname + q + l.hash);
    return;
  }
  p = get(ls, K);
  if (p) { if (p == 'en') en(); return; }
  p = get(ss, C);
  if (p) { if (p != 'BD') en(); return; }
  function guess() {
    var tz = '', L = n.languages || [n.language || ''], i;
    try { tz = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch (e) {}
    for (i = 0; i < L.length; i++) if (/^bn/i.test(L[i])) return;
    if (tz != 'Asia/Dhaka') en();
  }
  if (!w.fetch || !w.AbortController) return guess();
  var a = new AbortController(), t = setTimeout(function () { a.abort(); }, 1500);
  fetch('https://api.country.is/', { signal: a.signal, credentials: 'omit', referrerPolicy: 'no-referrer' })
    .then(function (r) { return r.json(); })
    .then(function (j) {
      clearTimeout(t);
      var c = j && j.country;
      if (!c) return guess();
      set(ss, C, c);
      if (c != 'BD') en();
    })
    .catch(function () { clearTimeout(t); guess(); });
})(window, location, navigator);
