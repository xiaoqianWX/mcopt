#!/usr/bin/env python3
"""Reproducible in-game fps benchmark, through Prism Launcher and the dev-only bench mod (bench/, mod id mcopt-bench).

  tools/bench.py setup
      Builds the bench mod and creates (or refreshes) two dedicated Prism instances from the 26.3 instance:
        mcopt-bench-sodium   Fabric API + Sodium + the bench mod: the baseline without mcopt
        mcopt-bench-mcopt    the same + the mcopt jar (copied in again before every run)
  tools/bench.py run --config NAME=INSTANCE:API[+KEY=VALUE...] ... [--repeats N] [--label-prefix X] [--rd 16] [--still 10 --spin 20 --flight 20]
      API is default, opengl or vulkan (options.txt's preferredGraphicsBackend). KEY=VALUE pairs (profile, mcopt.*) go into the
      mcopt instance's config/mcopt.properties for that config (e.g. +mcopt.metal.pace=false); without any, mcopt's default. Example:
        tools/bench.py run --repeats 5 \\
          --config sodium-gl=mcopt-bench-sodium:opengl --config sodium-vk=mcopt-bench-sodium:vulkan \\
          --config mcopt-metal=mcopt-bench-mcopt:default --config mcopt-gl=mcopt-bench-mcopt:opengl

Each run launches the game through Prism with mcopt-bench/run.properties in the instance; the bench mod opens the world,
measures still / spin / flight and quits, leaving mcopt-bench/results/LABEL-TIMESTAMP.json (+ .png). Configs are
interleaved across repeats (A B C D, A B C D, ...) so slow drift (heat, background apps) spreads evenly. Before the first
measured run of each instance there's a warm-up run whose result is discarded (--no-warmup skips it).

The world: one copy for both instances. The first warm-up of an invocation starts from the saved copy (or, the very
first time, creates the world from the seed and generates it), then becomes the saved copy, so it holds every chunk the
current flight path reaches. Every run then starts from that copy (bench-results/golden/WORLD-SEED/), copied over the
instance's saves/WORLD: same terrain, same entities, same player data, in both instances and in every run.

The window: Prism opens it at 1920x1080 points. macOS shrinks a window that doesn't fit the screen, and a Retina
framebuffer has twice the pixels each way; each result records the window, framebuffer and display (with its refresh
rate). --window 960x540 gives a 1920x1080 framebuffer on a Retina display.

Only one game at a time: before each launch it waits while any Prism-launched game (org.prismlauncher.EntryPoint) or
Fabric dev client is running, someone else's included. Results: bench-results/TIMESTAMP/{summary.md, summary.json,
runs/}. Python 3 standard library only.
"""
import argparse, datetime, hashlib, json, os, re, shutil, signal, statistics, subprocess, sys, time, zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PRISM_DATA = Path.home() / 'Library/Application Support/PrismLauncher'
INSTANCES = PRISM_DATA / 'instances'
PRISM = '/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher'
SODIUM_INSTANCE, MCOPT_INSTANCE = 'mcopt-bench-sodium', 'mcopt-bench-mcopt'
TEMPLATE_MODS = ['fabric-api-0.161.0+26.3.jar', 'sodium-fabric-0.9.3-alpha.1+mc26.3.jar']
# Any running game: Prism starts Knot inside its own wrapper, so only the wrapper's class is on the command line.
GAME_PATTERN = r'org\.prismlauncher\.EntryPoint|net\.fabricmc\.loader\.impl\.launch\.knot\.KnotClient'
PHASES = ['still', 'spin', 'flight']
APIS = ('default', 'opengl', 'vulkan')
MCOPT_MOD_ID = 'mcopt-metal'
BENCH_MOD_ID = 'mcopt-bench'


def log(*a):
	print(*a, flush=True)


def die(msg):
	sys.exit('bench.py: ' + msg)


# ---------- instances ----------

def instance_dir(name):
	if not name.startswith('mcopt-bench-'):
		die(f'{name}: only mcopt-bench-* instances are touched')
	return INSTANCES / name


def mod_id(jar):
	try:
		with zipfile.ZipFile(jar) as z:
			return json.loads(z.read('fabric.mod.json').decode('utf-8'), strict=False).get('id')
	except Exception:
		return None


