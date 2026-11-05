// SPDX-License-Identifier: Apache-2.0
// Trailkeep client behaviour (ADR-0009): the htmx response contract, focus management, the live region, the
// native <dialog> helper and the session-expiry warning. No inline handlers, no eval: everything is wired here.
(function () {
  'use strict';

  const WARNING_SECONDS = 120;

  function meta(name) {
    const element = document.querySelector('meta[name="' + name + '"]');
    return element ? element.getAttribute('content') : null;
  }

  /** Announces text in the polite live region (BUILD_PLAN §10.1 "Dynamic updates"). */
  function announce(text) {
    const region = document.getElementById('tk-live');
    if (!region || !text) {
      return;
    }
    region.textContent = '';
    window.setTimeout(function () { region.textContent = text; }, 50);
  }

  /** Moves focus to the first heading (or [data-tk-focus]) of updated content. */
  function focusRegion(root) {
    const target = root.querySelector('[data-tk-focus], h1, h2, h3');
    if (target) {
      if (!target.hasAttribute('tabindex')) {
        target.setAttribute('tabindex', '-1');
      }
      target.focus();
    }
  }

  /** Native <dialog> helper: modal, Esc closes (built in), focus returns to the control that opened it. */
  const dialogs = {
    open: function (dialog, opener) {
      dialog.tkOpener = opener || document.activeElement;
      dialog.showModal();
      focusRegion(dialog);
    },
    close: function (dialog) {
      dialog.close();
    }
  };

  function wireDialogs() {
    document.addEventListener('click', function (event) {
      const opener = event.target.closest('[data-tk-dialog-open]');
      if (opener) {
        const dialog = document.getElementById(opener.getAttribute('data-tk-dialog-open'));
        if (dialog) {
          // An opener may be a link to the same form as a page, which is what it does without JavaScript.
          event.preventDefault();
          dialogs.open(dialog, opener);
        }
      }
      const closer = event.target.closest('[data-tk-dialog-close]');
      if (closer) {
        dialogs.close(closer.closest('dialog'));
      }
    });
    document.querySelectorAll('dialog').forEach(function (dialog) {
      dialog.addEventListener('close', function () {
        if (dialog.tkOpener && document.body.contains(dialog.tkOpener)) {
          dialog.tkOpener.focus();
        }
      });
    });
  }

  /** htmx contract: 200 swaps; 403/404/409/422 swap the problem or form fragment; 5xx keeps the page. */
  function wireHtmx() {
    document.addEventListener('htmx:configRequest', function (event) {
      const header = meta('tk-csrf-header');
      const token = meta('tk-csrf-token');
      if (header && token) {
        event.detail.headers[header] = token;
      }
    });
    document.addEventListener('htmx:beforeSwap', function (event) {
      const status = event.detail.xhr.status;
      if (status === 403 || status === 404 || status === 409 || status === 422) {
        event.detail.shouldSwap = true;
        event.detail.isError = false;
      } else if (status >= 500) {
        event.detail.shouldSwap = false;
        announce(document.body.getAttribute('data-tk-error-text') || 'Something went wrong. Please try again.');
      }
    });
    document.addEventListener('htmx:afterSwap', function (event) {
      const root = event.detail.target;
      focusRegion(root);
      const message = root.querySelector('[data-tk-announce]');
      announce(message ? message.getAttribute('data-tk-announce') : null);
    });
    document.addEventListener('htmx:afterRequest', resetSessionTimer);
  }

  /** Session-expiry warning (WCAG 2.2.1): warns two minutes before the idle timeout and offers to extend. */
  let sessionTimer = null;

  function resetSessionTimer() {
    const seconds = parseInt(document.body.getAttribute('data-tk-session-seconds') || '1800', 10);
    window.clearTimeout(sessionTimer);
    sessionTimer = window.setTimeout(function () {
      const dialog = document.getElementById('tk-session-dialog');
      if (dialog && !dialog.open) {
        dialogs.open(dialog, document.activeElement);
      }
    }, Math.max(seconds - WARNING_SECONDS, 0) * 1000);
  }

  function wireSession() {
    document.addEventListener('click', function (event) {
      if (!event.target.closest('[data-tk-session-extend]')) {
        return;
      }
      const headers = {};
      const header = meta('tk-csrf-header');
      const token = meta('tk-csrf-token');
      if (header && token) {
        headers[header] = token;
      }
      fetch('/session/extend', { method: 'POST', headers: headers, credentials: 'same-origin' })
        .then(function (response) {
          dialogs.close(document.getElementById('tk-session-dialog'));
          if (response.ok) {
            resetSessionTimer();
            announce(document.body.getAttribute('data-tk-extended-text') || 'Your session was extended.');
          } else {
            window.location.reload();
          }
        });
    });
    resetSessionTimer();
  }

  /** Forms: Ctrl+S (Cmd+S) submits a [data-tk-ctrl-s] form; an error summary takes focus; a toast is announced. */
  function wireForms() {
    document.addEventListener('keydown', function (event) {
      const form = document.querySelector('form[data-tk-ctrl-s]');
      if (form && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        form.requestSubmit();
      }
    });
    const summary = document.querySelector('[data-tk-error-summary]');
    if (summary) {
      summary.focus();
    }
    const toast = document.querySelector('[data-tk-toast]');
    if (toast) {
      announce(toast.textContent.trim());
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    wireForms();
    wireDialogs();
    wireHtmx();
    wireSession();
  });

  window.trailkeep = { announce: announce, dialogs: dialogs };
}());
