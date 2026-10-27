#!/usr/bin/env python3
"""jarvis-cli — talk to the Jarvis app's Live Bridge debug server.

The Jarvis app (Settings -> Live Bridge) runs a small token-authenticated
HTTP server on the phone. This CLI speaks to it, so an assistant on a
remote machine can check status, read logs, see tasks/screens, run
commands, and (only if the user enabled it) answer a send confirmation —
without screenshot relay.

Connection modes:
  * Direct (default): talks straight to --host/--port. Use on the phone
    itself (Termux) or on the same network. Environment proxies are
    deliberately bypassed in this mode.
  * --tailscale: routes through the VM's egress proxy with its port
    swapped to 3130 (that is how the VM reaches the tailnet). Requires
    $HTTPS_PROXY to be set.

Credentials come from (first wins): flags > environment >
~/.config/jarvis-cli/config.json > built-in defaults. The config file
holds a bearer token: NEVER commit it, never share it, chmod 600 it.

Exit codes: 0 ok, 2 usage error, 3 auth/connection failure.
The token is never printed, including in error messages.

Examples:
  jarvis-cli --host 100.64.x.y --token <token> --tailscale status
  jarvis-cli logs --tag Live -n 50
  jarvis-cli command "play rathinamo on spotify"
  jarvis-cli answer yes
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

DEFAULT_PORT = 8765
CONFIG_PATH = os.path.expanduser("~/.config/jarvis-cli/config.json")


def load_config_file():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def resolve(name, flag_value, env_name, config, default):
    if flag_value is not None:
        return flag_value
    env_value = os.environ.get(env_name)
    if env_value:
        return env_value
    cfg_value = config.get(name)
    if cfg_value is not None and cfg_value != "":
        return cfg_value
    return default


def tailscale_proxy_url():
    """$HTTPS_PROXY with its port replaced by 3130 (VM -> tailnet path)."""
    raw = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if not raw:
        return None
    parsed = urllib.parse.urlparse(raw)
    host = parsed.hostname or ""
    auth = ""
    if parsed.username:
        auth = parsed.username
        if parsed.password:
            auth += ":" + parsed.password
        auth += "@"
    scheme = parsed.scheme or "http"
    return "%s://%s%s:3130" % (scheme, auth, host)


def build_opener(use_tailscale):
    if use_tailscale:
        proxy = tailscale_proxy_url()
        if not proxy:
            print(
                "error: --tailscale needs $HTTPS_PROXY to be set",
                file=sys.stderr,
            )
            sys.exit(3)
        return urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy})
        )
    # Direct mode: never let an environment proxy swallow the connection.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(opener, base, token, method, path, body=None, timeout=130):
    url = base + path
    data = None
    headers = {"Authorization": "Bearer " + token}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", "replace")
        print("error: HTTP %s: %s" % (exc.code, text or exc.reason), file=sys.stderr)
        sys.exit(3)
    except urllib.error.URLError as exc:
        print("error: cannot reach bridge: %s" % exc.reason, file=sys.stderr)
        sys.exit(3)


def fmt_time(ms):
    try:
        return datetime.fromtimestamp(ms / 1000.0).strftime("%H:%M:%S")
    except Exception:
        return "?"


def render(cmd, payload):
    if cmd == "health":
        print("OK — version %s" % payload.get("version", "?"))
    elif cmd == "status":
        print("Jarvis %s (code %s)" % (payload.get("version"), payload.get("versionCode")))
        print("voice: %s / %s" % (payload.get("voiceEngine"), payload.get("voiceStatus")))
        print("overlay: %s, accessibility: %s" % (
            payload.get("overlayRunning"), payload.get("accessibilityOn")))
        print("bridge uptime: %ss on port %s" % (
            payload.get("bridgeUptimeSeconds"), payload.get("bridgePort")))
        print("tailnet IP: %s" % payload.get("tailnetIp"))
    elif cmd == "logs":
        for line in payload.get("lines", []):
            print("%s %s/%s %s" % (
                fmt_time(line.get("timeMs", 0)),
                line.get("level", "?"), line.get("tag", "?"),
                line.get("message", "")))
    elif cmd == "tasks":
        for section in ("current", "history"):
            print("== %s ==" % section)
            for item in payload.get(section, []):
                print("[%s] %s — %s (%s%%)" % (
                    item.get("status"), item.get("title"),
                    item.get("resultLine") or item.get("step") or "",
                    item.get("progress", 0)))
    elif cmd == "screen":
        if payload.get("available"):
            print(payload.get("elements", ""))
        else:
            print("screen not available: %s" % payload.get("reason", "?"))
    elif cmd == "command":
        if payload.get("accepted"):
            print("accepted — %s" % payload.get("note", "still running"))
        else:
            print(payload.get("reply", ""))
    elif cmd == "answer":
        print("answered: %s" % payload.get("answer"))
    else:
        print(json.dumps(payload, indent=2))


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="jarvis-cli",
        description="CLI for the Jarvis app's Live Bridge debug server.",
    )
    parser.add_argument("--host", default=None, help="bridge host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=None,
                        help="bridge port (default: 8765)")
    parser.add_argument("--token", default=None, help="bridge bearer token")
    parser.add_argument("--tailscale", action="store_true",
                        help="route via $HTTPS_PROXY with port 3130 (VM -> tailnet)")
    parser.add_argument("--json", action="store_true", help="print raw JSON")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("health", help="check the bridge answers")
    sub.add_parser("status", help="app version, voice, overlay, accessibility, uptime")

    p_logs = sub.add_parser("logs", help="recent AppLog lines")
    p_logs.add_argument("--tag", default=None, help="filter by log tag, e.g. Live")
    p_logs.add_argument("-n", type=int, default=100, help="lines (max 500)")

    sub.add_parser("tasks", help="current + recent tasks")
    sub.add_parser("screen", help="current serialized screen (needs accessibility)")

    p_cmd = sub.add_parser("command", help="run a chat command in the app")
    p_cmd.add_argument("text", help="command text, e.g. \"play rathinamo on spotify\"")
    p_cmd.add_argument("--timeout", type=int, default=130,
                       help="HTTP wait seconds (server caps a turn at 120)")

    p_ans = sub.add_parser("answer", help="answer a pending send confirmation")
    p_ans.add_argument("answer", choices=["yes", "no"])

    args = parser.parse_args(argv)
    config = load_config_file()
    host = resolve("host", args.host, "JARVIS_BRIDGE_HOST", config, "127.0.0.1")
    port = resolve("port", args.port, "JARVIS_BRIDGE_PORT", config, DEFAULT_PORT)
    token = resolve("token", args.token, "JARVIS_BRIDGE_TOKEN", config, "")
    if not token:
        print(
            "error: no bridge token. Pass --token, set JARVIS_BRIDGE_TOKEN, "
            "or put token in %s" % CONFIG_PATH,
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        port = int(port)
    except (TypeError, ValueError):
        print("error: port must be a number", file=sys.stderr)
        sys.exit(2)

    opener = build_opener(args.tailscale)
    base = "http://%s:%d" % (host, port)

    if args.cmd == "health":
        status, text = call(opener, base, token, "GET", "/health")
        payload = json.loads(text) if text else {}
    elif args.cmd == "status":
        status, text = call(opener, base, token, "GET", "/status")
        payload = json.loads(text)
    elif args.cmd == "logs":
        n = max(1, min(500, args.n))
        path = "/logs?n=%d" % n
        if args.tag:
            path += "&tag=" + urllib.parse.quote(args.tag)
        status, text = call(opener, base, token, "GET", path)
        payload = json.loads(text)
    elif args.cmd == "tasks":
        status, text = call(opener, base, token, "GET", "/tasks")
        payload = json.loads(text)
    elif args.cmd == "screen":
        status, text = call(opener, base, token, "GET", "/screen")
        payload = json.loads(text)
    elif args.cmd == "command":
        status, text = call(
            opener, base, token, "POST", "/command",
            body={"text": args.text}, timeout=args.timeout)
        payload = json.loads(text) if text else {}
    elif args.cmd == "answer":
        status, text = call(
            opener, base, token, "POST", "/answer",
            body={"answer": args.answer})
        payload = json.loads(text) if text else {}
    else:  # pragma: no cover - argparse enforces choices
        parser.error("unknown command")

    if args.json:
        print(json.dumps(payload, indent=2))
    else:
        render(args.cmd, payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