def has_mcopt(inst):
	"""mcopt-bench-mcopt, or another bench instance with mcopt in its mods (e.g. one made for Distant Horizons): its runs get
	the jar under test and their config/mcopt.properties."""
	if inst == MCOPT_INSTANCE:
		return True
	mods = instance_dir(inst) / 'minecraft/mods'
	return mods.is_dir() and any(mod_id(f) == MCOPT_MOD_ID for f in mods.glob('*.jar'))


def put_mod(mods, jar, mid):
	"""Copy jar into mods, replacing any other jar of the same mod id (old versions, other file names)."""
	for f in mods.glob('*.jar'):
		if f.name != jar.name and mod_id(f) == mid:
			f.unlink()
	dst = mods / jar.name
	if not dst.exists() or dst.read_bytes() != jar.read_bytes():
		shutil.copy2(jar, dst)


def bench_jar():
	jars = sorted((p for p in (REPO / 'bench/build/libs').glob('bench-*.jar') if not re.search(r'-(sources|dev)\.jar$', p.name)),
		key=lambda p: p.stat().st_mtime)
	if not jars:
		die('no bench/build/libs/bench-*.jar: run tools/bench.py setup (or ./gradlew :bench:build)')
	return jars[-1]


def write_cfg(src, dst, name):
	"""instance.cfg from the template, line by line (Qt's INI: configparser would rewrite keys and values)."""
	overrides = {
		'name': name, 'notes': 'Created by mcopt tools/bench.py for the fps benchmark (plays normally without mcopt-bench/run.properties)',  # no ';': a comment in Qt's INI
		'OverrideWindow': 'true', 'MinecraftWinWidth': '1920', 'MinecraftWinHeight': '1080', 'LaunchMaximized': 'false',
		'OverrideMemory': 'true', 'MaxMemAlloc': '4096', 'MinMemAlloc': '512',
		'JoinWorldOnLaunch': '', 'JoinServerOnLaunch': 'false', 'iconKey': 'default',
	}
	drop = {'lastLaunchTime', 'lastTimePlayed', 'totalTimePlayed', 'linkedInstances', 'shortcuts', 'ManagedPack', 'ManagedPackID',
		'ManagedPackName', 'ManagedPackType', 'ManagedPackURL', 'ManagedPackVersionID', 'ManagedPackVersionName'}
	out, seen, section = [], set(), None
	for line in src.read_text(encoding='utf-8').splitlines():
		m = re.match(r'^\[(.+)\]$', line)
		if m:
			section = m.group(1)
			if section != 'General':
				continue
			out.append(line)
			continue
		if section != 'General':
			continue  # [UI]: column layouts, nothing to copy
		key = line.split('=', 1)[0]
		if key in drop:
			continue
		if key in overrides:
			line = f'{key}={overrides[key]}'
			seen.add(key)
		out.append(line)
	out += [f'{k}={v}' for k, v in overrides.items() if k not in seen]
	tmp = dst.with_suffix('.cfg.tmp')
	tmp.write_text('\n'.join(out) + '\n', encoding='utf-8')
	os.replace(tmp, dst)


def set_option(game_dir, key, value):
	"""Set (or add) one options.txt line."""
	f = game_dir / 'options.txt'
	lines = f.read_text(encoding='utf-8').splitlines() if f.exists() else []
	lines = [l for l in lines if not l.startswith(key + ':')] + [f'{key}:{value}']
	f.write_text('\n'.join(lines) + '\n', encoding='utf-8')


