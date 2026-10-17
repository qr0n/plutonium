const { useState, useEffect, useRef, useMemo } = React;

/* ---------- data ---------- */
const CLIENT_VERSION = '1.8.9';

const NEWS = [
  { tag: 'CLIENT', date: 'May 22', title: 'Plutonium b3.2 — FPS pass',
    body: 'Reworked the chunk renderer for stable frame times during fast strafes. Particle batching now uses a single draw call. Average frame budget down ~2.4ms on mid-range hardware.' },
  { tag: 'MODULES', date: 'May 14', title: 'New HUD modules: CPS, Reach, Ping',
    body: 'Three new toggleable modules join the HUD editor. Drag to position, scale to taste, and theme them with the existing accent picker.' },
  { tag: 'NOTE', date: 'May 02', title: 'Cosmetics are now offline-friendly',
    body: 'Plutonium no longer phones home to render capes and bandanas. Your loadout lives in the client and applies whether you’re online or not.' },
];

/* ---------- helpers ---------- */

/* a procedural pixel "skin head" avatar (no minecraft sprites) */
function PixelHead({ name = 'Steve', size = 64 }) {
  // hash the name → deterministic palette
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
  // 8x8 face grid
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

/* tiny inline icons (no external libs) */
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
};

function IconBox({ glyph, size = 18 }) {
  return <span style={{ display: 'inline-flex', width: size, height: size }}>{React.cloneElement(glyph, { width: size, height: size })}</span>;
}

/* ---------- top-level app ---------- */
function App() {
  const [username, setUsername] = useState('Notch_Jr');
  const [tab, setTab] = useState('home');
  const [profileOpen, setProfileOpen] = useState(false);
  const [launchState, setLaunchState] = useState('idle'); // idle | preparing | running
  const [progress, setProgress] = useState(0);
  const [ram, setRam] = useState(4);

  // launch flow
  useEffect(() => {
    if (launchState !== 'preparing') return;
    setProgress(0);
    let p = 0;
    const id = setInterval(() => {
      p += Math.random() * 9 + 3;
      if (p >= 100) {
        p = 100;
        setProgress(100);
        clearInterval(id);
        setTimeout(() => setLaunchState('running'), 400);
      } else {
        setProgress(p);
      }
    }, 220);
    return () => clearInterval(id);
  }, [launchState]);

  return (
    <div style={layoutStyles.shell}>
      <BackgroundFX />

      <div style={layoutStyles.window}>
        <TitleBar username={username} onProfile={() => setProfileOpen(true)} />

        <div style={layoutStyles.body}>
          <Sidebar tab={tab} setTab={setTab} />
          <main style={layoutStyles.main} className="scroll">
            {tab === 'home' && (
              <HomeView
                username={username}
                onLaunch={() => setLaunchState('preparing')}
                launchState={launchState}
                progress={progress}
                onStop={() => { setLaunchState('idle'); setProgress(0); }}
                ram={ram}
                onChangeName={() => setProfileOpen(true)}
              />
            )}
            {tab === 'settings' && <SettingsView ram={ram} setRam={setRam} />}
          </main>
        </div>
      </div>

      {profileOpen && (
        <ProfileDialog
          username={username}
          onClose={() => setProfileOpen(false)}
          onSave={(name) => { setUsername(name); setProfileOpen(false); }}
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
      {/* subtle grid */}
      <div style={{
        position: 'absolute', inset: 0,
        backgroundImage:
          'linear-gradient(rgba(138,123,255,0.04) 1px, transparent 1px),' +
          'linear-gradient(90deg, rgba(138,123,255,0.04) 1px, transparent 1px)',
        backgroundSize: '40px 40px',
        maskImage: 'radial-gradient(ellipse at 50% 50%, black 30%, transparent 75%)',
      }} />
      {/* grain */}
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
    <header style={titleStyles.bar}>
      <div style={titleStyles.brand}>
        <img src="assets/plutonium-logo.png" alt="PLUTONIUM"
             style={{ height: 18, imageRendering: 'pixelated', filter: 'drop-shadow(0 0 12px rgba(138,123,255,0.45))' }} />
        <span className="mono" style={titleStyles.version}>v3.2.1</span>
      </div>

      <div style={titleStyles.status}>
        <span style={titleStyles.dot} /> <span className="mono" style={{ fontSize: 11, color: 'var(--ink-dim)' }}>offline mode · ready</span>
      </div>

      <div style={titleStyles.right}>
        <button onClick={onProfile} style={titleStyles.profile} title="Change username">
          <PixelHead name={username} size={26} />
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 1 }}>
            <span style={{ fontSize: 13, fontWeight: 600 }}>{username}</span>
            <span className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)' }}>OFFLINE · CLICK TO EDIT</span>
          </div>
        </button>
        <div style={titleStyles.wctrl}>
          <span style={{ ...titleStyles.wbtn, background: '#ffbd44' }} />
          <span style={{ ...titleStyles.wbtn, background: '#28c941' }} />
          <span style={{ ...titleStyles.wbtn, background: '#ff5f57' }} />
        </div>
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
function HomeView({ username, onLaunch, launchState, progress, onStop, ram, onChangeName }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24, padding: '28px 32px 40px', minHeight: '100%' }}>
      <HeroPanel
        username={username}
        onLaunch={onLaunch}
        launchState={launchState}
        progress={progress}
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

