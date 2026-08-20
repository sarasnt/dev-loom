# Reaching a source that only your machine can reach

## Problem

DevLoom's backend runs in a container. Some sources are only routable from the host.

On the machine this was found on, the backend container reaches the public internet fine and
cannot reach **any** RFC1918 address at all — not the corporate Bitbucket at `10.235.0.10`, not
the LAN gateway, not even the host's own LAN IP:

| From | Target | Result |
| --- | --- | --- |
| host | `bitbucket.critical.pt` → 10.235.0.10 | 200 in 0.2ms |
| container | same | never connects |
| container | `api.github.com` | 200 |
| container | `jira.critical.pt` (public Cloudflare IP) | 401 — reached it |
| container | `192.168.1.1` (LAN gateway) | no connect |
| container | `192.168.1.74` (the host's own LAN IP) | no connect |

DNS resolves identically in both; this is routing, not name resolution. Jira and Confluence work
only because they happen to resolve to public addresses. Endpoint security software that blocks
forwarded traffic to private ranges is the usual cause, and it is not something DevLoom can
assume away — a self-hosted Bitbucket, Jira or GitLab behind a VPN is the normal case for the
users this product is for.

The immediate symptom is already fixed: the call fails in 5s and says "Could not reach CSW
Bitbucket" instead of hanging or, worse, reporting that nothing is failing. But the feature still
does not work on such a machine, and no amount of error-message polish changes that.

## The shape of the answer already exists

DevLoom solved this exact problem once. The host agent (`agent/devloom-agent.mjs`, 127.0.0.1:8765)
exists because a container cannot reach the user's filesystem, git, `claude` CLI or desktop
notifications. Network reachability is the same class of problem with the same solution: the agent
runs where the user's network access is.

So: when the backend cannot reach a source directly, ask the agent to fetch it.

## Design

### A single relay endpoint, not one per source

`POST /http/fetch` on the agent:

```
{ "url": "https://bitbucket.critical.pt/rest/build-status/1.0/commits/<sha>",
  "method": "GET",
  "headers": { "Accept": "application/json" },
  "body": null }
```

returns

```
{ "status": 200, "headers": { ... }, "body": "<raw response body>" }
```

One endpoint rather than per-connector endpoints, because the agent has no business knowing what
Bitbucket's build-status API looks like. Connectors keep owning their own API knowledge; the agent
only moves bytes.

### Only the hosts the user configured

An open relay on localhost would let any page in the browser use the agent to reach anything the
user's machine can reach, which is a materially worse thing to run than the agent already is.

The relay therefore refuses any URL whose origin is not the base URL of a configured source. The
backend sends the source instance id alongside the request, and the agent validates against a list
of allowed origins it is given at call time by the backend — which is the only component that
knows what sources exist. A request for an origin not on that list is refused with 403 and logged.

The existing localhost-origin allowlist and loopback bind still apply, so this is reachable only
from this machine, as with every other agent endpoint.

### Credentials never leave the backend's process… except they must

The relay carries the `Authorization` header the connector built, because the request is useless
without it. This is a real widening: a token that previously existed only inside the backend
container now crosses to the host agent process.

Mitigations, in order of importance:

1. The agent must never log headers. It already redacts nothing today because it never receives
   any, so this needs an explicit rule and a test of the log output.
2. Tokens are not persisted by the agent — used for the one request, never written to disk.
3. `SecretCipher`-encrypted storage is unchanged; this is transport only.

This is the main cost of the design and should be weighed deliberately rather than waved past. The
alternative — the user fixes their firewall — has no such cost, and for a user who *can* change
that firewall it is the better answer.

### When the relay is used

Direct first, relay on failure. A connector attempts its normal call; on `SourceUnreachableException`
it retries once through the relay, and remembers per source instance that the direct path failed so
subsequent calls skip straight to the relay until the next restart.

Not "always relay", because direct is faster, keeps tokens in one process, and works for every
source that is publicly routable — which is most of them.

Not "configure it per source", because the user cannot reasonably be asked to know which of their
sources are container-routable. The system can find out by trying.

### When the agent is not running

Then the source is unreachable and the existing honest error stands, extended by one line: that
starting the host agent may make this source reachable. This must not become a silent failure — the
whole point of the previous change was that "we could not ask" and "nothing is failing" are
different sentences.

## Surface

**Agent** — `POST /http/fetch`, with an allowed-origins check and a no-header-logging rule.
Node built-ins only, per the standing constraint that non-terminal agent features add no
dependencies.

**Backend** — `HostAgentClient.fetch(url, method, headers, body)`; a small `SourceFetcher` that
implements try-direct-then-relay and holds the per-instance "direct path is dead" flag. Connectors
call `SourceFetcher` instead of building their own `RestClient`, which also gives the timeouts from
`SourceHttp` a single home.

**Frontend** — none. This is invisible when it works; when it does not, the existing unreachable
state already says so.

## Out of scope

- Streaming responses through the relay. Every source call in question is a small JSON body.
- Using the relay for the LLM providers. Those are public endpoints and a different trust question.
- Making the agent a general proxy for the browser. The relay serves the backend only.

## Verification

1. With the container unable to reach a source, the connector succeeds through the relay and the
   Builds screen analyzes a real failure.
2. With the agent stopped, the same case reports unreachable, naming the agent as a possible fix.
3. A relay request for an origin that is not a configured source is refused with 403.
4. The agent's log output contains no `Authorization` header after a relayed request.
5. A publicly-routable source (GitHub) never touches the relay — verified by the agent's request
   count staying at zero across a sync.
