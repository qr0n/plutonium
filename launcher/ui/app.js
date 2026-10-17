const { useState, useEffect, useRef, useMemo, useCallback } = React;

/* ---------- Tauri bridge ---------- */
const invoke = window.__TAURI__
  ? window.__TAURI__.core.invoke
  : async (cmd, args) => {
      console.warn(`[dev] invoke('${cmd}', ${JSON.stringify(args)})`);
      if (cmd === 'load_settings') return { username: 'Notch_Jr', ram: 4, window_mode: 'Windowed', discord_rpc: true, close_on_launch: false };
      if (cmd === 'load_accounts') return ['Notch_Jr', 'Steve', 'Herobrine'];
      if (cmd === 'is_game_running') return null;
      return null;
    };

/* Progress of the download/install steps before launch ({stage, done, total}). */
function useLaunchProgress() {
  const [progress, setProgress] = useState(null);
  useEffect(() => {
    if (!window.__TAURI__) return;
    const unlisten = window.__TAURI__.event.listen('launcher-progress', (e) => setProgress(e.payload));
    return () => { unlisten.then((f) => f()); };
  }, []);
  return [progress, setProgress];
}

/* ---------- data ---------- */
const CLIENT_VERSION = '1.8.9';

const NEWS = [
  { tag: 'CLIENT', date: 'May 22', title: 'Plutonium b3.2 — FPS pass',
    body: 'Reworked the chunk renderer for stable frame times during fast strafes. Particle batching now uses a single draw call. Average frame budget down ~2.4ms on mid-range hardware.' },
  { tag: 'MODULES', date: 'May 14', title: 'New HUD modules: CPS, Reach, Ping',
    body: 'Three new toggleable modules join the HUD editor. Drag to position, scale to taste, and theme them with the existing accent picker.' },
  { tag: 'NOTE', date: 'May 02', title: 'Cosmetics are now offline-friendly',
    body: 'Plutonium no longer phones home to render capes and bandanas. Your loadout lives in the client and applies whether you\'re online or not.' },
];

/* ---------- helpers ---------- */

function PixelHead({ name = 'Steve', size = 64 }) {
  const hash = useMemo(() => {
    let h = 2166136261;
    for (let i = 0; i < name.length; i++) { h ^= name.charCodeAt(i); h = Math.imul(h, 16777619); }
    return Math.abs(h);
  }, [name]);
  const hue = hash % 360;
  const skin = `hsl(${(hue + 30) % 360} 55% 62%)`;
  const skinD = `hsl(${(hue + 30) % 360} 55% 48%)`;
  const hair = `hsl(${hue} 40% 22%)`;
  const eye = '#1a1a2e';
  const grid = [
    'HHHHHHHH',
    'HHHHHHHH',
    'SSSSSSSS',
    'SeSSSSeS',
    'SSSSSSSS',
    'SSSMMSSS',
    'DDDDDDDD',
    'DDDDDDDD',
  ];
  const px = size / 8;
  const colorFor = (c) => ({ H: hair, S: skin, e: eye, M: skinD, D: skinD }[c]);
  return (
    <div style={{
      width: size, height: size, display: 'grid',
      gridTemplateColumns: `repeat(8, ${px}px)`,
      gridTemplateRows: `repeat(8, ${px}px)`,
      imageRendering: 'pixelated',
      borderRadius: 4,
      overflow: 'hidden',
      boxShadow: '0 4px 18px rgba(0,0,0,0.4), inset 0 0 0 1px rgba(255,255,255,0.05)',
    }}>
      {grid.flatMap((row, r) => row.split('').map((c, i) => (
        <div key={`${r}-${i}`} style={{ background: colorFor(c) }} />
      )))}
    </div>
  );
}

const Icon = {
  home: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z"/></svg>,
  cubes: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M12 3 4 7v8l8 4 8-4V7zM4 7l8 4 8-4M12 11v10"/></svg>,
  mods: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7"/></svg>,
  skins: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M4 8l4-4h8l4 4-3 2v10H7V10z"/></svg>,
  server: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="3" y="4" width="18" height="6" rx="1"/><rect x="3" y="14" width="18" height="6" rx="1"/><circle cx="7" cy="7" r=".8" fill="currentColor"/><circle cx="7" cy="17" r=".8" fill="currentColor"/></svg>,
  settings: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v.1a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>,
  play: <svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>,
  edit: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M12 20h9M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>,
  check: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M5 12l5 5L20 7"/></svg>,
  close: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M6 6l12 12M18 6L6 18"/></svg>,
  chevron: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M6 9l6 6 6-6"/></svg>,
  plus: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M12 5v14M5 12h14"/></svg>,
  ram: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="2" y="8" width="20" height="9" rx="1"/><path d="M6 17v2M10 17v2M14 17v2M18 17v2M7 11h10M7 14h10"/></svg>,
  refresh: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"/></svg>,
  warn: <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M12 9v4M12 17h.01M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/></svg>,
};

