const notes = venjs.state([]);
const title = venjs.state('');
const body = venjs.state('');
const remindSeconds = venjs.state('10');
const saving = venjs.state(false);
const status = venjs.state('');
const attachLocation = venjs.state(false);
const backups = venjs.state([]);

const NOTES_FILE = 'notes.json';

const cx = (...parts) => parts.filter(Boolean).join(' ').trim();

const nowLabel = () => {
  try {
    return new Date().toLocaleString();
  } catch (_err) {
    return '';
  }
};

const parseNotes = (raw) => {
  try {
    const data = JSON.parse(String(raw || '[]'));
    return Array.isArray(data) ? data : [];
  } catch (_err) {
    return [];
  }
};

const sortNotes = (items) => (items || [])
  .slice()
  .sort((a, b) => {
    const ap = a && a.pinned ? 1 : 0;
    const bp = b && b.pinned ? 1 : 0;
    if (ap !== bp) return bp - ap;
    return Number((b && b.createdAt) || 0) - Number((a && a.createdAt) || 0);
  });

const loadNotes = async () => {
  try {
    const res = await venjs.readFile({ name: NOTES_FILE });
    notes.set(sortNotes(parseNotes(res && res.read)));
  } catch (_err) {
    try {
      await venjs.createFile({ name: NOTES_FILE, write: '[]' });
    } catch (_err2) {}
    notes.set([]);
  }
};

const persistNotes = async (next) => {
  saving.set(true);
  try {
    const normalized = sortNotes(next || []);
    notes.set(normalized);
    await venjs.writeFile({ name: NOTES_FILE, write: JSON.stringify(normalized) });
  } finally {
    saving.set(false);
  }
};

const refreshBackups = async () => {
  try {
    const { files } = await venjs.listFiles();
    const list = Array.isArray(files) ? files : [];
    backups.set(list.filter((name) => String(name || '').startsWith('backup-')).slice(0, 20));
  } catch (_err) {
    backups.set([]);
  }
};

const backupNow = async () => {
  const name = `backup-${Date.now()}.json`;
  try {
    await venjs.createFile({ name, write: JSON.stringify(notes.get() || []), overwrite: true });
    status.set(`Backup created: ${name}`);
  } catch (e) {
    status.set(`Backup error: ${e.message || e}`);
  } finally {
    await refreshBackups();
  }
};

const loadBackup = async (name) => {
  try {
    const res = await venjs.readFile({ name });
    const next = sortNotes(parseNotes(res && res.read));
    status.set(`Loaded: ${name}`);
    await persistNotes(next);
  } catch (e) {
    status.set(`Load error: ${e.message || e}`);
  }
};

const addNote = async (preset = {}) => {
  const t = String(preset.title ?? title.get() ?? '').trim();
  const b = String(preset.body ?? body.get() ?? '').trim();
  if (!t && !b) {
    status.set('Add a title or body.');
    return;
  }

  const createdAt = Date.now();
  const note = {
    id: String(createdAt),
    title: t || 'Untitled',
    body: b,
    pinned: false,
    createdAt,
    createdLabel: nowLabel()
  };

  const shouldAttach = Boolean(preset.attachLocation ?? attachLocation.get());
  if (shouldAttach) {
    try {
      status.set('Getting location...');
      const loc = await venjs.getLocation({ enableHighAccuracy: false, timeoutMs: 10000 });
      if (loc && typeof loc === 'object') {
        note.location = {
          latitude: Number(loc.latitude),
          longitude: Number(loc.longitude),
          accuracy: Number(loc.accuracy)
        };
      }
    } catch (_err) {}
  }

  const next = sortNotes([note, ...(notes.get() || [])]);
  title.set('');
  body.set('');
  status.set('Saved.');
  await persistNotes(next);
};

const addQuickNote = async () => addNote({
  title: 'Quick note',
  body: `Shaken at ${nowLabel()}`,
  attachLocation: false
});

const togglePinned = async (id) => {
  const next = sortNotes((notes.get() || []).map((n) => {
    if (!n || n.id !== id) return n;
    return { ...n, pinned: !n.pinned };
  }));
  await persistNotes(next);
  status.set('Updated.');
};

const removeNote = async (id) => {
  const next = (notes.get() || []).filter((n) => n && n.id !== id);
  status.set('Deleted.');
  await persistNotes(next);
};

const scheduleReminder = async (note) => {
  if (!venjs.notifications) {
    status.set('Notifications not available.');
    return;
  }

  const seconds = Math.max(1, Number(remindSeconds.get() || 10) || 10);
  try {
    const perm = await venjs.notifications.requestPermission();
    if (perm && perm.granted === false) {
      status.set('Notification permission denied.');
      return;
    }
    const res = await venjs.notifications.scheduleLocal({
      id: `note-${note.id}`,
      title: note.title || 'Reminder',
      body: note.body || 'Open the app to view.',
      delayMs: seconds * 1000,
      data: { noteId: note.id }
    });
    status.set(res && res.exact === false
      ? `Reminder scheduled (~${seconds}s, may be delayed by Android).`
      : `Reminder set for ${seconds}s.`);
  } catch (e) {
    status.set(`Reminder error: ${e.message || e}`);
  }
};