def setup(a):
	template = INSTANCES / a.template
	if not (template / 'instance.cfg').exists():
		die(f'no template instance at {template}')
	log('building the bench mod')
	subprocess.run(['./gradlew', ':bench:build', '-q', '--console=plain'], cwd=REPO, check=True)
	jar = bench_jar()
	mcopt = Path(a.mcopt_jar)
	for name, with_mcopt in ((SODIUM_INSTANCE, False), (MCOPT_INSTANCE, True)):
		d = instance_dir(name)
		game = d / 'minecraft'
		mods = game / 'mods'
		new = not d.exists()
		mods.mkdir(parents=True, exist_ok=True)
		write_cfg(template / 'instance.cfg', d / 'instance.cfg', name)
		shutil.copy2(template / 'mmc-pack.json', d / 'mmc-pack.json')
		for m in TEMPLATE_MODS:
			src = template / 'minecraft/mods' / m
			if not src.exists():
				die(f'{src} is missing')
			put_mod(mods, src, mod_id(src))
		put_mod(mods, jar, BENCH_MOD_ID)
		if with_mcopt:
			if mcopt.exists():
				put_mod(mods, mcopt, MCOPT_MOD_ID)
			else:
				log(f'  note: {mcopt} not built yet (tools/release); every run copies the current one in')
		if not (game / 'options.txt').exists():
			set_option(game, 'onboardAccessibility', 'false')
			set_option(game, 'preferredGraphicsBackend', '"default"')
		log(f'{"created" if new else "updated"} {d}')
		log('  mods: ' + ', '.join(sorted(p.name for p in mods.glob('*.jar'))))
	log('\nPrism picks new instance folders up while running (it watches instances/); if --launch says the instance is unknown,')
	log('restart Prism once. Next: tools/bench.py run --config sodium-gl=mcopt-bench-sodium:opengl --config mcopt-metal=mcopt-bench-mcopt:default')


# ---------- processes ----------

def pgrep(pattern):
	"""Java processes whose command line matches (not a shell that merely mentions the pattern, like someone's pgrep)."""
	r = subprocess.run(['pgrep', '-f', pattern], capture_output=True, text=True)
	pids = []
	for p in r.stdout.split():
		comm = subprocess.run(['ps', '-o', 'comm=', '-p', p], capture_output=True, text=True).stdout.strip()
		if os.path.basename(comm) == 'java':
			pids.append(int(p))
	return pids


def describe(pid):
	r = subprocess.run(['ps', '-o', 'args=', '-p', str(pid)], capture_output=True, text=True)
	m = re.search(r'instances/([^/]+)/', r.stdout)
	return m.group(1) if m else r.stdout.strip()[:80]


def wait_no_game():
	shown = None
	while True:
		pids = pgrep(GAME_PATTERN)
		if not pids:
			if shown:
				log('  ... it has quit')
			return
		who = ', '.join(f'{describe(p)} (pid {p})' for p in pids)
		if who != shown:
			log(f'  waiting: a game is running: {who}')
			shown = who
		time.sleep(5)


def instance_pids(name):
	return pgrep(r'java.*instances/' + re.escape(name) + '/')


def stop(pid):
	for sig, wait in ((signal.SIGTERM, 20), (signal.SIGKILL, 5)):
		try:
			os.kill(pid, sig)
		except ProcessLookupError:
			return
		for _ in range(wait * 2):
			time.sleep(0.5)
			try:
				os.kill(pid, 0)
			except ProcessLookupError:
				return


class LogTail:
	"""Echoes the bench's own lines from the instance's latest.log as the run goes."""

	def __init__(self, game_dir, since):
		self.f, self.since, self.pos, self.ino = game_dir / 'logs/latest.log', since, 0, None

	def poll(self):
		try:
			st = self.f.stat()
		except FileNotFoundError:
			return
		if st.st_mtime < self.since:
			return  # the previous launch's log
		if st.st_ino != self.ino or st.st_size < self.pos:
			self.ino, self.pos = st.st_ino, 0
		with open(self.f, 'rb') as fh:
			fh.seek(self.pos)
			data = fh.read()
		end = data.rfind(b'\n') + 1
		self.pos += end
		for line in data[:end].decode('utf-8', 'replace').splitlines():
			i = line.find('[STDOUT]: [mcopt-bench] ')  # not the server's "ServerLevel[mcopt-bench]" lines
			if i >= 0:
				log('    ' + line[i + 24:])


def log_tail(game_dir, n=40):
	f = game_dir / 'logs/latest.log'
	if not f.exists():
		return '(no logs/latest.log)'
	return '\n'.join(f.read_text(encoding='utf-8', errors='replace').splitlines()[-n:])


# ---------- one run ----------

def golden_dir(a):
	return REPO / 'bench-results/golden' / f'{a.world}-{a.seed}'