function IconBox({ glyph, size = 18 }) {
  return <span style={{ display: 'inline-flex', width: size, height: size }}>{React.cloneElement(glyph, { width: size, height: size })}</span>;
}

/* ---------- settings hook ---------- */
function useSettings() {
  const [settings, setSettingsState] = useState(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    invoke('load_settings').then((s) => {
      setSettingsState(s);
      setLoaded(true);
    }).catch(() => {
      setSettingsState({ username: 'Player', ram: 4, window_mode: 'Windowed', discord_rpc: true, close_on_launch: false });
      setLoaded(true);
    });
  }, []);

  const update = useCallback((patch) => {
    setSettingsState((prev) => {
      const next = { ...prev, ...patch };
      invoke('save_settings', { settings: next }).catch(console.error);
      return next;
    });
  }, []);

  return { settings, loaded, update };
}

/* ---------- accounts hook ---------- */
function useAccounts() {
  const [accounts, setAccounts] = useState([]);

  useEffect(() => {
    invoke('load_accounts').then((list) => {
      setAccounts(Array.isArray(list) ? list : []);
    }).catch(() => setAccounts([]));
  }, []);

  const persist = useCallback((list) => {
    invoke('save_accounts', { accounts: list }).catch(console.error);
  }, []);

  const add = useCallback((name) => {
    name = name.trim();
    setAccounts((prev) => {
      if (prev.includes(name)) return prev;
      const next = [...prev, name];
      persist(next);
      return next;
    });
  }, [persist]);

  const remove = useCallback((name) => {
    setAccounts((prev) => {
      const next = prev.filter((n) => n !== name);
      persist(next);
      return next;
    });
  }, [persist]);

  return { accounts, add, remove };
}

/* ---------- top-level app ---------- */
function App() {
  const { settings, loaded, update } = useSettings();
  const { accounts, add: addAccount, remove: removeAccount } = useAccounts();
  const [tab, setTab] = useState('home');
  const [profileOpen, setProfileOpen] = useState(false);
  const [launchState, setLaunchState] = useState('idle'); // idle | launching | running | error
  const [progress, setProgress] = useLaunchProgress();
  const [gamePid, setGamePid] = useState(null);
  const [error, setError] = useState(null);

  // Poll game status while running
  useEffect(() => {
    if (launchState !== 'running') return;
    const id = setInterval(() => {
      invoke('is_game_running').then((pid) => {
        if (pid === null) {
          setLaunchState('idle');
          setGamePid(null);
        }
      }).catch(() => {});
    }, 2000);
    return () => clearInterval(id);
  }, [launchState]);

  const handleLaunch = useCallback(async () => {
    if (!settings) return;
    setError(null);
    setProgress(null);
    setLaunchState('launching');
    try {
      const pid = await invoke('launch_game');
      setGamePid(pid);
      setLaunchState('running');
      if (settings.close_on_launch) {
        invoke('close_launcher');
      }
    } catch (e) {
      setError(typeof e === 'string' ? e : e.message || 'Launch failed');
      setLaunchState('error');
      setTimeout(() => setLaunchState('idle'), 8000);
    }
  }, [settings]);

  const handleStop = useCallback(async () => {
    try {
      await invoke('stop_game');
    } catch (e) {
      console.error('stop_game error:', e);
    }
    setLaunchState('idle');
    setGamePid(null);
  }, []);

  if (!loaded) {
    return (
      <div style={layoutStyles.shell}>
        <BackgroundFX />
        <div style={{ ...layoutStyles.window, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span className="pixel" style={{ fontSize: 16, color: 'var(--violet-soft)' }}>Loading…</span>
        </div>
      </div>
    );
  }

  return (
    <div style={layoutStyles.shell}>
      <BackgroundFX />

      <div style={layoutStyles.window}>
        <TitleBar username={settings.username} onProfile={() => setProfileOpen(true)} />

        <div style={layoutStyles.body}>
          <Sidebar tab={tab} setTab={setTab} />
          <main style={layoutStyles.main} className="scroll">
            {tab === 'home' && (
              <HomeView
                username={settings.username}
                onLaunch={handleLaunch}
                launchState={launchState}
                progress={progress}
                gamePid={gamePid}
                error={error}
                onStop={handleStop}
                ram={settings.ram}
                onChangeName={() => setProfileOpen(true)}
              />
            )}
            {tab === 'settings' && (
              <SettingsView
                ram={settings.ram}
                setRam={(v) => update({ ram: v })}
                windowMode={settings.window_mode}
                setWindowMode={(v) => update({ window_mode: v })}
                discordRpc={settings.discord_rpc}
                setDiscordRpc={(v) => update({ discord_rpc: v })}
                closeOnLaunch={settings.close_on_launch}
                setCloseOnLaunch={(v) => update({ close_on_launch: v })}
              />
            )}
          </main>
        </div>
      </div>

      {profileOpen && (
        <AccountsDialog
          username={settings.username}
          accounts={accounts}
          onClose={() => setProfileOpen(false)}
          onSelectAccount={(name) => {
            addAccount(name);
            update({ username: name });
            setProfileOpen(false);
          }}
          onAddAccount={(name) => {
            addAccount(name);
            update({ username: name });
          }}
          onRemoveAccount={(name) => {
            removeAccount(name);
            if (name === settings.username && accounts.length > 1) {
              const next = accounts.filter((n) => n !== name)[0];
              update({ username: next });
            }
          }}
        />
      )}
    </div>
  );
}

/* ---------- background ---------- */
function BackgroundFX() {
  return (
    <div aria-hidden="true" style={{
      position: 'fixed', inset: 0, zIndex: 0, pointerEvents: 'none',
      background:
        'radial-gradient(1000px 700px at 78% 38%, rgba(96, 80, 220, 0.28), transparent 60%),' +
        'radial-gradient(800px 600px at 12% 84%, rgba(60, 40, 160, 0.22), transparent 65%),' +
        'linear-gradient(180deg, #060611 0%, #07071a 50%, #050510 100%)',
    }}>
      <div style={{
        position: 'absolute', inset: 0,
        backgroundImage:
          'linear-gradient(rgba(138,123,255,0.04) 1px, transparent 1px),' +
          'linear-gradient(90deg, rgba(138,123,255,0.04) 1px, transparent 1px)',
        backgroundSize: '40px 40px',
        maskImage: 'radial-gradient(ellipse at 50% 50%, black 30%, transparent 75%)',
      }} />
      <div style={{
        position: 'absolute', inset: 0,
        opacity: 0.06, mixBlendMode: 'overlay',
        backgroundImage:
          "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2' stitchTiles='stitch'/></filter><rect width='100%' height='100%' filter='url(%23n)' opacity='0.7'/></svg>\")",
      }} />
    </div>
  );
}

/* ---------- title bar ---------- */
function TitleBar({ username, onProfile }) {
  return (
    <header style={titleStyles.bar} data-tauri-drag-region>
      <div style={titleStyles.brand} data-tauri-drag-region>
        <img src="assets/plutonium-logo.png" alt="PLUTONIUM"
             style={{ height: 18, imageRendering: 'pixelated', filter: 'drop-shadow(0 0 12px rgba(138,123,255,0.45))' }} />
        <span className="mono" style={titleStyles.version}>v3.2.1</span>
      </div>

      <div style={titleStyles.status}>
        <span style={titleStyles.dot} /> <span className="mono" style={{ fontSize: 11, color: 'var(--ink-dim)' }}>offline mode · ready</span>
      </div>

      <div style={titleStyles.right} data-tauri-drag-region>
        <button onClick={onProfile} style={titleStyles.profile} title="Change username">
          <PixelHead name={username} size={26} />
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 1 }}>
            <span style={{ fontSize: 13, fontWeight: 600 }}>{username}</span>
            <span className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)' }}>OFFLINE · CLICK TO EDIT</span>
          </div>
        </button>
      </div>
    </header>
  );
}

