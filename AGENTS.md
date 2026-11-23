# wax-notes — Agent Instructions

STOP. Check for compressed form before reading further:

1. Does `AGENTS.compressed.md` exist in this directory?
2. YES → read that file; follow its instructions only; do NOT continue reading this file.
3. NO → continue below.

BEFORE responding to any user request, execute the bootstrap chain below in order.

## Bootstrap chain — execute exactly one branch (stop after match)

```
when {
  `/opt/geekinasuit/agents/internal/AGENTS.compressed.md` exists → read+follow; exit chain
  `/opt/geekinasuit/agents/internal/AGENTS.md` exists → read+follow; exit chain
  `/opt/geekinasuit/agents/public/AGENTS.compressed.md` exists → read+follow; exit chain
  `/opt/geekinasuit/agents/public/AGENTS.md` exists → read+follow; exit chain
  `~/.geekinasuit/agents/public/AGENTS.compressed.md` exists → read+follow; exit chain
  `~/.geekinasuit/agents/public/AGENTS.md` exists → read+follow; exit chain
  else → skip; proceed to project-specific section below
}
```

Do not read multiple branches. Stop immediately after the first match. "Follow" includes the
operator layer's own mandatory bootstrap steps (machine facts, compressed-format conventions, the
TOOLS catalog) and its on-demand PR workflow before any PR work.

---

## What this project is

wax-notes is a Google-Keep-style notes app: end-to-end encrypted, local-first, syncing over Nostr,
with per-note sharing to humans and AI agents (including agents in Block's Buzz workspace) at a
chosen role. Android first, then desktop (JVM), then iOS.

- **Founding design:** [`docs/design-brief.md`](docs/design-brief.md). Read it before design work;
  cite it by section ("brief §6.3"). Don't edit its body to track decisions.
- **Current plan and decisions:**
  [`thoughts/shared/plans/2026-11-22-roadmap.md`](thoughts/shared/plans/2026-11-22-roadmap.md):
  build strategy, milestones, the decision register (D-n), open questions (O-n) and risks (R-n).
- **License:** Apache-2.0.

## Build and test

- **Bazel, bzlmod only** (no `WORKSPACE`, no Gradle). `bazel test //...` is the green gate,
  locally and in CI (`.github/workflows/ci.yml`).
- **Declare every shared module with `kmp_module`** (`bazel/kmp/kmp.bzl`). It makes the library,
  one target that runs every test class in commonTest (whatever its file's name; it fails if there
  are none), and the guard that scans commonMain for platform references.
- **KMP discipline** (roadmap, "Build strategy"): shared code lives in
  `shared/<module>/src/commonMain/kotlin` with tests in `src/commonTest/kotlin`; commonMain
  uses no `java.*`, `javax.*` or `android.*` API, including implicit `java.lang` (`System`,
  `Thread`); libraries used there must publish iOS artifacts;
  platform capabilities are interfaces implemented under `platform/<target>`; no `expect`/`actual`
  yet; tests use `kotlin.test`. Bazel compiles commonMain for the JVM today; the iOS milestone
  (#7) compiles the same sources for Kotlin/Native using the operator's rules_kotlin fork.

## Invariants worth knowing before you change things

From the brief; each becomes test-held as it is implemented.

- **Relays learn nothing about participants.** Outer events are signed by the per-note channel
  key, carry no `p` tags and no plaintext note id (brief §5.2). Tests assert outer events
  contain no plaintext ids, member pubkeys or content (brief §11).
- **Every inbound op is validated before merge** against membership, epoch and role; invalid ops
  are dropped and logged locally, never merged or re-published (brief §6.3).
- **The UI never waits on the network.** Edits apply to the local CRDT and persist first
  (brief §4).
- **Sharing is always explicit.** No note reaches an agent implicitly (brief §9).

## Working in this repo

- VCS is **jj** (non-colocated; GitHub origin `geekinasuit/wax-notes`). Commit files by explicit
  path. Every change goes through a bookmark and a PR, opened with `gh-pr-create.main.kts`
  (operator TOOLS catalog), never raw `gh`. Agents never merge their own PRs without explicit
  one-time permission.
- **Tickets are public GitHub issues** on `geekinasuit/wax-notes` (an open-source project files
  tickets in the clear). Use `tickets.main.kts` with `--backend github --repo
  geekinasuit/wax-notes` for every ticket operation; filing needs `--acknowledge-live-write`. No
  `thoughts/shared/tickets/` store. Reference from PRs with `fixes #N` / `see #N`.
- `thoughts/` is tracked and public: write nothing there that shouldn't be in the clear.
- A plan in `thoughts/shared/plans/` comes before a substantial implementation: prose and light
  pseudocode, no full code. Research and spike reports go in `thoughts/shared/research/`.
- When a spike or ticket settles a decision, update the roadmap's decision register in the same PR.
- Test first where you can. A bug fix starts with a test that reproduces it. Mutation-check a new
  test: break the code on purpose and confirm it fails.
- `AGENTS.md`, plans, research and workflows carry a `.compressed.md` sibling; any edit to the
  `.md` updates the compressed form in the same commit. Tickets and handoffs are exempt.
