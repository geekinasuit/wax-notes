<!--
Founding design input for wax-notes, written by Christian in a Claude chat and committed verbatim
on 2026-11-22. Do not edit the body to track later decisions; record those in
thoughts/shared/plans/2026-11-22-roadmap.md (decision register) and tickets, and cite the brief by
section number.

Placeholder resolution (operator, 2026-11-22): the app name is `wax-notes`. The one placeholder
in the original body, `<app>` in the §5.1 HKDF info string, is resolved to `wax-notes` below; that
is the only edit to the original text. The string is a wire-level domain-separation constant,
permanent once notes exist; see the roadmap's decision register (D-6).
-->

# Encrypted Nostr Notes: Design Brief

> Working name: `wax-notes`. This brief is the input for implementation. Items marked [DECISION] are settled. Items marked [SPIKE] must be checked before building on them. Items marked [OPEN] still need an answer from the product owner.

## 1. Product summary
A Google-Keep-style app for small notes. It is end-to-end encrypted, local-first, and syncs over Nostr. Each note is private by default. A note can be shared with specific humans and with AI agents (e.g. agents in Block's Buzz workspace), each with a permission level. The UX is a notes app (a card grid with quick capture), not a chat client.
Key scenario: a user and their agents discuss something in Buzz. An agent with append access adds items to a shared shopping-list note. The user sees which items the agent added. The agent cannot see any of the user's other notes.
### Goals
- Note types: checklist, plain text, rich text.
- Platforms: Android, iOS, desktop. The same notes appear on every device.
- Fully offline-capable. Sync is eventual and conflict-free.
- Works with any Nostr relay. A relay the user controls guarantees the data stays available. The Buzz relay is a supported option, not a requirement.
- Sharing is private to its participants: relays must not learn who shares a note with whom.
- Per-note roles for humans and agents, with clear authorship where the role calls for it.
### Non-goals (v1)
- Public or published notes.
- Real-time cursor presence (could come later via ephemeral events).
- Forward secrecy within an epoch (see §6.4).

## 2. Platform & stack
- [DECISION, confirm] Kotlin Multiplatform with Compose Multiplatform, targeting Android, iOS and desktop JVM.
- Nostr protocol layer: evaluate Quartz (Amethyst's KMP Nostr library) for events, signing, NIP-44, NIP-59 and relay clients. [SPIKE] Confirm its iOS target support and NIP-44/NIP-59 coverage.
- Local storage: SQLite via SQLDelight. [OPEN] Should local data be encrypted at rest (SQLCipher), or should we rely on OS storage encryption?
- Key management, in order of preference: NIP-46 remote signer (bunker), all platforms; NIP-55 external signer (Amber), Android; Local key in the platform keystore or keychain, as a fallback.

## 3. Conflict resolution: CRDT, not OT
[DECISION] Use a CRDT. OT needs a central server to put every op in one total order. Nostr relays are dumb, there can be several of them, and ops arrive out of order. With a CRDT, merging across relays and devices is just taking the union of the events.
- Rich text and plain text: a sequence/rich-text CRDT. Candidates: Loro (native rich text, Rust core with uniffi FFI → Kotlin bindings); Yjs via y-crdt (yrs); Automerge (Peritext-style marks, automerge-java).
- Checklists: a list CRDT for item order, plus a per-item map of LWW registers (text, checked, createdBy).
[SPIKE] CRDT selection. Evaluate against: (1) usable bindings on Android, iOS and JVM (same library on all three); (2) decode-before-apply: an incoming update can be decoded into inspectable ops before merge — needed for permission validation (§6.3), blocking requirement; (3) rich-text quality and snapshot/compaction support; (4) encoded update/snapshot size (NIP-44 limit, §5.4).

## 4. Local-first architecture
UI (Compose) → Note repository → CRDT docs (one per note); Local DB (CRDT state, op log, keys, per-relay cursors); Sync engine → N relays (read/write sets per user) → Signer (NIP-46/55/local).
- Every edit applied to local CRDT first and persisted immediately. UI never waits on network.
- Sync engine batches local ops (debounce ~1–2s, flush on background/close), encrypts, signs, publishes to every write relay.
- Inbound events deduped by event ID, decrypted, validated (§6.3), merged. CRDT merges idempotent.
- Keep a since cursor per relay; backfill from cursor on reconnect.

## 5. Data model & Nostr events
### 5.1 Identifiers & keys (per note)
- note_id: random 32 bytes, never in plaintext on a relay.
- note_key[epoch]: random 32-byte symmetric key, rotated on revocation (§6.4).
- channel_key[epoch]: a secp256k1 keypair derived as HKDF(note_key[epoch], info="wax-notes/channel/v1"). Every member can derive it. Outer events are signed by the channel key, never by a member's real key. Relays see only an opaque per-note pubkey.
### 5.2 Event layering
- Outer event (relay sees): signed by channel_key. Content is a NIP-44 v2 payload encrypted with note_key[epoch] as conversation key. Tags minimal: no p tags, no note ID. A d tag only on addressable events, holding an opaque value derived from the note key.
- Inner event (members see after decrypting): a fully signed Nostr event by the author's real key. Content is the op payload. Gives verifiable authorship; relay learns nothing about participants.
Clients subscribe with {"authors": [channel_pubkey, ...]} filters.
### 5.3 Event kinds (numbers TBD; choose during Buzz upstreaming §8; Buzz reserves 40000–49999; addressable use 30000–39999)
NOTE_OP (regular): CRDT update batch + deps. NOTE_INTENT (regular): a suggestion (§6.2). NOTE_SNAPSHOT (addressable): full CRDT state at a frontier. NOTE_MEMBERSHIP (addressable): owner-signed roster with roles, current epoch, note type. KEY_ENVELOPE (NIP-59 gift wrap kind 1059 to each member): note_id, epoch, note_key, role, sharer pubkey. USER_INDEX (addressable, NIP-44 to self): personal view state (§5.5).
### 5.4 Size limit
NIP-44 v2 plaintext capped at 65,535 bytes. Snapshots of large rich-text notes/images can exceed this. Options: chunk snapshots, or store large payloads as encrypted Blossom blobs referenced by hash. [SPIKE] Measure typical snapshot sizes with the chosen CRDT.
### 5.5 Shared vs personal state
- Shared (inside note CRDT): title, body/items, note type, color.
- Personal (inside USER_INDEX): pinned, archived, labels, sort order, and the list of note IDs/keys the user holds.

## 6. Sharing, roles & permissions
### 6.1 Read access is encryption
Private note has a KEY_ENVELOPE only to the owner (or only in USER_INDEX). Sharing = sending a gift-wrapped KEY_ENVELOPE to recipient's pubkey. Relay sees only that someone sent the recipient a gift wrap. Agents are just pubkeys; an agent only receives keys for notes explicitly shared with it.
### 6.2 Roles
owner (read yes / write anything incl. membership / note-level attribution); editor (read yes / any content op / note-level only attribution); appender (read yes / add new content in its own namespace only (§6.3), edit/delete only what it added / per item or block attribution); suggester (read yes / intents only NOTE_INTENT, not merged until owner/editor accepts / per suggestion); reader (read yes / nothing). Accepting a suggestion turns it into a NOTE_OP authored by the accepting user.
### 6.3 Write access is client-side validation
Relay enforces nothing. Every client validates every inbound op before merging: (1) inner event signature valid and inner pubkey in current NOTE_MEMBERSHIP for that epoch; (2) NOTE_MEMBERSHIP signed by owner; (3) op allowed by author's role — editor/owner any content path; appender only touches appends/<author_pubkey>/… in the CRDT doc; suggester/reader no NOTE_OP accepted; (4) invalid ops dropped and logged locally, never merged or re-published. Namespace isolation is why decode-before-apply is a blocking spike criterion (§3). Fallback if no library can be inspected before applying: appenders also write intents, each client materializes them deterministically.
### 6.4 Revocation & epochs
Removing/downgrading a member: owner generates note_key[epoch+1], publishes new NOTE_MEMBERSHIP, re-wraps new key to remaining members, writes fresh NOTE_SNAPSHOT under new epoch. After rotation, clients ignore new ops under old epoch. Limitation: a removed member keeps whatever they already decrypted (true of any E2EE). MLS over Nostr (Marmot) could be evaluated later; out of scope v1.
### 6.5 Signing cost
Every inner op needs author signature. With NIP-46 that's a network round trip per batch. Mitigate by debouncing/batching. [OPEN] If too slow, add per-device subkeys authorized in NOTE_MEMBERSHIP or USER_INDEX.

## 7. Agent integration
Ship a small notes SDK for agents (Rust core/bindings, because Buzz is Rust): decrypt, validate, merge, publish for the agent's own role. Expose as tools (e.g. an MCP server): list_shared_notes(), read_note(id), append_items(id, items), suggest(id, change), edit_note(id, ops) (editors only). An agent can only list notes it holds envelopes for. Least privilege enforced cryptographically.

## 8. Relays & Buzz
Any relay: user configures read/write relay sets (NIP-65 style); recommend at least one relay the user controls. Buzz relay: validates incoming events against a registered kind list; new kinds must be declared in buzz-core's kind.rs (ALL_KINDS); clients auth with NIP-42 and NIP-29 membership. Our kinds likely rejected until registered. [SPIKE] Clone block/buzz, find kind.rs and ingest.rs (some docs say crates/buzz-core/src/kind.rs; verify), confirm how unknown kinds and gift wraps (1059) are handled. Plan: (1) develop against generic relay (strfry or nostr-rs-relay); (2) run a local Buzz relay with our kinds patched in; (3) upstream a PR to block/buzz registering the kinds. Privacy caveat: on NIP-42 auth-required relays (incl. Buzz), the relay learns which real pubkey subscribes to which channel pubkeys, partly undoing §5.2 participant privacy. [OPEN] Is this acceptable for Buzz-hosted notes?

## 9. UX notes
Card grid (masonry), quick-capture bar, note-type picker (text/rich text/checklist). Share sheet: pick people or agents (npub, NIP-05, or a Buzz agent directory), then choose a role. Per-note sharing indicator (avatars); appender items carry a small author badge; editor notes show a contributors line. A suggestions tray on notes with suggesters: accept/reject. Never show a note to an agent implicitly; sharing is always explicit.

## 10. Milestones
0. Spikes: CRDT selection (§3), Quartz platform coverage, snapshot sizes (§5.4), Buzz ingest behavior (§8).
1. Local single-user app: all three note types, card grid, local persistence, no network.
2. Single-user sync: channel keys, outer/inner events, ops and snapshots, multi-relay sync, USER_INDEX, same notes on all devices.
3. Sharing with humans: key envelopes, membership, roles, validation, epochs and revocation.
4. Agents: appender namespaces, suggestions, agent SDK and MCP tools.
5. Buzz: local Buzz relay testing, then the upstream kind registration PR.

## 11. Testing requirements
CRDT convergence property tests (random op interleavings across 3+ simulated devices and 2+ relays, with partitions and duplicate delivery). Validation tests (forged inner signatures, ops from removed members, appender writes outside its namespace, old-epoch ops after rotation — all rejected). Encryption (NIP-44 test vectors; confirm outer events contain no plaintext note IDs, member pubkeys or content). Offline (edits on two offline devices that merge correctly on reconnect).

## 12. Open questions (summary)
- Encryption at rest for the local DB (§2).
- Per-device subkeys if remote-signer latency hurts (§6.5).
- Whether NIP-42 subscription metadata leakage on Buzz is acceptable (§8).
- App name (resolved: wax-notes).