/* ---------- sidebar ---------- */
function Sidebar({ tab, setTab }) {
  const items = [
    { id: 'home', label: 'Home', glyph: Icon.home },
  ];
  return (
    <aside style={sideStyles.bar}>
      <nav style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {items.map(it => {
          const on = tab === it.id;
          return (
            <button key={it.id} onClick={() => setTab(it.id)} style={{
              ...sideStyles.item,
              color: on ? '#fff' : 'var(--ink-dim)',
              background: on ? 'linear-gradient(90deg, rgba(138,123,255,0.18), rgba(138,123,255,0.04))' : 'transparent',
              boxShadow: on ? 'inset 2px 0 0 var(--violet)' : 'inset 2px 0 0 transparent',
            }}>
              <IconBox glyph={it.glyph} size={18} />
              <span style={{ fontSize: 13, fontWeight: 500 }}>{it.label}</span>
            </button>
          );
        })}
      </nav>

      <div style={{ flex: 1 }} />

      <button onClick={() => setTab('settings')} style={{
        ...sideStyles.item,
        color: tab === 'settings' ? '#fff' : 'var(--ink-dim)',
        background: tab === 'settings' ? 'linear-gradient(90deg, rgba(138,123,255,0.18), rgba(138,123,255,0.04))' : 'transparent',
      }}>
        <IconBox glyph={Icon.settings} size={18} />
        <span style={{ fontSize: 13, fontWeight: 500 }}>Settings</span>
      </button>

      <div style={sideStyles.footer}>
        <div className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)', textTransform: 'uppercase', letterSpacing: '0.08em' }}>Build</div>
        <div className="mono" style={{ fontSize: 11, color: 'var(--ink-dim)' }}>3.2.1 · stable</div>
      </div>
    </aside>
  );
}