def copy_world(src, dst):
	if dst.exists():
		shutil.rmtree(dst)
	shutil.copytree(src, dst, ignore=shutil.ignore_patterns('session.lock'))


def run_props(a, label):
	return {
		'label': label, 'renderDistance': a.rd, 'still': a.still, 'spin': a.spin, 'flight': a.flight, 'flightSpeed': a.flight_speed,
		'settleMin': a.settle_min, 'settleMax': a.settle_max, 'seed': a.seed, 'world': a.world, 'screenshot': str(not a.no_screenshot).lower(),
		'quit': 'true',
		'windowWidth': a.window[0] if a.window else 0, 'windowHeight': a.window[1] if a.window else 0,
		'fullscreen': str(a.fullscreen).lower(),
		'signs': a.signs, 'signText': a.sign_text,
	}


def one_run(a, name, inst, api, props, label, mcopt_jar, bjar):
	"""Launch one run; returns (result dict or None, json path or None, error or None)."""
	d = instance_dir(inst)
	game = d / 'minecraft'
	if not (d / 'instance.cfg').exists():
		return None, None, f'no instance {d}: run tools/bench.py setup'
	wait_no_game()
	mods = game / 'mods'
	put_mod(mods, bjar, BENCH_MOD_ID)
	if has_mcopt(inst):
		put_mod(mods, mcopt_jar, MCOPT_MOD_ID)
		write_mcopt_props(game, name, props)
	set_option(game, 'preferredGraphicsBackend', f'"{api}"')
	saves = game / 'saves' / a.world
	golden = golden_dir(a)
	if golden.exists():
		copy_world(golden, saves)
	elif saves.exists():
		shutil.rmtree(saves)  # created from the seed by this run: never a world from another seed
	bench = game / 'mcopt-bench'
	(bench / 'results').mkdir(parents=True, exist_ok=True)
	run_file = bench / 'run.properties'
	run_file.write_text(''.join(f'{k}={v}\n' for k, v in run_props(a, label).items()), encoding='utf-8')
	try:
		started = time.time()
		launcher = subprocess.Popen([PRISM, '--launch', inst], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
		tail = LogTail(game, started - 1)
		pid = None
		while time.time() - started < 120 and pid is None:
			time.sleep(1)
			pids = instance_pids(inst)
			pid = pids[0] if pids else None
		if pid is None:
			return None, None, 'Prism did not start the game within 120 s (unknown instance? restart Prism once after setup)'
		log(f'    game pid {pid}')
		result_file = None
		while time.time() - started < a.timeout:
			tail.poll()
			found = [p for p in (bench / 'results').glob(f'{label}-*.json') if p.stat().st_mtime >= started - 1]
			if found:
				result_file = max(found, key=lambda p: p.stat().st_mtime)
				break
			if not instance_pids(inst):
				time.sleep(1)
				found = [p for p in (bench / 'results').glob(f'{label}-*.json') if p.stat().st_mtime >= started - 1]
				if found:
					result_file = max(found, key=lambda p: p.stat().st_mtime)
					break
				return None, None, 'the game exited without a result (crash?)\n' + log_tail(game)
			time.sleep(1)
		if result_file is None:
			stop(pid)
			return None, None, f'no result within {a.timeout} s (game stopped)\n' + log_tail(game)
		deadline = time.time() + 120
		while instance_pids(inst) and time.time() < deadline:
			tail.poll()
			time.sleep(1)
		tail.poll()
		quit_error = None
		if instance_pids(inst):
			stop(pid)
			quit_error = 'the game did not quit within 120 s of writing its result (stopped)'
		launcher.poll()
		res = json.loads(result_file.read_text(encoding='utf-8'))
		if res.get('error'):
			return res, result_file, 'the bench failed in game:\n' + res['error'][:2000]
		return res, result_file, quit_error
	finally:
		run_file.unlink(missing_ok=True)  # the instance plays normally again


def expected_backend(inst, api):
	if api == 'opengl':
		return 'OpenGL'
	if api == 'vulkan':
		return 'Vulkan'
	return 'Metal' if has_mcopt(inst) else 'OpenGL'


# ---------- summary ----------

def med(xs):
	return statistics.median(xs) if xs else None


def fmt(x, d=1):
	return '-' if x is None else f'{x:.{d}f}'


def summarize(configs, runs):
	summary = {}
	for name, inst, api, props in configs:
		rs = [r for r in runs if r['config'] == name and r['ok']]
		entry = {'instance': inst, 'api': api, 'mcoptProperties': props, 'runs': len(rs), 'failed': len([r for r in runs if r['config'] == name and not r['ok']]),
			'backends': sorted({r['backend'] for r in rs}), 'expectedBackend': expected_backend(inst, api),
			'mismatches': len([r for r in rs if r['mismatch']]), 'phases': {}}
		for ph in PHASES:
			avg = [r['phases'][ph]['avgFps'] for r in rs if ph in r['phases']]
			low = [r['phases'][ph]['low1Fps'] for r in rs if ph in r['phases'] and 'low1Fps' in r['phases'][ph]]
			if avg:
				entry['phases'][ph] = {'avgFps': {'median': med(avg), 'min': min(avg), 'max': max(avg), 'all': avg},
					'low1Fps': {'median': med(low), 'min': min(low) if low else None, 'max': max(low) if low else None, 'all': low}}
		summary[name] = entry
	return summary


def markdown(meta, configs, runs, summary):
	L = [f'# mcopt fps benchmark {meta["started"]}', '']
	L.append(f'{meta["machine"]}. Render distance {meta["args"]["rd"]}; phases still {meta["args"]["still"]} s, spin {meta["args"]["spin"]} s, '
		f'flight {meta["args"]["flight"]} s at {meta["args"]["flight_speed"]} blocks/s; seed {meta["args"]["seed"]}; '
		f'{meta["args"]["repeats"]} repeat(s), interleaved' + ('; fullscreen.' if meta["args"].get("fullscreen") else '.'))
	L.append(f'mcopt jar: `{meta["mcoptJar"]["path"]}` sha256 {meta["mcoptJar"]["sha256"][:12]}; repo {meta["git"]}.')
	L.append('')
	for name, inst, api, props in configs:
		L.append(f'- {name}: {inst}, graphics API {api}' + ''.join(f', {k}={v}' for k, v in props.items()))
	L.append('')
	L.append('Median across repeats (min-max). Avg fps = frames / seconds; 1% low = 1000 / mean of the slowest 1% of frame times.')
	L.append('')
	L.append('| config | backend | phase | runs | avg fps | range | 1% low | range |')
	L.append('|---|---|---|---|---|---|---|---|')
	for name, inst, api, props in configs:
		s = summary[name]
		be = ', '.join(s['backends']) or '-'
		if s['mismatches']:
			be += f' **MISMATCH (asked {s["expectedBackend"]})**'
		for ph in PHASES:
			p = s['phases'].get(ph)
			if not p:
				continue
			L.append(f'| {name} | {be} | {ph} | {s["runs"]} | {fmt(p["avgFps"]["median"])} | {fmt(p["avgFps"]["min"], 0)}-{fmt(p["avgFps"]["max"], 0)} '
				f'| {fmt(p["low1Fps"]["median"])} | {fmt(p["low1Fps"]["min"], 0)}-{fmt(p["low1Fps"]["max"], 0)} |')
		if s['failed']:
			L.append(f'| {name} | | **{s["failed"]} failed run(s)** | | | | | |')
	L += ['', '## Runs', '', 'Avg fps per phase; load: the Mac\'s 1-minute load average as still began (other work competing with the game).', '',
		'| # | config | repeat | backend | settle s | still | spin | flight | window (framebuffer) | display | load | notes |', '|---|---|---|---|---|---|---|---|---|---|---|---|']
	for i, r in enumerate(runs, 1):
		if not r['ok']:
			L.append(f'| {i} | {r["config"]} | {r["repeat"]} | - | - | - | - | - | - | - | - | FAILED: {r["error"].splitlines()[0] if r["error"] else ""} |')
			continue
		ph = r['phases']
		notes = []
		if r['mismatch']:
			notes.append(f'backend mismatch: asked {r["expectedBackend"]}')
		if r['settle'].get('timedOut'):
			notes.append('settle timed out')
		unfocused = sum(ph[p].get('unfocusedFrames', 0) for p in ph)
		limited = sum(ph[p].get('limitedFrames', 0) for p in ph)
		if limited:
			notes.append(f'{limited} frame-limited frames')
		if unfocused:
			notes.append(f'unfocused {unfocused} frames')
		if r.get('screenInterruptions'):
			notes.append(f'{r["screenInterruptions"]} screen(s) closed')
		if r.get('warning'):
			notes.append(r['warning'])
		w = r.get('window', {})
		L.append(f'| {i} | {r["config"]} | {r["repeat"]} | {r["backend"]} | {fmt(r["settle"].get("seconds"))} | '
			+ ' | '.join(fmt(ph[p]['avgFps']) if p in ph else '-' for p in PHASES)
			+ f' | {w.get("width")}x{w.get("height")} ({w.get("framebufferWidth")}x{w.get("framebufferHeight")}) | {w.get("monitor", {}).get("mode", "-")} '
			f'| {fmt(ph.get("still", {}).get("loadAvg"))} | {"; ".join(notes)} |')
	return '\n'.join(L) + '\n'


def sha256(p):
	return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def machine():
	def sh(*cmd):
		try:
			return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()
		except OSError:
			return '?'
	mem = sh('sysctl', '-n', 'hw.memsize')
	gb = f'{int(mem) / 2**30:.0f} GB' if mem.isdigit() else '?'
	return f'{sh("sysctl", "-n", "machdep.cpu.brand_string")}, {gb}, macOS {sh("sw_vers", "-productVersion")}'


def git_state():
	try:
		head = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=REPO, capture_output=True, text=True).stdout.strip()
		dirty = subprocess.run(['git', 'status', '--porcelain'], cwd=REPO, capture_output=True, text=True).stdout.strip()
		return head + (' (uncommitted changes)' if dirty else '')
	except OSError:
		return '?'


