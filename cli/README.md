# jarvis-cli — Live Bridge setup guide

`jarvis-cli` talks to the Jarvis app's **Live Bridge** debug server, so
an assistant on a remote machine can check the app, read logs, run
commands, and answer send confirmations over Tailscale — no more
screenshot relay for QA.

## Setup (in order)

### 1. Phone: join Tailscale
Install Tailscale on the phone and sign in to your tailnet. Note the
phone's tailnet IP (100.64.x.y) from the Tailscale app.

### 2. Jarvis app: turn the bridge on
Settings → **Live Bridge** → toggle ON. Note two things from that
screen:
- **Tailnet IP** (or "not on a tailnet" — then step 1 is not done yet)
- **Auth token** (tap Copy; Regenerate makes a new one)

Optional: turn ON **"Allow remote send confirmations"** only if you
want the CLI to answer a send ask. OFF by default — sends must then be
confirmed on the phone, as usual.

### 3. VM: approve the "muse" device + run the CLI
The VM joins your tailnet as device **muse** — approve it in the
Tailscale admin panel (the approval link is supplied at runtime).
Then, from the repo root:

```bash
# one-time config (token file — NEVER commit or share it)
mkdir -p ~/.config/jarvis-cli
cat > ~/.config/jarvis-cli/config.json << 'EOF'
{"host": "100.64.x.y", "port": 8765, "token": "PASTE_TOKEN_HERE"}
EOF
chmod 600 ~/.config/jarvis-cli/config.json

python3 cli/jarvis_cli.py --tailscale health
python3 cli/jarvis_cli.py --tailscale status
```

Flags beat env vars beat the config file: `--host`, `--port`,
`--token`, or `JARVIS_BRIDGE_HOST` / `JARVIS_BRIDGE_PORT` /
`JARVIS_BRIDGE_TOKEN`. Add `--json` for raw JSON output.

## Example commands

```bash
python3 cli/jarvis_cli.py --tailscale status
python3 cli/jarvis_cli.py --tailscale logs --tag Live -n 50
python3 cli/jarvis_cli.py --tailscale tasks
python3 cli/jarvis_cli.py --tailscale screen
python3 cli/jarvis_cli.py --tailscale command "play rathinamo on spotify"
# if a send ask is pending AND remote confirmations are enabled:
python3 cli/jarvis_cli.py --tailscale answer yes
```

On the phone itself (Termux), drop `--tailscale` and use
`--host 127.0.0.1` — the bridge serves loopback too.

## Security notes

- **Token auth on every request** — no token, no answer (401).
- **Tailnet/loopback-only source guard** — any other address gets 403,
  even with a token.
- **Remote send confirmation is OFF by default.** With it off,
  `/answer` returns 403 and sends must be confirmed on the phone.
  The bridge never auto-sends: a "yes" only resolves the same
  just-in-time ask the phone would show.
- **Turn the bridge OFF when you are done debugging** (Settings →
  Live Bridge, or the notification's Stop action). The server only
  runs while the toggle is on.
- The token file must never be committed to git or pasted anywhere
  public. Regenerate the token in the app if it ever leaks.