/* ---------- HOME ---------- */
function HomeView({ username, onLaunch, launchState, progress, gamePid, error, onStop, ram, onChangeName }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24, padding: '28px 32px 40px', minHeight: '100%' }}>
      <HeroPanel
        username={username}
        onLaunch={onLaunch}
        launchState={launchState}
        progress={progress}
        gamePid={gamePid}
        error={error}
        onStop={onStop}
        onChangeName={onChangeName}
        ram={ram}
      />

      <Section title="Patch notes" right={<span className="mono" style={{ fontSize: 11, color: 'var(--ink-mute)' }}>{NEWS.length} updates</span>}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 12 }}>
          {NEWS.map((n, i) => <NewsCard key={i} n={n} />)}
        </div>
      </Section>
    </div>
  );
}

function HeroPanel({ username, onLaunch, launchState, progress, gamePid, error, onStop, onChangeName, ram }) {
  const running = launchState === 'running';
  const busy = launchState === 'launching';

  return (
    <div style={heroStyles.wrap}>
      <img src="assets/atom.png" alt=""
        style={heroStyles.atom}
        aria-hidden="true" />

      <div style={heroStyles.inner}>
        <div style={heroStyles.left}>
          <div className="mono" style={{ fontSize: 11, letterSpacing: '0.18em', color: 'var(--violet-soft)', textTransform: 'uppercase' }}>
            {running ? 'Game is running' : 'Ready to launch'}
          </div>
          <h1 className="pixel" style={heroStyles.greet}>
            Welcome back,<br/>
            <button onClick={onChangeName} style={heroStyles.nameBtn}>
              {username}
              <span style={heroStyles.editIcon}><IconBox glyph={Icon.edit} size={14}/></span>
            </button>
          </h1>
          <p style={{ margin: 0, color: 'var(--ink-dim)', fontSize: 14, maxWidth: 460, lineHeight: 1.55 }}>
            A lightweight <b style={{ color: 'var(--ink)' }}>{CLIENT_VERSION}</b> client tuned for PvP — modular HUD, configurable keybinds, and frame-stable rendering on modest hardware.
          </p>

          <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 6 }}>
            <PlayButton
              launchState={launchState}
              onLaunch={onLaunch}
              onStop={onStop}
            />
          </div>

          {busy && <LaunchProgress progress={progress} />}
          {running && (
            <div style={heroStyles.runStrip}>
              <span style={heroStyles.runDot} />
              <span className="mono" style={{ fontSize: 11, color: 'var(--good)' }}>RUNNING · pid {gamePid} · {CLIENT_VERSION}</span>
              <span style={{ flex: 1 }} />
              <button onClick={onStop} style={heroStyles.stopBtn}>Stop</button>
            </div>
          )}
          {launchState === 'error' && error && (
            <div style={heroStyles.errorStrip}>
              <IconBox glyph={Icon.warn} size={14} />
              <span className="mono" style={{ fontSize: 11, color: 'var(--bad)' }}>{error}</span>
            </div>
          )}
        </div>

        <div style={heroStyles.right}>
          <StatTile label="Version" value={CLIENT_VERSION} sub="minecraft" />
          <StatTile label="Status" value={running ? 'LIVE' : 'IDLE'} sub={running ? `pid ${gamePid}` : 'ready'} highlight={running} />
          <StatTile label="Memory" value={`${ram}.0`} sub="GB allocated" />
          <StatTile label="Build" value="3.2.1" sub="stable" />
        </div>
      </div>
    </div>
  );
}

function LaunchProgress({ progress }) {
  const stage = progress ? progress.stage : 'Preparing';
  const counted = progress && progress.total > 0;
  const pct = counted ? Math.round((progress.done / progress.total) * 100) : null;
  return (
    <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 420 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={heroStyles.spinner} />
        <span className="mono" style={{ fontSize: 11, color: 'var(--violet-soft)', letterSpacing: '0.06em' }}>
          {stage}{counted ? ` · ${progress.done} / ${progress.total}` : '…'}
        </span>
      </div>
      {counted && (
        <div style={heroStyles.progressTrack}>
          <div style={{ ...heroStyles.progressFill, width: `${pct}%` }} />
        </div>
      )}
    </div>
  );
}

function PlayButton({ launchState, onLaunch, onStop }) {
  const running = launchState === 'running';
  const busy = launchState === 'launching';

  if (running) {
    return (
      <button onClick={onStop} style={{ ...heroStyles.play, background: 'linear-gradient(180deg, #2a2a52 0%, #1b1b3a 100%)', boxShadow: 'inset 0 0 0 1px var(--line-2)' }}>
        <span className="pixel" style={{ fontSize: 18 }}>PLAYING</span>
        <span className="mono" style={{ fontSize: 11, color: 'var(--ink-dim)', marginTop: 2 }}>{CLIENT_VERSION} · click to stop</span>
      </button>
    );
  }
  if (busy) {
    return (
      <button disabled style={{ ...heroStyles.play, cursor: 'progress', opacity: 0.7 }}>
        <div style={heroStyles.spinner} />
        <span className="pixel" style={{ fontSize: 18 }}>LAUNCHING</span>
      </button>
    );
  }
  if (launchState === 'error') {
    return (
      <button disabled style={{ ...heroStyles.play, background: 'linear-gradient(180deg, #3a1a2a 0%, #2a1020 100%)', cursor: 'not-allowed', opacity: 0.7 }}>
        <span className="pixel" style={{ fontSize: 18 }}>FAILED</span>
      </button>
    );
  }
  return (
    <button onClick={onLaunch} style={heroStyles.play}>
      <IconBox glyph={Icon.play} size={20} />
      <span className="pixel" style={{ fontSize: 18 }}>PLAY</span>
    </button>
  );
}