const exportToText = async () => {
  const items = notes.get() || [];
  const text = items
    .map((n) => {
      const header = `${n.pinned ? '[PINNED] ' : ''}${n.title || 'Untitled'} (${n.createdLabel || ''})`;
      const loc = n.location && Number.isFinite(n.location.latitude) && Number.isFinite(n.location.longitude)
        ? `Location: ${n.location.latitude}, ${n.location.longitude} (+/- ${n.location.accuracy || '?'}m)`
        : '';
      const bodyText = n.body ? String(n.body) : '';
      return [header, loc, bodyText].filter(Boolean).join('\n');
    })
    .join('\n\n---\n\n');

  try {
    await venjs.createFile({ name: 'vennotes-export.txt', write: text, overwrite: true });
    status.set('Exported: vennotes-export.txt');
  } catch (e) {
    status.set(`Export error: ${e.message || e}`);
  }
};

const Btn = (textContent, onClick, variant, extraClass) =>
  venjs.button({
    textContent,
    onClick,
    className: cx('btn', variant ? `btn--${variant}` : '', extraClass || '')
  });

const Pill = (label, active, onClick) =>
  venjs.div({ onClick, className: cx('pill', active ? 'pill--active' : '') }, [
    venjs.text({ textContent: label, className: cx('pillText', active ? 'pillText--active' : '') })
  ]);

const NoteCard = (n) =>
  venjs.div({ onDoubleTap: () => togglePinned(n.id), className: 'noteCard stretch' }, [
    venjs.div({ className: 'row row--tight' }, [
      venjs.text({ textContent: n.title || 'Untitled', className: 'noteTitle' }),
      n.pinned ? venjs.text({ textContent: 'PINNED', className: 'badge' }) : null
    ]),
    n.createdLabel ? venjs.text({ textContent: n.createdLabel, className: 'meta' }) : null,
    n.location && Number.isFinite(n.location.latitude) && Number.isFinite(n.location.longitude)
      ? venjs.text({ textContent: `Location: ${n.location.latitude}, ${n.location.longitude}`, className: 'meta' })
      : null,
    n.body ? venjs.text({ textContent: n.body, className: 'noteBody' }) : null,
    venjs.div({ className: 'row' }, [
      Btn('Remind', () => scheduleReminder(n), 'success', 'btn--inline'),
      Btn('Delete', () => removeNote(n.id), 'danger', 'btn--inline')
    ])
  ]);

const App = () => {
  const list = notes.get() || [];
  const backupList = backups.get() || [];

  return venjs.div({ className: 'screen stretch' }, [
    venjs.div({ className: 'header' }, [
      venjs.text({ textContent: 'VenNotes', className: 'title' }),
      venjs.text({
        textContent: saving.get()
          ? 'Saving...'
          : (status.get() || 'Shake to quick-add. Double-tap a note to pin/unpin.'),
        className: 'subtitle'
      })
    ]),

    venjs.div({ className: 'card stretch' }, [
      venjs.input({
        placeholder: 'Title',
        value: title.get(),
        className: 'field',
        onChange: (p) => title.set(p.value || '')
      }),
      venjs.input({
        placeholder: 'Write a note...',
        value: body.get(),
        className: 'field field--body',
        onChange: (p) => body.set(p.value || '')
      }),
      venjs.div({ className: 'row' }, [
        Pill('Attach location', Boolean(attachLocation.get()), () => attachLocation.set((v) => !v)),
        Btn('Add note', () => addNote(), 'primary', 'btn--inline')
      ])
    ]),

    venjs.div({ className: 'card stretch' }, [
      venjs.div({ className: 'row' }, [
        venjs.text({ textContent: 'Reminder (sec):', className: 'label' }),
        venjs.input({
          placeholder: '10',
          value: remindSeconds.get(),
          className: 'field field--small',
          onChange: (p) => remindSeconds.set(p.value || '')
        }),
        Btn('Reload', () => { loadNotes(); refreshBackups(); }, 'dark', 'btn--inline')
      ]),
      venjs.div({ className: 'row row--tight' }, [
        Btn('Backup', backupNow, 'purple', 'btn--inline'),
        Btn('Export', exportToText, 'teal', 'btn--inline')
      ])
    ]),

    venjs.text({
      textContent: list.length === 0 ? 'No notes yet.' : `Notes (${list.length})`,
      className: 'sectionLabel'
    }),

    ...(list.length === 0 ? [] : list.map(NoteCard)),

    venjs.div({ className: 'footer stretch' }, [
      venjs.text({ textContent: 'Backups', className: 'sectionLabel' }),
      ...(backupList.length === 0
        ? [venjs.text({ textContent: 'No backups yet.', className: 'muted' })]
        : backupList.map((name) =>
          venjs.div({ onClick: () => loadBackup(name), className: 'backupItem' }, [
            venjs.text({ textContent: name, className: 'backupText' })
          ])
        ))
    ])
  ]);
};

try {
  if (typeof window !== 'undefined') {
    if (window.__vennotes_stopShake && typeof window.__vennotes_stopShake === 'function') {
      window.__vennotes_stopShake();
    }
    window.__vennotes_stopShake = venjs.onShake(() => {
      addQuickNote().catch(() => {});
    });
  }
} catch (_err) {}

try {
  if (venjs.notifications) {
    if (typeof window !== 'undefined') {
      if (window.__vennotes_stopTap && typeof window.__vennotes_stopTap === 'function') {
        window.__vennotes_stopTap();
      }
      window.__vennotes_stopTap = venjs.notifications.onTap((n) => {
        const id = n && (n.noteId || (n.data && n.data.noteId));
        if (id) status.set(`Notification tapped for note ${id}`);
      });
    }
  }
} catch (_err) {}

loadNotes().then(refreshBackups).catch(() => {});
venjs.mount(App);