function HeroPanel({ username, onLaunch, launchState, progress, onStop, onChangeName, ram }) {
  const running = launchState === 'running';
  const preparing = launchState === 'preparing';

  return (
    <div style={heroStyles.wrap}>
      {/* atom backdrop */}
      <img src="assets/atom.png" alt=""
        style={heroStyles.atom}
        aria-hidden="true" />

      <div style={heroStyles.inner}>
        <div style={heroStyles.left}>
          <div className="mono" style={{ fontSize: 11, letterSpacing: '0.18em', color: 'var(--violet-soft)', textTransform: 'uppercase' }}>
            Ready to launch
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
              progress={progress}
              onLaunch={onLaunch}
              onStop={onStop}
            />
            <div style={heroStyles.versionBtn} title="Plutonium only ships for 1.8.9">
              <span style={{ width: 8, height: 8, borderRadius: 1, background: 'var(--good)' }} />
              <span className="pixel" style={{ fontSize: 12 }}>{CLIENT_VERSION}</span>
              <span style={{ fontSize: 11, color: 'var(--ink-mute)' }}>locked</span>
            </div>
          </div>

          {preparing && (
            <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div className="mono" style={{ fontSize: 11, color: 'var(--violet-soft)', letterSpacing: '0.06em' }}>
                {progress < 30 ? 'Resolving libraries…' : progress < 65 ? 'Verifying assets…' : progress < 92 ? 'Allocating heap…' : 'Spawning JVM…'}  · {Math.floor(progress)}%
              </div>
              <div style={heroStyles.progressTrack}>
                <div style={{ ...heroStyles.progressFill, width: `${progress}%` }} />
              </div>
            </div>
          )}
          {running && (
            <div style={heroStyles.runStrip}>
              <span style={heroStyles.runDot} />
              <span className="mono" style={{ fontSize: 11, color: 'var(--good)' }}>RUNNING · pid 48201 · {CLIENT_VERSION}</span>
              <span style={{ flex: 1 }} />
              <button onClick={onStop} style={heroStyles.stopBtn}>Stop</button>
            </div>
          )}
        </div>

        <div style={heroStyles.right}>
          <StatTile label="Hours played" value="612" sub="on this account" />
          <StatTile label="Sessions" value="148" sub="avg 4h 12m" />
          <StatTile label="Wins" value="73%" sub="last 30 days" />
          <StatTile label="Memory" value={`${ram}.0`} sub="GB allocated" />
        </div>
      </div>
    </div>
  );
}