function StatTile({ label, value, sub, highlight }) {
  return (
    <div style={statStyles.tile}>
      <div className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)', textTransform: 'uppercase', letterSpacing: '0.1em' }}>{label}</div>
      <div className="pixel" style={{ fontSize: 26, color: highlight ? 'var(--good)' : '#fff', marginTop: 6, letterSpacing: '0.02em' }}>{value}</div>
      <div style={{ fontSize: 11, color: 'var(--ink-dim)', marginTop: 4 }}>{sub}</div>
    </div>
  );
}

function Section({ title, right, children }) {
  return (
    <section>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 12 }}>
        <h2 className="pixel" style={{ margin: 0, fontSize: 13, letterSpacing: '0.14em', color: 'var(--violet-soft)', textTransform: 'uppercase' }}>{title}</h2>
        {right}
      </div>
      {children}
    </section>
  );
}

function NewsCard({ n }) {
  return (
    <article style={cardStyles.news}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
        <span className="mono" style={cardStyles.newsTag}>{n.tag}</span>
        <span className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)' }}>{n.date}</span>
      </div>
      <h3 style={{ margin: '0 0 4px', fontSize: 14, fontWeight: 600 }}>{n.title}</h3>
      <p style={{ margin: 0, fontSize: 12, color: 'var(--ink-dim)', lineHeight: 1.55 }}>{n.body}</p>
    </article>
  );
}

/* ---------- SETTINGS ---------- */
function SettingsView({ ram, setRam, windowMode, setWindowMode, discordRpc, setDiscordRpc, closeOnLaunch, setCloseOnLaunch }) {
  return (
    <div style={{ padding: '28px 32px 40px', display: 'flex', flexDirection: 'column', gap: 18, maxWidth: 720 }}>
      <h1 className="pixel" style={{ margin: 0, fontSize: 22 }}>SETTINGS</h1>

      <SettingsCard label="Java heap" sub="Memory allocated to the JVM. 1.8.9 runs comfortably at 2–4 GB.">
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <IconBox glyph={Icon.ram} size={18}/>
          <input type="range" min="1" max="8" value={ram} onChange={(e) => setRam(parseInt(e.target.value))}
                 style={{ flex: 1, accentColor: 'var(--violet)' }} />
          <span className="pixel" style={{ fontSize: 18, minWidth: 60, textAlign: 'right' }}>{ram} GB</span>
        </div>
      </SettingsCard>

      <SettingsCard label="Window mode" sub="How the client should open on launch.">
        <Segmented options={['Windowed', 'Borderless', 'Fullscreen']} value={windowMode} onChange={setWindowMode} />
      </SettingsCard>

      <SettingsCard label="Discord Rich Presence" sub="Show your current server and PvP stats to Discord friends.">
        <Toggle on={discordRpc} onChange={setDiscordRpc} />
      </SettingsCard>

      <SettingsCard label="Close launcher on game start" sub="Frees up a bit of RAM — but you'll have to reopen this window to play again.">
        <Toggle on={closeOnLaunch} onChange={setCloseOnLaunch} />
      </SettingsCard>
    </div>
  );
}

function SettingsCard({ label, sub, children }) {
  return (
    <div style={{ background: 'var(--bg-1)', border: '1px solid var(--line)', borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600 }}>{label}</div>
          <div style={{ fontSize: 12, color: 'var(--ink-dim)', marginTop: 4 }}>{sub}</div>
        </div>
        <div>{children}</div>
      </div>
    </div>
  );
}

function Segmented({ options, value, onChange }) {
  return (
    <div style={{ display: 'inline-flex', background: 'var(--bg-2)', border: '1px solid var(--line)', borderRadius: 8, padding: 3 }}>
      {options.map(o => (
        <button key={o} onClick={() => onChange(o)} style={{
          padding: '6px 12px', border: 0, borderRadius: 6, fontSize: 12, fontWeight: 500,
          background: value === o ? 'var(--violet-2)' : 'transparent',
          color: value === o ? '#fff' : 'var(--ink-dim)',
          cursor: 'pointer',
        }}>{o}</button>
      ))}
    </div>
  );
}

function Toggle({ on, onChange }) {
  return (
    <button onClick={() => onChange(!on)} style={{
      width: 44, height: 24, borderRadius: 999, border: 0, cursor: 'pointer',
      background: on ? 'var(--violet-2)' : 'var(--bg-3)',
      position: 'relative', transition: 'background .15s',
    }}>
      <span style={{
        position: 'absolute', top: 3, left: on ? 23 : 3, width: 18, height: 18,
        borderRadius: '50%', background: '#fff', transition: 'left .15s',
      }} />
    </button>
  );
}

