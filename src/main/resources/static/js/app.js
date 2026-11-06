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

  /**
   * Native <dialog> helper: modal, Esc closes (built in), focus returns to the control that opened it. Focus lands on
   * the dialog's [data-tk-dialog-focus] control if it has one, else on its heading. A dialog the server rendered open
   * (the page without JavaScript) is closed first, so it can be shown modal.
   */
  const dialogs = {
    open: function (dialog, opener) {
      dialog.tkOpener = opener || document.activeElement;
      if (dialog.open) {
        dialog.close();
      }
      dialog.showModal();
      const first = dialog.querySelector('[data-tk-dialog-focus]');
      if (first) {
        first.focus();
      } else {
        focusRegion(dialog);
      }
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
        // A closer may be a link back to the page, which is what it does without JavaScript.
        event.preventDefault();
        dialogs.close(closer.closest('dialog'));
      }
    });
    document.querySelectorAll('dialog').forEach(function (dialog) {
      // The close event arrives as a task after close(). Browsers that restore focus themselves have already put
      // it back on the opener, and the person may have moved on (Tab, Enter) by now: return focus only if it is
      // lost (on the body) or still inside the closed dialog, never pull it away from where the person took it.
      dialog.addEventListener('close', function () {
        const active = document.activeElement;
        const lost = !active || active === document.body || dialog.contains(active);
        if (lost && dialog.tkOpener && document.body.contains(dialog.tkOpener)) {
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
      // A form whose controls sit in a dialog (the cases list's filters) closes it once its results are in, and
      // focus goes to the results rather than back to the dialog's opener.
      const source = event.detail.requestConfig && event.detail.requestConfig.elt;
      const dialog = source instanceof HTMLFormElement ? source.querySelector('dialog[open]') : null;
      if (dialog) {
        dialog.tkOpener = null;
        dialog.close();
      }
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

  /**
   * The cases list: a click on a row opens its case (the case-number link stays the keyboard path), and the "select
   * all on this page" box, hidden without script, ticks or clears every row's box.
   */
  function wireRows() {
    document.addEventListener('click', function (event) {
      const row = event.target.closest('tr[data-tk-href]');
      if (row && !event.target.closest('a, button, input, label, select, textarea')) {
        window.location.assign(row.getAttribute('data-tk-href'));
      }
    });
    document.addEventListener('change', function (event) {
      if (event.target.matches('[data-tk-select-all]')) {
        document.querySelectorAll('input[name="ids"][form="tk-export"]').forEach(function (box) {
          box.checked = event.target.checked;
        });
      }
    });
    const reveal = function () {
      document.querySelectorAll('[data-tk-select-all]').forEach(function (box) { box.hidden = false; });
    };
    reveal();
    document.addEventListener('htmx:afterSettle', reveal);
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

  /**
   * ARIA APG "Combobox with list autocomplete" over a native <select data-tk-combobox> (the Request update recipient,
   * BUILD_PLAN §10.3): a text input with role=combobox filters a listbox of the select's options as the person types;
   * Down and Up move through the shown options (aria-activedescendant), Enter or a click chooses one, Esc closes the
   * list. The select stays in the form, hidden, and carries the chosen value; without JavaScript it is the control.
   */
  function wireComboboxes() {
    document.querySelectorAll('select[data-tk-combobox]').forEach(function (select) {
      const input = document.createElement('input');
      const list = document.createElement('ul');
      const label = document.querySelector('label[for="' + select.id + '"]');
      const options = Array.from(select.options).filter(function (option) { return option.value; });
      let active = -1;
      input.type = 'text';
      input.id = select.id + '-input';
      input.className = 'form-control';
      input.autocomplete = 'off';
      input.setAttribute('role', 'combobox');
      input.setAttribute('aria-autocomplete', 'list');
      input.setAttribute('aria-expanded', 'false');
      input.setAttribute('aria-controls', select.id + '-listbox');
      input.setAttribute('aria-required', 'true');
      input.setAttribute('aria-describedby', select.getAttribute('aria-describedby') || '');
      if (select.hasAttribute('data-tk-dialog-focus')) {
        input.setAttribute('data-tk-dialog-focus', 'true');
        select.removeAttribute('data-tk-dialog-focus');
      }
      list.id = select.id + '-listbox';
      list.className = 'tk-listbox';
      list.setAttribute('role', 'listbox');
      list.hidden = true;
      if (label) {
        label.id = label.id || select.id + '-label';
        label.htmlFor = input.id;
        list.setAttribute('aria-labelledby', label.id);
      }
      options.forEach(function (option, index) {
        const item = document.createElement('li');
        item.id = list.id + '-' + index;
        item.setAttribute('role', 'option');
        item.setAttribute('aria-selected', 'false');
        item.setAttribute('data-value', option.value);
        item.textContent = option.text;
        list.appendChild(item);
      });
      if (select.selectedIndex > 0) {
        input.value = select.options[select.selectedIndex].text;
      }
      // The hidden select is no longer what the person fills in; the server checks the choice (422).
      select.required = false;
      select.hidden = true;
      select.after(input, list);
      // An error summary's link to the select now leads to the combobox.
      document.querySelectorAll('a[href="#' + select.id + '"]').forEach(function (link) {
        link.setAttribute('href', '#' + input.id);
      });

      const shown = function () {
        return Array.from(list.children).filter(function (item) { return !item.hidden; });
      };
      const setActive = function (index) {
        const items = shown();
        Array.from(list.children).forEach(function (item) { item.setAttribute('aria-selected', 'false'); });
        active = index;
        if (index >= 0 && items[index]) {
          items[index].setAttribute('aria-selected', 'true');
          input.setAttribute('aria-activedescendant', items[index].id);
          items[index].scrollIntoView({ block: 'nearest' });
        } else {
          input.removeAttribute('aria-activedescendant');
        }
      };
      const expand = function (open) {
        list.hidden = !open;
        input.setAttribute('aria-expanded', String(open));
        if (!open) {
          setActive(-1);
        }
      };
      const filter = function () {
        const text = input.value.trim().toLowerCase();
        Array.from(list.children).forEach(function (item) {
          item.hidden = !item.textContent.toLowerCase().includes(text);
        });
        const exact = options.find(function (option) { return option.text.toLowerCase() === text; });
        select.value = exact ? exact.value : '';
      };
      const choose = function (item) {
        input.value = item.textContent;
        select.value = item.getAttribute('data-value');
        expand(false);
      };
      input.addEventListener('input', function () {
        filter();
        expand(shown().length > 0);
      });
      input.addEventListener('keydown', function (event) {
        const items = shown();
        if (event.key === 'ArrowDown') {
          event.preventDefault();
          if (list.hidden) {
            filter();
            expand(shown().length > 0);
          }
          setActive(Math.min(active + 1, shown().length - 1));
        } else if (event.key === 'ArrowUp' && !list.hidden) {
          event.preventDefault();
          setActive(Math.max(active - 1, 0));
        } else if (event.key === 'Enter' && !list.hidden && items[active]) {
          event.preventDefault();
          choose(items[active]);
        } else if (event.key === 'Escape' && !list.hidden) {
          // Closes the list, not the dialog around it.
          event.preventDefault();
          event.stopPropagation();
          expand(false);
        }
      });
      input.addEventListener('blur', function () { expand(false); });
      list.addEventListener('mousedown', function (event) { event.preventDefault(); });
      list.addEventListener('click', function (event) {
        const item = event.target.closest('[role="option"]');
        if (item) {
          choose(item);
          input.focus();
        }
      });
    });
  }

  /**
   * A visible character count under a textarea[data-tk-count][maxlength] ("12 of 5000 characters"), from the
   * attribute's text with {0} and {1}. The limit is also in the field's hint and enforced by maxlength, so the count
   * is a visual aid and stays out of the accessibility tree rather than announcing every keystroke.
   */
  function wireCounters() {
    document.querySelectorAll('textarea[data-tk-count][maxlength]').forEach(function (area) {
      const count = document.createElement('div');
      count.className = 'form-text';
      count.setAttribute('aria-hidden', 'true');
      const update = function () {
        count.textContent = area.getAttribute('data-tk-count')
          .replace('{0}', String(area.value.replace(/\r\n/g, '\n').length))
          .replace('{1}', area.getAttribute('maxlength'));
      };
      area.after(count);
      area.addEventListener('input', update);
      update();
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    wireCounters();
    wireComboboxes();
    wireForms();
    wireRows();
    wireDialogs();
    wireHtmx();
    wireSession();
  });

  window.trailkeep = { announce: announce, dialogs: dialogs };
}());