function PlayButton({ launchState, progress, onLaunch, onStop }) {
  const preparing = launchState === 'preparing';
  const running = launchState === 'running';

  if (running) {
    return (
      <button onClick={onStop} style={{ ...heroStyles.play, background: 'linear-gradient(180deg, #2a2a52 0%, #1b1b3a 100%)', boxShadow: 'inset 0 0 0 1px var(--line-2)' }}>
        <span className="pixel" style={{ fontSize: 18 }}>PLAYING</span>
        <span className="mono" style={{ fontSize: 11, color: 'var(--ink-dim)', marginTop: 2 }}>{CLIENT_VERSION} · click to stop</span>
      </button>
    );
  }
  if (preparing) {
    return (
      <button disabled style={{ ...heroStyles.play, cursor: 'progress' }}>
        <span className="pixel" style={{ fontSize: 18 }}>LAUNCHING</span>
        <span className="mono" style={{ fontSize: 11, marginTop: 2, opacity: 0.85 }}>{Math.floor(progress)}%</span>
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

function StatTile({ label, value, sub }) {
  return (
    <div style={statStyles.tile}>
      <div className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)', textTransform: 'uppercase', letterSpacing: '0.1em' }}>{label}</div>
      <div className="pixel" style={{ fontSize: 26, color: '#fff', marginTop: 6, letterSpacing: '0.02em' }}>{value}</div>
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
function SettingsView({ ram, setRam }) {
  return (
    <div style={{ padding: '28px 32px 40px', display: 'flex', flexDirection: 'column', gap: 18, maxWidth: 720 }}>
      <h1 className="pixel" style={{ margin: 0, fontSize: 22 }}>SETTINGS</h1>

      <SettingsCard label="Java heap" sub={`Memory allocated to the JVM. 1.8.9 runs comfortably at 2–4 GB.`}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <IconBox glyph={Icon.ram} size={18}/>
          <input type="range" min="1" max="8" value={ram} onChange={(e) => setRam(parseInt(e.target.value))}
                 style={{ flex: 1, accentColor: 'var(--violet)' }} />
          <span className="pixel" style={{ fontSize: 18, minWidth: 60, textAlign: 'right' }}>{ram} GB</span>
        </div>
      </SettingsCard>

      <SettingsCard label="Window mode" sub="How the client should open on launch.">
        <Segmented options={['Windowed', 'Borderless', 'Fullscreen']} value="Windowed" />
      </SettingsCard>

      <SettingsCard label="Optifine" sub="Bundle Optifine HD U M5 with the launch profile.">
        <Toggle on />
      </SettingsCard>

      <SettingsCard label="Discord Rich Presence" sub="Show your current server and PvP stats to Discord friends.">
        <Toggle on />
      </SettingsCard>

      <SettingsCard label="Close launcher on game start" sub="Frees up a bit of RAM — but you'll have to reopen this window to play again.">
        <Toggle on={false} />
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

function Segmented({ options, value }) {
  const [v, setV] = useState(value);
  return (
    <div style={{ display: 'inline-flex', background: 'var(--bg-2)', border: '1px solid var(--line)', borderRadius: 8, padding: 3 }}>
      {options.map(o => (
        <button key={o} onClick={() => setV(o)} style={{
          padding: '6px 12px', border: 0, borderRadius: 6, fontSize: 12, fontWeight: 500,
          background: v === o ? 'var(--violet-2)' : 'transparent',
          color: v === o ? '#fff' : 'var(--ink-dim)',
          cursor: 'pointer',
        }}>{o}</button>
      ))}
    </div>
  );
}

function Toggle({ on }) {
  const [val, setVal] = useState(on);
  return (
    <button onClick={() => setVal(!val)} style={{
      width: 44, height: 24, borderRadius: 999, border: 0, cursor: 'pointer',
      background: val ? 'var(--violet-2)' : 'var(--bg-3)',
      position: 'relative', transition: 'background .15s',
    }}>
      <span style={{
        position: 'absolute', top: 3, left: val ? 23 : 3, width: 18, height: 18,
        borderRadius: '50%', background: '#fff', transition: 'left .15s',
      }} />
    </button>
  );
}

/* ---------- PROFILE DIALOG (change username) ---------- */
function ProfileDialog({ username, onClose, onSave }) {
  const [name, setName] = useState(username);
  const [touched, setTouched] = useState(false);
  const ref = useRef();
  useEffect(() => { ref.current && ref.current.focus(); ref.current && ref.current.select(); }, []);

  const validation = useMemo(() => validateUsername(name), [name]);
  const canSave = validation.ok && name !== username;

  return (
    <Modal onClose={onClose} width={460}>
      <div style={modalStyles.head}>
        <div>
          <h2 className="pixel" style={{ margin: 0, fontSize: 16 }}>EDIT PROFILE</h2>
          <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--ink-dim)' }}>Your in-game name and how friends find you.</p>
        </div>
        <button onClick={onClose} style={modalStyles.x}><IconBox glyph={Icon.close} size={16}/></button>
      </div>

      <div style={{ padding: '4px 22px 18px', display: 'flex', alignItems: 'center', gap: 16 }}>
        <PixelHead name={name || 'Steve'} size={72} />
        <div style={{ flex: 1 }}>
          <div className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)', letterSpacing: '0.12em', textTransform: 'uppercase' }}>preview</div>
          <div className="pixel" style={{ fontSize: 22, marginTop: 4, color: name ? '#fff' : 'var(--ink-mute)' }}>
            {name || 'YourName'}
          </div>
          <div className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)', marginTop: 2 }}>palette derived from name hash</div>
        </div>
      </div>

      <div style={{ padding: '0 22px 8px' }}>
        <label className="mono" style={{ fontSize: 10, color: 'var(--ink-mute)', letterSpacing: '0.12em', textTransform: 'uppercase' }}>username</label>
        <div style={{ position: 'relative', marginTop: 6 }}>
          <input
            ref={ref}
            value={name}
            onChange={(e) => { setName(e.target.value); setTouched(true); }}
            onKeyDown={(e) => { if (e.key === 'Enter' && canSave) onSave(name); if (e.key === 'Escape') onClose(); }}
            spellCheck={false}
            maxLength={20}
            style={{
              ...inputStyle,
              width: '100%',
              fontFamily: "'Silkscreen', monospace",
              fontSize: 16,
              padding: '12px 14px',
              borderColor: touched && !validation.ok ? 'var(--bad)' : (canSave ? 'var(--violet)' : 'var(--line-2)'),
              boxShadow: canSave ? '0 0 0 3px rgba(138,123,255,0.18)' : 'none',
            }}
          />
          <span className="mono" style={{ position: 'absolute', right: 12, top: '50%', transform: 'translateY(-50%)', fontSize: 11, color: 'var(--ink-mute)' }}>{name.length}/20</span>
        </div>
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <Rule ok={name.length >= 3 && name.length <= 20} text="3–20 characters" />
          <Rule ok={/^[A-Za-z0-9_]*$/.test(name)} text="Letters, digits and underscores only" />
          <Rule ok={!/^_|_$/.test(name) || name === ''} text="Cannot start or end with an underscore" />
        </div>
      </div>

      <div style={modalStyles.foot}>
        <button onClick={onClose} style={ghostBtn}>Cancel</button>
        <button disabled={!canSave} onClick={() => onSave(name)} style={{
          ...primaryBtn,
          opacity: canSave ? 1 : 0.45,
          cursor: canSave ? 'pointer' : 'not-allowed',
        }}>Save changes</button>
      </div>
    </Modal>
  );
}

function validateUsername(n) {
  if (n.length < 3 || n.length > 20) return { ok: false };
  if (!/^[A-Za-z0-9_]+$/.test(n)) return { ok: false };
  if (/^_|_$/.test(n)) return { ok: false };
  return { ok: true };
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
    padding: '10px 16px',
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
  versionBtn: {
    display: 'inline-flex', alignItems: 'center', gap: 10,
    padding: '14px 16px',
    background: 'var(--bg-2)', color: 'var(--ink)',
    border: '1px solid var(--line-2)', borderRadius: 10, cursor: 'pointer',
  },
  progressTrack: { width: '100%', height: 6, background: 'var(--bg-2)', borderRadius: 999, overflow: 'hidden', border: '1px solid var(--line)' },
  progressFill: { height: '100%', background: 'linear-gradient(90deg, var(--violet), var(--violet-soft))', boxShadow: '0 0 16px var(--violet-glow)', transition: 'width .2s' },
  runStrip: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
    background: 'rgba(122,240,168,0.06)', border: '1px solid rgba(122,240,168,0.25)',
    borderRadius: 8, marginTop: 6,
  },
  runDot: { width: 8, height: 8, borderRadius: '50%', background: 'var(--good)', boxShadow: '0 0 8px var(--good)' },
  stopBtn: { background: 'transparent', border: '1px solid var(--line-2)', borderRadius: 6, color: 'var(--ink)', padding: '4px 12px', fontSize: 11, cursor: 'pointer' },
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
const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<App />);