/* ---------- PROFILE DIALOG ---------- */
function AccountsDialog({ username, accounts, onClose, onSelectAccount, onAddAccount, onRemoveAccount }) {
  const [newName, setNewName] = useState('');
  const inputRef = useRef();

  const valid = (n) => n.length >= 2 && n.length <= 16 && /^[A-Za-z0-9_]+$/.test(n);
  const canAdd = valid(newName.trim()) && !accounts.includes(newName.trim());

  const handleAdd = () => {
    const name = newName.trim();
    if (!canAdd) return;
    onAddAccount(name);
    setNewName('');
    inputRef.current && inputRef.current.focus();
  };

  return (
    <Modal onClose={onClose} width={500}>
      {/* header */}
      <div style={modalStyles.head}>
        <div>
          <h2 className="pixel" style={{ margin: 0, fontSize: 16 }}>ACCOUNTS</h2>
          <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--ink-dim)' }}>Saved cracked accounts — click a row to switch.</p>
        </div>
        <button onClick={onClose} style={modalStyles.x}><IconBox glyph={Icon.close} size={16}/></button>
      </div>

      {/* account list */}
      <div style={{ padding: '4px 16px 8px', maxHeight: 300, overflowY: 'auto' }} className="scroll">
        {accounts.length === 0 ? (
          <div style={{ padding: '28px 0', textAlign: 'center', color: 'var(--ink-mute)', fontSize: 13 }}>
            No saved accounts — add one below.
          </div>
        ) : accounts.map((name) => {
          const active = name === username;
          return (
            <div
              key={name}
              onClick={() => onSelectAccount(name)}
              style={{
                display: 'flex', alignItems: 'center', gap: 12,
                padding: '10px 12px', borderRadius: 8, cursor: 'pointer',
                background: active ? 'rgba(138,123,255,0.12)' : 'transparent',
                border: `1px solid ${active ? 'rgba(138,123,255,0.35)' : 'transparent'}`,
                marginBottom: 4, transition: 'background .12s',
              }}
            >
              <PixelHead name={name} size={38} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14, fontWeight: 600, color: active ? 'var(--violet-soft)' : 'var(--ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {name}
                </div>
                {active && (
                  <div className="mono" style={{ fontSize: 10, color: 'var(--good)', marginTop: 1 }}>● ACTIVE</div>
                )}
              </div>
              {active && <IconBox glyph={Icon.check} size={15} />}
              <button
                onClick={(e) => { e.stopPropagation(); onRemoveAccount(name); }}
                style={{
                  background: 'transparent', border: '1px solid var(--line-2)', borderRadius: 6,
                  color: 'var(--ink-mute)', padding: '4px 9px', cursor: 'pointer', fontSize: 12,
                  flexShrink: 0,
                }}
                title="Remove account"
              >✕</button>
            </div>
          );
        })}
      </div>

      {/* add new account */}
      <div style={{ padding: '14px 22px 18px', borderTop: '1px solid var(--line)', marginTop: 4 }}>
        <div className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)', letterSpacing: '0.12em', textTransform: 'uppercase', marginBottom: 8 }}>
          Add account
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <div style={{ flex: 1, position: 'relative' }}>
            <input
              ref={inputRef}
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleAdd();
                if (e.key === 'Escape') onClose();
              }}
              placeholder="Username (2–16 chars)"
              spellCheck={false}
              maxLength={16}
              style={{
                ...inputStyle,
                width: '100%',
                fontFamily: "'Silkscreen', monospace",
                fontSize: 14,
                padding: '10px 44px 10px 12px',
                borderColor: newName && !valid(newName.trim()) ? 'var(--bad)' : (canAdd ? 'var(--violet)' : 'var(--line-2)'),
                boxShadow: canAdd ? '0 0 0 3px rgba(138,123,255,0.15)' : 'none',
              }}
            />
            <span className="mono" style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', fontSize: 10, color: 'var(--ink-mute)', pointerEvents: 'none' }}>
              {newName.length}/16
            </span>
          </div>
          <button
            disabled={!canAdd}
            onClick={handleAdd}
            style={{ ...primaryBtn, opacity: canAdd ? 1 : 0.4, cursor: canAdd ? 'pointer' : 'not-allowed', padding: '10px 20px', flexShrink: 0 }}
          >
            Add
          </button>
        </div>
      </div>
    </Modal>
  );
}

function Rule({ ok, text }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, color: ok ? 'var(--good)' : 'var(--ink-mute)' }}>
      <span style={{
        width: 14, height: 14, borderRadius: 3,
        background: ok ? 'rgba(122,240,168,0.15)' : 'transparent',
        border: `1px solid ${ok ? 'var(--good)' : 'var(--line-2)'}`,
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      }}>{ok && <IconBox glyph={Icon.check} size={10} />}</span>
      <span>{text}</span>
    </div>
  );
}