def parse_config(s):
	"""NAME=INSTANCE:API[+KEY=VALUE...]: the optional KEY=VALUE pairs (profile, mcopt.*) go into the mcopt instance's
	config/mcopt.properties for that config's runs, e.g. mcopt-nopace=mcopt-bench-mcopt:default+mcopt.metal.pace=false."""
	m = re.match(r'^([A-Za-z0-9._-]+)=([^:]+):(\w+)((?:\+[A-Za-z0-9._-]+=[^+]*)*)$', s)
	if not m or m.group(3) not in APIS:
		die(f'--config {s}: expected NAME=INSTANCE:API[+KEY=VALUE...] with API one of {", ".join(APIS)} (NAME: letters, digits, . _ -)')
	instance_dir(m.group(2))
	props = dict(kv.split('=', 1) for kv in m.group(4).split('+')[1:])
	for k in props:
		if not (k == 'profile' or k.startswith('mcopt.')):
			die(f'--config {s}: {k}: only profile and mcopt.* keys (config/mcopt.properties)')
	if props and not has_mcopt(m.group(2)):
		die(f'--config {s}: mcopt settings need an instance with mcopt ({MCOPT_INSTANCE})')
	return m.group(1), m.group(2), m.group(3), props


def write_mcopt_props(game_dir, name, props):
	"""Every run states its mcopt settings: mcopt's default (the alpha profile) plus the config's keys, nothing left over."""
	lines = [f'# Written by tools/bench.py for config {name}: mcopt\'s default profile plus that config\'s keys.', 'profile=alpha']
	lines = [l for l in lines if not (l.startswith('profile=') and 'profile' in props)] + [f'{k}={v}' for k, v in props.items()]
	(game_dir / 'config').mkdir(parents=True, exist_ok=True)
	(game_dir / 'config/mcopt.properties').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def run(a):
	configs = [parse_config(c) for c in a.config]
	if len({c[0] for c in configs}) != len(configs):
		die('config names must be unique')
	mcopt_jar = (REPO / a.mcopt_jar) if not Path(a.mcopt_jar).is_absolute() else Path(a.mcopt_jar)
	if any(has_mcopt(inst) for _, inst, _, _ in configs) and not mcopt_jar.exists():
		die(f'{mcopt_jar} is missing: build it with tools/release, or pass --mcopt-jar')
	bjar = bench_jar()
	stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
	out = REPO / 'bench-results' / stamp
	(out / 'runs').mkdir(parents=True, exist_ok=True)

	plan, warmed = [], set()
	for rep in range(1, a.repeats + 1):
		for name, inst, api, props in configs:
			if not a.no_warmup and inst not in warmed:
				warmed.add(inst)
				plan.append((name, inst, api, props, 0))
			if not a.warmup_only:
				plan.append((name, inst, api, props, rep))
	meta = {'started': stamp, 'args': vars(a), 'machine': machine(), 'git': git_state(),
		'mcoptJar': {'path': str(mcopt_jar), 'sha256': sha256(mcopt_jar) if mcopt_jar.exists() else '', 'mtime': mcopt_jar.stat().st_mtime if mcopt_jar.exists() else 0},
		'benchJar': {'path': str(bjar), 'sha256': sha256(bjar)}, 'configs': [{'name': n, 'instance': i, 'api': p, 'mcoptProperties': pr} for n, i, p, pr in configs]}
	golden = golden_dir(a)
	if a.no_warmup and not golden.exists():
		log(f'warning: --no-warmup and no saved world at {golden}: the first run creates the world, and its numbers include that')
	log(f'{len(plan)} runs ({sum(1 for p in plan if p[4] == 0)} warm-up); results in {out}')

	runs, golden_saved = [], False
	for i, (name, inst, api, props, rep) in enumerate(plan, 1):
		label = f'{a.label_prefix}{name}-' + (f'r{rep}' if rep else 'warmup')
		log(f'[{i}/{len(plan)}] {label}: {inst}, graphics API {api}' + ''.join(f', {k}={v}' for k, v in props.items()))
		t = time.time()
		res, res_file, err = one_run(a, name, inst, api, props, label, mcopt_jar, bjar)
		log(f'    {time.time() - t:.0f} s')
		ok = res is not None and not (res.get('error'))
		if ok and err is None and not golden_saved and (rep == 0 or not golden.exists()):
			# this run's world (after the game saved it on quit) is the copy every later run starts from
			golden.parent.mkdir(parents=True, exist_ok=True)
			copy_world(INSTANCES / inst / 'minecraft/saves' / a.world, golden)
			golden_saved = True
			log(f'    saved the world as {golden}')
		if err and not ok:
			log('    FAILED: ' + err.replace('\n', '\n      '))
		elif err:
			log('    warning: ' + err)
		if rep == 0:
			if ok:
				log(f'    warm-up done ({res["backend"].get("backendName")}), result discarded')
			continue
		rec = {'config': name, 'instance': inst, 'api': api, 'mcoptProperties': props, 'repeat': rep, 'label': label, 'ok': ok, 'error': None if ok else err,
			'warning': err if ok else None, 'expectedBackend': expected_backend(inst, api)}
		if res is not None and res_file is not None:
			shutil.copy2(res_file, out / 'runs' / res_file.name)
			png = res.get('screenshot', {}).get('file')
			if png and Path(png).exists():
				shutil.copy2(png, out / 'runs' / Path(png).name)
			rec['resultFile'] = str(res_file)
			rec['screenshot'] = png
		if ok:
			backend = res['backend'].get('backendName', '?')
			rec.update({'backend': backend, 'driverInfo': res['backend'].get('driverInfo'), 'gpu': res['backend'].get('name'),
				'mismatch': rec['expectedBackend'].lower() not in backend.lower(), 'settle': res.get('settle', {}), 'window': res.get('window', {}),
				'options': res.get('options', {}), 'screenInterruptions': res.get('screenInterruptions', 0), 'phases': res.get('phases', {})})
			ph = rec['phases']
			log(f'    {backend}: ' + ' / '.join(f'{p} {ph[p]["avgFps"]:.1f} fps (1% low {ph[p].get("low1Fps", 0):.0f})' for p in PHASES if p in ph)
				+ f'; settle {rec["settle"].get("seconds")} s' + (' TIMED OUT' if rec['settle'].get('timedOut') else ''))
			if rec['mismatch']:
				log(f'    !!! BACKEND MISMATCH: asked {api} -> expected {rec["expectedBackend"]}, the game ran {backend} !!!')
		runs.append(rec)
		summary = summarize(configs, runs)
		(out / 'summary.json').write_text(json.dumps({'meta': meta, 'summary': summary, 'runs': runs}, indent='\t') + '\n', encoding='utf-8')
		(out / 'summary.md').write_text(markdown(meta, configs, runs, summary), encoding='utf-8')

	if not runs:
		log('no measured runs')
		return 1
	md = (out / 'summary.md').read_text(encoding='utf-8')
	log('\n' + md)
	log(f'written: {out / "summary.md"}, {out / "summary.json"}, {out / "runs"}')
	bad = [n for n, s in summarize(configs, runs).items() if s['mismatches']]
	if bad:
		log(f'!!! backend mismatch in: {", ".join(bad)} !!!')
	return 0 if all(r['ok'] for r in runs) and not bad else 1


