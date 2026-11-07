@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.admin.core.util.browser

import org.example.project.ui.guard.HistoryTraversalGate
import org.example.project.ui.guard.LeaveGuard
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Wraps the two ways the browser itself leaves the panel while a form holds unsaved edits.
 *
 * **Reload / tab close:** a `beforeunload` handler that asks the browser's own prompt while
 * [LeaveGuard.isDirty]. Its wording is the browser's and cannot be localized.
 *
 * **Back / Forward:** a `popstate` cannot be vetoed, because the browser has already moved when it
 * fires, and `bindToBrowserNavigation` then pops the NavController from it. So a **capture-phase**
 * listener, installed **before** that binding registers its own, swallows a traversal that carries
 * a history state while the guard is dirty, restores the entry we were on with `pushState`, and
 * asks. On Discard the traversal is replayed: exactly, with `history.go(delta)`, when the target is in
 * the trail of entries seen since install (the restore was then `history.go(-delta)`, so no entry is
 * added), otherwise as a synthetic `popstate` (which adds one history entry). A typed `#hash` has no state
 * and is deliberately not guarded.
 *
 * Returns the function that removes both listeners and restores `history.pushState/replaceState`.
 */
fun installBrowserLeaveGuard(guard: LeaveGuard): () -> Unit {
    val gate = HistoryTraversalGate()
    var handle: JsAny? = null
    handle =
        installJs(
            shouldHold = { hasState -> gate.onPopState(hasState, guard.isDirty) == HistoryTraversalGate.Decision.Hold },
            onHold = {
                guard.requestLeave { handle?.let { replayJs(it) } }
            },
            isDirty = { guard.isDirty },
        )
    return { handle?.let { disposeJs(it) } }
}

private fun installJs(
    shouldHold: (Boolean) -> Boolean,
    onHold: () -> Unit,
    isDirty: () -> Boolean,
): JsAny =
    js(
        """(function () {
  // `trail` mirrors the history entries seen since install, so a held traversal can be undone EXACTLY
  // with history.go(-delta) (and replayed with history.go(delta)): no extra entry is added. A target
  // outside the trail (an entry from before install, a multi-step jump) falls back to restoring the
  // current entry with pushState and replaying a synthetic popstate, which adds one entry.
  var trail = [history.state];
  var pos = 0;
  var push = history.pushState.bind(history);
  var repl = history.replaceState.bind(history);
  var cur = { state: history.state, url: location.href };
  function snapshot() { cur = { state: history.state, url: location.href }; }
  history.pushState = function (s, t, u) { push(s, t, u); trail = trail.slice(0, pos + 1); trail.push(history.state); pos++; snapshot(); };
  history.replaceState = function (s, t, u) { repl(s, t, u); trail[pos] = history.state; snapshot(); };
  // Nearest earlier/later entry with this exact state. Route-stack states repeat (visit the same screens
  // twice and entries are identical), so two equally near matches are ambiguous: return null and let the
  // caller fall back to the pushState restore instead of guessing a direction.
  function deltaOf(state) {
    var best = null, tie = false;
    for (var i = 0; i < trail.length; i++) {
      if (i === pos || trail[i] !== state) continue;
      var d = Math.abs(i - pos);
      if (best === null || d < Math.abs(best - pos)) { best = i; tie = false; }
      else if (d === Math.abs(best - pos)) tie = true;
    }
    return (best === null || tie) ? null : best - pos;
  }
  function follow(state) {
    var d = deltaOf(state);
    if (d !== null) pos += d; else { trail = [state]; pos = 0; }
    snapshot();
  }
  // `undoing` / `replaying` swallow exactly the one popstate our own history.go() causes. They clear
  // themselves after a moment, so a go() that turned out to be a no-op can never leave the guard blind.
  var undoing = false;
  var replaying = false;
  var held = null;
  var heldDelta = null;
  function flag(name) { setTimeout(function () { if (name === 'u') undoing = false; else replaying = false; }, 600); }
  function onPop(e) {
    if (undoing) { undoing = false; snapshot(); return; }
    if (replaying) { replaying = false; follow(e.state); return; }
    if (!shouldHold(e.state != null)) { follow(e.state); return; }
    e.stopImmediatePropagation();
    held = e.state;
    heldDelta = deltaOf(e.state);
    if (heldDelta !== null) { undoing = true; flag('u'); history.go(-heldDelta); }
    else { push(cur.state, '', cur.url); }
    onHold();
  }
  function onUnload(e) { if (isDirty()) { e.preventDefault(); e.returnValue = ''; } }
  window.addEventListener('popstate', onPop, true);
  window.addEventListener('beforeunload', onUnload);
  return {
    replay: function () {
      var s = held, d = heldDelta; held = null; heldDelta = null;
      if (s == null) return;
      replaying = true; flag('r');
      if (d !== null) history.go(d);
      else window.dispatchEvent(new PopStateEvent('popstate', { state: s }));
    },
    dispose: function () {
      window.removeEventListener('popstate', onPop, true);
      window.removeEventListener('beforeunload', onUnload);
      history.pushState = push; history.replaceState = repl;
    }
  };
})()""",
    )

private fun replayJs(handle: JsAny): Unit = js("handle.replay()")

private fun disposeJs(handle: JsAny): Unit = js("handle.dispose()")