/* ---------- MODAL ---------- */
function Modal({ children, onClose, width = 480 }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div onClick={onClose} style={modalStyles.backdrop}>
      <div onClick={(e) => e.stopPropagation()} style={{ ...modalStyles.dialog, width }}>
        {children}
      </div>
    </div>
  );
}

/* ---------- STYLES ---------- */
const layoutStyles = {
  shell: { position: 'relative', width: '100vw', height: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center', padding: 24, zIndex: 1 },
  window: {
    position: 'relative', width: '100%', height: '100%', maxWidth: 1320, maxHeight: 860,
    background: 'linear-gradient(180deg, rgba(15,15,32,0.92) 0%, rgba(10,10,24,0.92) 100%)',
    border: '1px solid var(--line)',
    borderRadius: 16,
    boxShadow: '0 30px 80px rgba(0,0,0,0.55), inset 0 1px 0 rgba(255,255,255,0.04)',
    backdropFilter: 'blur(20px)',
    display: 'flex', flexDirection: 'column',
    overflow: 'hidden',
  },
  body: { display: 'flex', flex: 1, minHeight: 0 },
  main: { flex: 1, overflowY: 'auto', position: 'relative' },
};

const titleStyles = {
  bar: {
    display: 'flex', alignItems: 'center', gap: 20,
    padding: '10px 16px 10px 80px',
    height: 52,
    borderBottom: '1px solid var(--line)',
    background: 'rgba(8,8,20,0.6)',
  },
  brand: { display: 'flex', alignItems: 'center', gap: 12 },
  version: { fontSize: 10, color: 'var(--ink-mute)', letterSpacing: '0.1em', padding: '3px 7px', border: '1px solid var(--line)', borderRadius: 4 },
  status: { display: 'flex', alignItems: 'center', gap: 8 },
  dot: { width: 6, height: 6, borderRadius: '50%', background: 'var(--good)', boxShadow: '0 0 8px var(--good)' },
  right: { marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 14 },
  profile: {
    display: 'flex', alignItems: 'center', gap: 10,
    background: 'rgba(138,123,255,0.06)', border: '1px solid var(--line)',
    borderRadius: 8, padding: '5px 12px 5px 6px', cursor: 'pointer',
  },
  wctrl: { display: 'flex', gap: 6, marginLeft: 4 },
  wbtn: { width: 11, height: 11, borderRadius: '50%' },
};

const sideStyles = {
  bar: {
    width: 200,
    background: 'rgba(8,8,20,0.4)',
    borderRight: '1px solid var(--line)',
    padding: '20px 12px 16px',
    display: 'flex', flexDirection: 'column', gap: 4,
  },
  item: {
    display: 'flex', alignItems: 'center', gap: 12,
    padding: '10px 14px',
    border: 0,
    borderRadius: 8,
    cursor: 'pointer',
    textAlign: 'left',
    transition: 'background .12s',
  },
  footer: {
    marginTop: 16, padding: '12px 14px 0', borderTop: '1px solid var(--line)',
  },
};

const heroStyles = {
  wrap: {
    position: 'relative',
    background: 'linear-gradient(135deg, rgba(28,22,72,0.7) 0%, rgba(14,12,38,0.7) 60%, rgba(8,8,20,0.7) 100%)',
    border: '1px solid var(--line-2)',
    borderRadius: 16,
    overflow: 'hidden',
    boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.04), 0 18px 40px rgba(0,0,0,0.35)',
  },
  atom: {
    position: 'absolute', right: -40, top: -50,
    width: 520, height: 'auto', opacity: 0.55,
    pointerEvents: 'none', userSelect: 'none',
    mixBlendMode: 'screen',
  },
  inner: { position: 'relative', padding: '32px 36px', display: 'grid', gridTemplateColumns: 'minmax(0, 1.4fr) minmax(0, 1fr)', gap: 28, minHeight: 280 },
  left: { display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0 },
  right: { display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 10, alignContent: 'start', minWidth: 0 },
  greet: { margin: 0, fontSize: 30, lineHeight: 1.25, color: '#fff', letterSpacing: '0.02em' },
  nameBtn: {
    background: 'transparent', border: 'none', padding: 0, color: 'var(--violet-soft)', cursor: 'pointer',
    font: 'inherit', display: 'inline-flex', alignItems: 'center', gap: 10,
    textShadow: '0 0 22px rgba(138,123,255,0.5)',
  },
  editIcon: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 22, height: 22, borderRadius: 5, background: 'rgba(138,123,255,0.16)', color: 'var(--violet-soft)',
    border: '1px solid var(--line-2)',
  },
  play: {
    display: 'inline-flex', alignItems: 'center', gap: 12,
    padding: '16px 28px',
    background: 'linear-gradient(180deg, #8a7bff 0%, #5b48f0 100%)',
    color: '#fff', border: 0, borderRadius: 10, cursor: 'pointer',
    boxShadow: '0 8px 24px rgba(91, 72, 240, 0.45), inset 0 1px 0 rgba(255,255,255,0.25), inset 0 -2px 0 rgba(0,0,0,0.25)',
    minWidth: 220, justifyContent: 'center', flexDirection: 'column',
  },
  progressTrack: { width: '100%', height: 6, background: 'var(--bg-2)', borderRadius: 999, overflow: 'hidden', border: '1px solid var(--line)' },
  progressFill: { height: '100%', background: 'linear-gradient(90deg, var(--violet), var(--violet-soft))', boxShadow: '0 0 16px var(--violet-glow)', transition: 'width .2s' },
  runStrip: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
    background: 'rgba(122,240,168,0.06)', border: '1px solid rgba(122,240,168,0.25)',
    borderRadius: 8, marginTop: 6,
  },
  errorStrip: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
    background: 'rgba(255,107,138,0.06)', border: '1px solid rgba(255,107,138,0.25)',
    borderRadius: 8, marginTop: 6,
  },
  runDot: { width: 8, height: 8, borderRadius: '50%', background: 'var(--good)', boxShadow: '0 0 8px var(--good)' },
  stopBtn: { background: 'transparent', border: '1px solid var(--line-2)', borderRadius: 6, color: 'var(--ink)', padding: '4px 12px', fontSize: 11, cursor: 'pointer' },
  spinner: {
    width: 16, height: 16, borderRadius: '50%',
    border: '2px solid var(--line-2)', borderTopColor: 'var(--violet-soft)',
    animation: 'spin 0.8s linear infinite',
  },
};