def window_size(s):
	m = re.match(r'^(\d+)x(\d+)$', s)
	if not m:
		raise argparse.ArgumentTypeError('expected WxH, e.g. 960x540')
	return int(m.group(1)), int(m.group(2))


def main():
	p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	common = argparse.ArgumentParser(add_help=False)
	common.add_argument('--mcopt-jar', default='dist/mcopt-0.2.0-alpha.1.jar', help='the mcopt jar to bench (default: %(default)s, relative to the repo)')
	sub = p.add_subparsers(dest='cmd', required=True)
	s = sub.add_parser('setup', parents=[common], help='build the bench mod, create/update the two mcopt-bench-* instances')
	s.add_argument('--template', default='26.3', help='instance to copy instance.cfg, mmc-pack.json and the Fabric API/Sodium jars from')
	r = sub.add_parser('run', parents=[common], help='run a benchmark matrix')
	r.add_argument('--config', action='append', required=True, metavar='NAME=INSTANCE:API[+KEY=VALUE...]')
	r.add_argument('--repeats', type=int, default=3)
	r.add_argument('--label-prefix', default='')
	r.add_argument('--rd', type=int, default=16, help='render distance')
	r.add_argument('--still', type=float, default=10)
	r.add_argument('--spin', type=float, default=20)
	r.add_argument('--flight', type=float, default=20)
	r.add_argument('--flight-speed', type=float, default=20, help='blocks a second')
	r.add_argument('--settle-min', type=float, default=8)
	r.add_argument('--settle-max', type=float, default=120)
	r.add_argument('--seed', type=int, default=20261005)
	r.add_argument('--world', default='mcopt-bench')
	r.add_argument('--timeout', type=float, default=360, help='seconds from launch to the result file')
	r.add_argument('--no-warmup', action='store_true')
	r.add_argument('--warmup-only', action='store_true', help='only the warm-up runs: (re)makes the saved world, e.g. to let Distant Horizons generate LODs into it over a long --settle-min')
	r.add_argument('--no-screenshot', action='store_true')
	r.add_argument('--signs', type=int, default=0, help='a wall of this many glowing signs (up to 231, outlined: within 16 blocks) in front of the camera')
	r.add_argument('--sign-text', choices=['ascii', 'cjk'], default='ascii', help='what the signs say: ascii (one glyph page) or cjk (Chinese mixed with Latin and symbols, over many glyph pages)')
	r.add_argument('--fullscreen', action='store_true', help='run fullscreen (the display\'s own resolution; overrides --window)')
	r.add_argument('--window', type=window_size, metavar='WxH',
		help='window size in points, set by the game (default: the instance\'s 1920x1080). A Retina window has twice the pixels each way, '
			'and macOS shrinks one that does not fit the screen: the results record the real window and framebuffer size')
	a = p.parse_args()
	if a.cmd == 'setup':
		a.mcopt_jar = str(REPO / a.mcopt_jar) if not Path(a.mcopt_jar).is_absolute() else a.mcopt_jar
		setup(a)
		return 0
	if not re.match(r'^[A-Za-z0-9._-]*$', a.label_prefix):
		die('--label-prefix: letters, digits, . _ - only')
	return run(a)


if __name__ == '__main__':
	sys.exit(main())
