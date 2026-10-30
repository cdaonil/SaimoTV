/*
 * Saimo TV - navegação por controle remoto (setas + OK) para Android TV.
 * Torna os cards clicáveis do site "focáveis" e move o foco pelo que está
 * visualmente na direção apertada (cima, baixo, esquerda, direita).
 * Escrito em ES5 para funcionar no WebView antigo do Mi TV Stick.
 */
(function () {
  if (window.__saimoTvNav) return;
  window.__saimoTvNav = true;

  var KEY = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' };
  var ENTER = 13, DPAD_CENTER = 23;

  // ---- estilo do item selecionado ----
  var css = document.createElement('style');
  css.textContent =
    '.tv-focus{outline:4px solid #f5c518 !important;outline-offset:2px !important;' +
    'box-shadow:0 0 0 6px rgba(245,197,24,.35),0 8px 24px rgba(0,0,0,.6) !important;' +
    'position:relative;z-index:5;transform:scale(1.06);transition:transform .12s ease;}' +
    ':focus{outline:none;}';
  (document.head || document.documentElement).appendChild(css);

  // Elementos que podem ser clicáveis
  var SELECTOR = 'a[href],button,input,select,textarea,[tabindex],[role=button],[role=link],' +
    '[role=tab],[role=menuitem],[role=option],[onclick],img,video,' +
    '[class*=card],[class*=Card],[class*=poster],[class*=Poster],[class*=item],[class*=Item],' +
    '[class*=thumb],[class*=channel],[class*=Channel],[class*=movie],[class*=Movie],' +
    '[class*=filme],[class*=canal],[class*=serie],[class*=episod],[class*=tile]';

  var NATIVE = /^(A|BUTTON|INPUT|SELECT|TEXTAREA|VIDEO)$/;

  function isPointer(el) {
    try { return window.getComputedStyle(el).cursor === 'pointer'; } catch (e) { return false; }
  }

  // Para imagens e itens soltos, sobe até o "card" clicável que os contém
  function clickableOf(el) {
    var cur = el, depth = 0;
    while (cur && cur !== document.body && depth < 6) {
      if (NATIVE.test(cur.tagName) || cur.hasAttribute('onclick') ||
          cur.getAttribute('role') === 'button' || cur.getAttribute('role') === 'link' ||
          (cur.hasAttribute('tabindex') && cur.getAttribute('tabindex') !== '-1') ||
          isPointer(cur)) {
        // sobe enquanto o pai também for clicável e do mesmo tamanho aproximado
        var p = cur.parentElement;
        while (p && p !== document.body && isPointer(p) && !NATIVE.test(cur.tagName)) {
          var a = cur.getBoundingClientRect(), b = p.getBoundingClientRect();
          if (b.width > a.width * 1.6 || b.height > a.height * 1.6) break;
          cur = p; p = cur.parentElement;
        }
        return cur;
      }
      cur = cur.parentElement; depth++;
    }
    return null;
  }

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var s = window.getComputedStyle(el);
    if (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity) === 0) return false;
    if (el.disabled) return false;
    return true;
  }

  function candidates() {
    var nodes = document.querySelectorAll(SELECTOR);
    var list = [], seen = [];
    for (var i = 0; i < nodes.length; i++) {
      var c = clickableOf(nodes[i]);
      if (!c || seen.indexOf(c) !== -1) continue;
      if (c.getAttribute('tabindex') === '-1' && !NATIVE.test(c.tagName)) continue;
      if (!visible(c)) continue;
      seen.push(c);
      list.push(c);
    }
    // Se um item está dentro de outro card de tamanho parecido, fica só o card.
    // (Contêineres enormes, como uma seção inteira, não engolem os cards.)
    var keep = list.filter(function (el) {
      var er = el.getBoundingClientRect(), ea = er.width * er.height;
      for (var j = 0; j < list.length; j++) {
        var o = list[j];
        if (o === el || !o.contains(el)) continue;
        var orr = o.getBoundingClientRect();
        if (orr.width * orr.height < ea * 4) return false;
      }
      return true;
    });
    // descarta contêineres enormes que só servem de "fundo" para vários cards
    return keep.filter(function (el) {
      var n = 0;
      for (var j = 0; j < keep.length; j++) if (keep[j] !== el && el.contains(keep[j])) n++;
      return n < 2;
    });
  }

  function makeFocusable(el) {
    if (!NATIVE.test(el.tagName) && !el.hasAttribute('tabindex')) el.setAttribute('tabindex', '0');
  }

  var current = null;

  function setFocus(el) {
    if (!el) return;
    if (current && current.classList) current.classList.remove('tv-focus');
    current = el;
    makeFocusable(el);
    el.classList.add('tv-focus');
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' }); }
    catch (e2) { el.scrollIntoView(false); }
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function best(dir, list, from) {
    var fr = from.getBoundingClientRect(), fc = center(fr);
    var bestEl = null, bestScore = Infinity;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === from || el.contains(from) || from.contains(el)) continue;
      var r = el.getBoundingClientRect(), c = center(r);
      var dx = c.x - fc.x, dy = c.y - fc.y, primary, ortho, overlap;
      if (dir === 'right' || dir === 'left') {
        primary = dir === 'right' ? dx : -dx;
        if (primary <= Math.min(fr.width, r.width) * 0.3) continue;
        ortho = Math.abs(dy);
        // mesma "linha" de cards ganha prioridade forte
        overlap = Math.min(fr.bottom, r.bottom) - Math.max(fr.top, r.top);
        if (overlap <= 0) ortho += 2000;
      } else {
        primary = dir === 'down' ? dy : -dy;
        if (primary <= Math.min(fr.height, r.height) * 0.3) continue;
        ortho = Math.abs(dx);
        overlap = Math.min(fr.right, r.right) - Math.max(fr.left, r.left);
        if (overlap <= 0) ortho *= 1.5;
      }
      var score = primary + ortho * 2;
      if (score < bestScore) { bestScore = score; bestEl = el; }
    }
    return bestEl;
  }

  function firstOnScreen(list) {
    var bestEl = null, bestScore = Infinity, vh = window.innerHeight;
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      if (r.bottom < 0 || r.top > vh) continue;
      var s = r.top * 3 + r.left;
      if (s < bestScore) { bestScore = s; bestEl = list[i]; }
    }
    return bestEl || list[0];
  }

  function activate(el) {
    var r = el.getBoundingClientRect(), c = center(r);
    var opts = { bubbles: true, cancelable: true, view: window, clientX: c.x, clientY: c.y };
    try {
      if (window.PointerEvent) {
        el.dispatchEvent(new PointerEvent('pointerdown', opts));
        el.dispatchEvent(new PointerEvent('pointerup', opts));
      }
      el.dispatchEvent(new MouseEvent('mousedown', opts));
      el.dispatchEvent(new MouseEvent('mouseup', opts));
    } catch (e) {}
    el.click();
  }

  function isTyping(el) {
    return el && (el.tagName === 'TEXTAREA' ||
      (el.tagName === 'INPUT' && !/^(button|submit|checkbox|radio|range)$/i.test(el.type)) ||
      el.isContentEditable);
  }

  document.addEventListener('keydown', function (e) {
    if (document.fullscreenElement || document.webkitFullscreenElement) return;
    var code = e.keyCode, dir = KEY[code], active = document.activeElement;

    if (code === ENTER || code === DPAD_CENTER) {
      var target = (current && document.contains(current)) ? current : active;
      if (!target || target === document.body) return;
      if (NATIVE.test(target.tagName)) return; // links/botões já funcionam sozinhos
      e.preventDefault(); e.stopPropagation();
      activate(target);
      return;
    }
    if (!dir) return;
    if (isTyping(active) && (dir === 'left' || dir === 'right')) return;

    var list = candidates();
    if (!list.length) return;
    var from = (current && document.contains(current) && visible(current)) ? current :
               (active && active !== document.body && list.indexOf(active) !== -1 ? active : null);

    e.preventDefault(); e.stopPropagation();
    if (!from) { setFocus(firstOnScreen(list)); return; }
    var next = best(dir, list, from);
    if (next) setFocus(next);
    else if (dir === 'up') window.scrollBy(0, -window.innerHeight / 2);
    else if (dir === 'down') window.scrollBy(0, window.innerHeight / 2);
  }, true);

  // Se o usuário tocar/clicar em algo, o foco acompanha
  document.addEventListener('focusin', function (e) {
    var el = e.target;
    if (el && el !== current && el !== document.body && el.classList) {
      if (current) current.classList.remove('tv-focus');
      current = el; el.classList.add('tv-focus');
    }
  }, true);
})();