const statStyles = {
  tile: {
    background: 'rgba(20,20,40,0.6)',
    border: '1px solid var(--line)',
    borderRadius: 10, padding: '14px 16px',
  },
};

const cardStyles = {
  instance: {
    position: 'relative',
    display: 'flex', alignItems: 'center', gap: 14,
    background: 'var(--bg-1)', border: '1px solid var(--line)', borderRadius: 12,
    padding: 14,
    transition: 'border-color .15s, transform .15s',
  },
  tag: { position: 'absolute', top: 8, right: 8, padding: '2px 7px', borderRadius: 4, fontSize: 9, letterSpacing: '0.08em' },
  tile: { width: 56, height: 56, borderRadius: 8, overflow: 'hidden', flexShrink: 0, border: '1px solid var(--line-2)' },
  tilePixels: { width: '100%', height: '100%' },

  news: {
    background: 'var(--bg-1)', border: '1px solid var(--line)', borderRadius: 10, padding: '12px 14px',
  },
  newsTag: {
    fontSize: 9, padding: '2px 6px', borderRadius: 3,
    background: 'rgba(138,123,255,0.14)', color: 'var(--violet-soft)', letterSpacing: '0.1em',
  },

  skin: { background: 'var(--bg-1)', border: '1px solid var(--line)', borderRadius: 12, padding: 12 },
};

const modalStyles = {
  backdrop: { position: 'fixed', inset: 0, background: 'rgba(2,2,8,0.6)', backdropFilter: 'blur(6px)', zIndex: 100, display: 'flex', alignItems: 'center', justifyContent: 'center' },
  dialog: { background: 'linear-gradient(180deg, #14142a 0%, #0b0b1c 100%)', border: '1px solid var(--line-2)', borderRadius: 14, boxShadow: '0 30px 80px rgba(0,0,0,0.6)', maxHeight: '85vh', display: 'flex', flexDirection: 'column' },
  head: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', padding: '20px 22px 16px' },
  x: { background: 'transparent', border: 0, color: 'var(--ink-dim)', cursor: 'pointer', padding: 6 },
  foot: { display: 'flex', justifyContent: 'flex-end', gap: 8, padding: '14px 22px 18px', borderTop: '1px solid var(--line)', marginTop: 8 },
  row: {
    display: 'flex', alignItems: 'center', gap: 12,
    width: '100%', padding: '10px 12px', border: 0, borderRadius: 8,
    color: 'var(--ink)', textAlign: 'left', cursor: 'pointer',
    transition: 'background .12s',
  },
};

const inputStyle = {
  flex: 1,
  background: 'var(--bg-2)',
  border: '1px solid var(--line-2)',
  color: 'var(--ink)',
  borderRadius: 8,
  padding: '8px 12px',
  fontSize: 13,
  outline: 'none',
};

const primaryBtn = {
  background: 'linear-gradient(180deg, #8a7bff 0%, #5b48f0 100%)',
  color: '#fff', border: 0, padding: '8px 16px', borderRadius: 8, cursor: 'pointer',
  fontSize: 13, fontWeight: 600,
  boxShadow: '0 4px 14px rgba(91,72,240,0.35), inset 0 1px 0 rgba(255,255,255,0.2)',
};

const ghostBtn = {
  background: 'transparent', color: 'var(--ink-dim)', border: '1px solid var(--line)',
  padding: '8px 14px', borderRadius: 8, cursor: 'pointer', fontSize: 13,
};

/* ---------- mount ---------- */

// Inject spinner keyframe animation
const styleTag = document.createElement('style');
styleTag.textContent = '@keyframes spin { to { transform: rotate(360deg); } }';
document.head.appendChild(styleTag);

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<App />);
