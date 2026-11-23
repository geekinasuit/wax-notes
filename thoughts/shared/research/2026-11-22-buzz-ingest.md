---
date: 2026-11-22
topic: How the Buzz relay treats wax-notes events
status: draft
ticket: "#6"
---

# How the Buzz relay treats our events

## Recommendation

1. **Brief §5.2 does not work on Buzz as written.** Registering our kinds is not enough. Buzz also
   requires the event's `pubkey` to equal the key the connection authenticated with. Only kind 1059
   is exempt. *Confidence: high (two independent checks in the source).*
2. **Keep the §5.2 design. Don't reshape it for Buzz.** Build on a generic relay first, as brief §8
   already plans. Decide how to publish to Buzz before milestone 6 (options below; D and G
   change the event shape, so choosing either later than milestone 3 is a migration).
3. **Design sync against the hazards found here** (see "What a sync design must handle"). Most hold on
   any relay. The design itself is left to the milestone 3 and 4 plans.
4. **Widen O-3.** Writes can leak as well as subscriptions, and by default durably: a database audit
   row ties the authenticated key to each stored event. For gift wraps that is normally the sender's key,
   but it is whatever admitted key the client connects with. For note writes it depends on the publishing
   option (O-4; under A it is only the channel key).
5. **Kind numbers (proposals only):** 8460, 8461, 38460, 38461, 38462. No collision found in the
   three clones. *Confidence: medium (unlisted users and open pull requests not checked).*

## Evidence base

Nothing in Buzz was built or run. Labels: **source read** = read in the code at the commit below;
**docs read** = read in a document; **code run** = we ran it (clones and greps only); **inference** =
our reasoning, not in the code.

| Repo | Commit | Commit date | License |
|------|--------|-------------|---------|
| `block/buzz` (`main`) | `10dd70f15228d9222fdf80f0d6004ddd3797416e` | 2026-11-22 | Apache-2.0 (`LICENSE`) |
| `nostr-protocol/nips` | `bec0fc89e75645e77a06d145598246efca844457` | 2026-11-16 | not checked |
| `nostr-protocol/registry-of-kinds` | `204f47cd83d488f3486fae82ef421b13517e0d80` | 2026-11-02 | not checked |

Unless it says otherwise, every `path:line` is source read in `block/buzz` at that commit. Short names:
`kind.rs` = `crates/buzz-core/src/kind.rs`; `lib.rs` = `crates/buzz-auth/src/lib.rs`;
`relay/` = `crates/buzz-relay/src/`; `ingest.rs`, `event.rs`, `req.rs`, `auth.rs`,
`side_effects.rs` = `relay/handlers/…`; `db/` = `crates/buzz-db/src/`. Roadmap R-2 (Kotlin
versions) does not apply: Buzz is Rust and we take no artifact from it.

## Kind registry: the brief has the wrong gate

- `kind.rs` exists where the brief guessed, and `ALL_KINDS` is at `kind.rs:725`.
- **`ALL_KINDS` does not gate ingest.** Its non-test uses are metric labels: `kind.rs:876` (used by
  `db/runtime/observability.rs:575` and `relay/metrics.rs:746`). `relay/metrics.rs:858` also iterates
  it. The rest is a duplicate-number test (`kind.rs:1030`).
- **The gate is `required_scope_for_kind`, `ingest.rs:598`.** It is a `match` on kind. The last arm is
  `_ => Err("restricted: unknown event kind")` (`ingest.rs:712`).
- The two lists differ in both directions, which shows they are separate. Kinds 41, 1063 and
  43001-43006 are in `ALL_KINDS` but have no arm, so clients cannot publish them. Kind 30350 has an arm
  (`ingest.rs:604`) but is not in `ALL_KINDS`.
- `CONTRIBUTING.md:510-560` (docs read) lists the steps for a new kind: constant in `kind.rs`, arm in
  `required_scope_for_kind`, side effects if any, search exclusion if private, tests.

## An EVENT from socket to store, in order

1. Connection permit, process-wide limit (`relay/connection.rs:389`). A connection that has not
   authenticated in 5 s is closed (`relay/connection.rs:31`, `:708-722`).
2. Frame size: default 512 KiB (`relay/config.rs:14`, `relay/connection.rs:1277`).
3. JSON parse (`relay/connection.rs:1404`).
4. Rate limits per authenticated pubkey (`relay/rejection.rs:52-110`). Defaults
   (`crates/buzz-auth/src/rate_limit.rs:90-139`): 10 frames/s, and 60 attempts/min to publish a
   non-ephemeral event (120/min for a key with a recorded owner, "agent"). Attempts that a later step
   refuses count too. HTTP has its own limit: 300 API calls/min per key, queries included
   (`relay/api/bridge.rs:243-275`).
5. Handler permit, else `rate-limited: too many concurrent requests` (`relay/connection.rs:1432-1446`).
6. Must be authenticated, else `auth-required: not authenticated` (`event.rs:719-738`).
7. `event.pubkey` must equal the authenticated pubkey unless kind 1059, else
   `invalid: event pubkey does not match authenticated identity` (`event.rs:744-754`).
8. Kind 22242 is refused (`event.rs:756`). Kind 24200 and the ephemeral range 20000-29999 leave here
   (`event.rs:771-931`). Everything else (and 28936) goes to ingest (`event.rs:964`).
9. The community must be active (`ingest.rs:2604`).
10. Relay-only and membership-notice kinds refused; 1059 refused over HTTP (`ingest.rs:2610-2635`).
11. Event id and Schnorr signature (`ingest.rs:2643`, `crates/buzz-core/src/verification.rs`).
12. **`created_at` within 900 s of server time, either way**, else
    `invalid: event timestamp too far from server time` (`ingest.rs:2685-2692`). No exemption by kind.
13. Content at most 256 KiB (`ingest.rs:2702`). A NIP-44 payload of 65,535 bytes fits (inference:
    base64 makes it about 88 KB).
14. Pubkey equals authenticated identity, again, same 1059 exemption (`ingest.rs:2711-2716`).
15. **Kind gate** (`ingest.rs:2719-2722`), then scope (`ingest.rs:2739`). NIP-42 grants every scope
    (`lib.rs:188-196`).
16. Ban or timeout check on the authenticated key (`ingest.rs:2748`); media references, when that mode
    is on (`ingest.rs:2750`, `relay/handlers/media_access.rs:13`).
17. Commands, feedback, reports and moderation are handled and return (`ingest.rs:2754-2806`).
18. Channel from the `h` tag (`ingest.rs:2839-2889`). If the kind requires one and it is missing:
    `invalid: channel-scoped events must include an h tag` (`ingest.rs:2891`).
19. If there is a channel: NIP-29 membership, role and archive checks (`ingest.rs:2945-2997`,
    `:3156-3176`).
20. Per-kind validators (`ingest.rs:3111-3496`).
21. Store. Replaceable (`ingest.rs:3766`), addressable (`:3774`, `d` length capped), else plain insert
    (`:3849`). `p` tags are indexed into `event_mentions` (`db/runtime/mod.rs:61`).
22. Audit row, then fan-out (`ingest.rs:3959`, `event.rs:474-518`).
23. `OK`.

## An unregistered kind

| Range | Over WebSocket | Over HTTP `POST /events` |
|-------|----------------|--------------------------|
| regular, replaceable, addressable, 40000 and up | `OK false`, `restricted: unknown event kind`; not stored | same text as an error |
| ephemeral 20000-29999 | **accepted and fanned out, not stored**; no kind check and no timestamp check (`event.rs:843-931`, `:1014-1134`) | rejected at the kind gate (`relay/api/bridge.rs:1480-1495`) |

Never a silent drop. Ephemeral events must still be signed by the authenticated key.
Order matters: the pubkey check (steps 7, 14) comes before the kind gate (step 15). So today a
channel-key-signed NOTE_OP on a member's connection gets the **pubkey** error, not the kind error. A
test that only looks for "unknown event kind" would miss the second problem.

More storage facts:

- Addressable replacement is by `(community, kind, pubkey, d)`; highest `created_at` wins, ties go to
  the lowest id (`db/store/replaceable.rs:104-230`, `:736-741`).
- **`OK true "duplicate:"` is not proof of storage** (`ingest.rs:3875-3880`, any `!was_inserted`).
  A losing (older) addressable event gets it. So does a deleted event sent again: the soft-deleted row keeps
  its key and the insert is `ON CONFLICT DO NOTHING` (`schema/tables/public/events.sql:40`,
  `db/store/event.rs:2034-2036`, `db/store/event_deletion.rs:282-288`), so it stays hidden.
- A superseded version is soft-deleted (`deleted_at`), not erased, for ordinary kinds
  (`db/store/event_deletion.rs:226-240`). So old snapshot ciphertext stays in the database after it is
  replaced.

## Gift wraps (kind 1059)

- **Accepted, WebSocket only** (`ingest.rs:635`, `:2624`).
- **From whom:** any authenticated, admitted connection. The wrap's own `pubkey` may be any key.
- **To whom:** anyone. Ingest does not require a `p` tag or check that the recipient is a member; we
  found no kind-1059 validation in `ingest.rs` beyond the general steps.
- **Reading:** a filter that can match 1059 must have `#p` values that all equal the authenticated key,
  else `restricted: p-gated events require #p matching your pubkey` (`req.rs:378-390`, `:1940-1975`;
  list at `kind.rs:211`). So finding wraps by recipient means authenticating as the key in their `p` tag,
  which a closed relay must have admitted. A filter for 1059 with non-empty `ids` is exempt, and no later
  WebSocket check covers 1059 (`req.rs:2130-2142`, `event.rs:220-237`, `crates/buzz-core/src/filter.rs:40-43`).
  So a client that knows the event id can fetch the wrap under any admitted key; decrypting it still takes
  the recipient's key. Over HTTP the "authenticated" key can be unproven; see `BUZZ_REQUIRE_AUTH_TOKEN` below.
- Never channel-scoped (`ingest.rs:2858`), not searchable (`schema/tables/public/events.sql:28`), does
  not start workflows (`event.rs:612-616`).
- **Blocker for a standard library:** NIP-59 says wrap timestamps SHOULD be tweaked and in the past
  (`nips/59.md:111-113`, docs read); NIP-17 says up to two days (`nips/17.md:59`, docs read). Buzz's
  900 s rule (step 12) rejects those. Its own end-to-end test uses a current timestamp
  (`crates/buzz-test-client/tests/e2e_nostr_interop.rs:589-616`). A wrap that fits the window gives up most
  of the tweak, and sent to other relays too, it shows its sending time there (inference).
- Kind 21059 (ephemeral gift wrap, `nips/59.md`) gets no pubkey exemption: the check is `== 1059`.

## NIP-42, membership and the pubkey rule

- **Over WebSocket, AUTH is required for both publishing and subscribing** (`event.rs:719-738`,
  `req.rs:109-122`). NIP-11 advertises `auth_required: true` (`relay/nip11.rs:167`, `:184`).
- **Over HTTP, proof of the key depends on `BUZZ_REQUIRE_AUTH_TOKEN`**, off in the code
  (`relay/config.rs:856-858`; the relay warns at `:1036-1041`). When off, and NIP-FI doesn't restrict the
  community, an unsigned `X-Pubkey: <hex>` header is accepted as the identity
  (`relay/api/bridge.rs:439-452`; `POST /events` `:1289`, `/query` `:1644`, `/count` `:2357`). When on,
  each request needs a NIP-98 signature. The shipped compose file and chart turn it on
  (`deploy/compose/.env.example:16`, `deploy/charts/buzz/values.yaml:120`); Buzz's own CI turns it off
  (`.github/workflows/_ci-relay.yml:384`). On a relay with it off:
  - Anyone can list a victim's gift wraps (ciphertext, times, sizes) by claiming the victim's key: the
    `p`-gate compares with the claimed key (`bridge.rs:1753-1754`). On a closed relay, claiming a
    member's key also passes the membership check, which reads the same key.
  - Publishing a channel-key event needs no login as the channel key, only the header. Admission still
    applies to the claimed key, so a closed relay must have admitted the channel key. The event's own
    signature is still checked (step 11). Gift wraps stay WebSocket-only (step 10). NIP-OA admission
    (option B) fails without a signed request (`relay/api/mod.rs:298-301`).
  - The audit row's "authenticated key" is then only a claimed key.

  So (inference) a local test relay should set it to true, or its results won't match a relay deployed
  from the shipped compose file or chart.
- **One identity per connection.** A second AUTH gets `auth-required: already authenticated`
  (`auth.rs:528-542`). NIP-42 says relays MUST treat every authenticated pubkey as authenticated
  (`nips/42.md:35`, docs read), so this differs from the spec.
- **Relay admission (who may AUTH).** Three switches, all off in the code (`relay/config.rs:860-866`,
  `:901`): `BUZZ_REQUIRE_RELAY_MEMBERSHIP`, `BUZZ_PUBKEY_ALLOWLIST`, `BUZZ_ALLOW_NIP_OA_AUTH`. The
  shipped deployments turn on membership and NIP-OA (`deploy/compose/.env.example:17-18`,
  `deploy/charts/buzz/values.yaml:121-123`). So expect **closed** in practice. Denial:
  `restricted: not a relay member` (`auth.rs:786`).
- **NIP-29 membership** applies only to events with a channel (an `h` tag holding a UUID). An event with
  no `h`, of a kind outside `requires_h_channel_scope` (`ingest.rs:887`), is community-global and needs
  no group membership. Our events should carry no `h`.
- **Pubkey must equal the authenticated key: yes**, twice (steps 7, 14), 1059 only exempt.

### Can a channel-key outer event publish? The options

| | How | Open relay | Closed relay | What Buzz learns |
|--|-----|-----------|--------------|------------------|
| A | Authenticate **as the channel key**, one socket per note key; or HTTP, each request signed by the channel key (`relay/api/bridge.rs:1404-1476`; signing isn't needed where `BUZZ_REQUIRE_AUTH_TOKEN` is off) | works, once the kinds are registered | refused unless the channel key is admitted (B or C) | for writes: channel key and IP, no real key (but see inference below) |
| B | A, plus a NIP-OA `auth` tag in which the note owner vouches for the channel key | not needed | works if NIP-OA is on and the owner is a member (`relay/api/mod.rs:294-321`) | a **durable** channel-key to owner link (`relay/api/mod.rs:664-715`) |
| C | Add the channel key as a relay member | not needed | works; an admin must add the key, or mint an invite it can claim (notes) | every member key is listed in the relay-signed kind 13534 event (`db/store/relay_members.rs:1106-1140`) |
| D | Send outer events **as kind 1059**; on either kind of relay, finding them needs `#p` = the reader's authenticated key | works today, no registration, but all as regular events (notes) | same, for admitted keys | connection's key (normally the sender's real key) per event, plus a `p` tag naming each reader (or the channel key, if readers authenticate as it) |
| E | Upstream: add our kinds to the 1059 exemption (`event.rs:745`, `ingest.rs:2712`) | works, once the kinds are registered | same, for admitted members | connection's key (normally the sender's real key) per event, durably |
| F | Upstream: several identities per connection, as NIP-42 says | works, once the kinds are registered | same admission problem as A | links the real key and the channel keys on one connection |
| G | Sign the outer event with the **member's own key**, with an opaque per-note value in a tag (inference) | works, once the kinds are registered, with an indexed tag (notes) | same, for admitted members | sender's real key per event, durably; **every admitted reader** sees who writes to which note |

Notes:

- **G against E.** G needs only the registration milestone 6 already plans, no change to Buzz's pubkey
  rule. It breaks brief §5.1 ("never by a member's real key") and §1's goal of hiding participants. E
  normally shows the operator the same key. So what asking for E buys is hiding the writer from the
  *other* relay users, not from the operator. In G's favour: one member can no longer replace or delete
  another's copies (end of "What a sync design must handle"). The risk shrinks but stays: a member can
  still delete its own ops, which others depend on (NOTE_OP carries "deps", brief §5.3), and an agent's
  recorded Buzz owner, who need not be in the note, can delete the agent's (`side_effects.rs:451-457`).
- **G needs an indexed tag.** Any other tag is matched only after the newest 1000 events of the rest of
  the filter are read, so a quiet note's ops can be missing with no error ("Tags and subscriptions").
  `#e` is indexed and takes 64 hex digits (`nips/01.md:143`, docs read), so an opaque value fits, though
  that isn't what `e` means (inference). The value must change with the epoch, or it links a note's epochs
  (inference; the member keys may link them anyway).
- **D and G lose the relay's single current version** of a snapshot, and D of a roster, so readers must
  choose or merge (inference from the facts cited). D: 1059 is a regular kind (`nips/01.md:96`, docs read;
  plain insert, step 21), so nothing is replaced and every version stays until deleted. G: replacement is by
  kind, pubkey and `d` (`nips/01.md:99`, docs read; "More storage facts"), so each member who signs one has
  their own slot for the note; a roster is owner-signed (brief §5.3), so readers can use the owner's alone.
  USER_INDEX is outside this if the user's own key signs it, which needs neither option.
- **D with per-reader `p` tags strands later readers.** The tags name the readers when the event is sent,
  so a member added later can't find earlier events by recipient; the id exemption helps only once it
  knows the ids (inference from the facts cited). That variant needs some way to hand history to new
  members; tagging the channel key instead doesn't, since a new member receives that key.
- **Rotation repeats admission.** Each epoch has a new channel key (brief §6.4), so B and C admit a key
  again at every rotation, and leave one owner link or member row per epoch. A needs no admission on an
  open relay.
- **C's admin step can be shared.** `POST /api/invites/claim` is exempt from the membership gate
  (`relay/api/invites.rs:12-15`), and one admin-minted invite can admit many keys: unlimited if
  `max_uses` is omitted, else up to 10,000 (`:69-73`, `crates/buzz-core/src/invite.rs:21`), for at most
  30 days (`:18`). The code isn't limited to channel keys, so anyone it is shared with can make other keys
  relay members too. Every claimed key still appears in the member list.
- **A's "no real key" covers writes only.** If readers subscribe on their own key, Buzz still logs that
  key next to the channel keys. Hiding readers too means each reader also authenticates as the channel
  key (one socket per note). Finding KEY_ENVELOPEs by recipient shows the recipient's key ("Gift wraps",
  with the exemption). So A alone does not settle O-3.
- B breaks in the obvious form. The owner link is first-write-wins. A second member vouching with a
  **different** owner key gets `Conflict` and is refused (`auth.rs:864-882`,
  `relay/api/mod.rs:610-632`). It works only if every member presents the **same** owner's tag, for
  example carried in the KEY_ENVELOPE. NIP-OA calls the tag a reusable capability
  (`docs/nips/NIP-OA.md`, docs read). The owner is then exposed, can delete the key's events
  (`side_effects.rs:451-456`), and a ban on the owner reaches the key (`auth.rs:60-109`).
- **B also trusts every reader.** The link is recorded from any valid tag on any admitted login,
  whatever the relay mode (`auth.rs:812-821`), and is "set once and never cleared"
  (`relay/api/mod.rs:578-580`, `db/store/user.rs:418`; user-deletion code not read). A reader holds the
  channel key, so a reader can log in first with a tag naming itself as owner. Members presenting the
  real owner's tag then get `Conflict` for that epoch's key. On a closed relay the reader must itself be
  a member for that login to be admitted.
- E is a small code change but a policy request. It also changes nothing for kind 5: deletions of our
  events would still need the channel key (or its recorded owner) to be the authenticated key. The pull
  request (milestone 6): constants in `kind.rs` and `ALL_KINDS`; arms in `required_scope_for_kind`; both
  pubkey exemptions; `is_global_only_kind`; the search exclusion (schema and migration); optionally `bounded_kind_label`
  (`event.rs:35`); tests.
- Inference: A hides the real key from the relay's records, but sockets opened together from one IP are
  easy to join. The access log writes the peer address and key of each login (`relay/access.rs:932-950`).

## Tags and subscriptions

- **Required:** nothing for a new channel-less kind. No `h`, no `p`. `d` is capped in length for addressable
  kinds (`ingest.rs:3776-3783`).
- **Do not add `h`.** An `h` with a UUID makes the event channel-scoped unless the kind is in
  `is_global_only_kind` (`ingest.rs:802`).
- **Indexed or pushed to SQL:** `authors`, `kinds`, `ids`, time, `#e`, `#a`, single `#p`, and `#d` when
  every kind in the filter is 30000-39999 (`req.rs:1330-1499`). For our kinds, other tags are matched
  after the query's `LIMIT` (`req.rs:690`, then EOSE at `:728`), so matches can be missing with no error.
- **`authors` on non-member keys: accepted.** No check compares filter authors with members
  (`req.rs:87-533`, `:1368`). Live fan-out has an author index (`relay/subscription.rs:840-885`).
- **The brief's filter is refused.** `{"authors":[channel_pubkey,…]}` can match gated kinds, so it is
  closed with the `p-gated` message (`req.rs:378-390`, `:1943`). **Always send `kinds`.**
- **Any admitted key can read our events**, except under D (gated, "Gift wraps"). A channel-less kind that
  is not gated is delivered to every connection that asks (`event.rs:239-241`). A member can list
  `{"kinds":[NOTE_OP]}` and see every channel key (under G, member key), size and time in the community.
  Content stays encrypted.
- **Search:** depends on how the database was made. Migrated fresh installs index only 0, 9, 40002,
  45001, 45003 (`migrations/0008_fresh_install_search_allowlist.sql`). Older populated databases
  (`migrations/0033_private_managed_agent_fts.sql`) and the desired-state schema
  (`schema/tables/public/events.sql:28`) use a skip-list, so they **would** tokenize our ciphertext
  unless the pull request excludes our kinds.
- Limits: 10 filters per REQ (`relay/protocol.rs:12`), 1000 stored events per filter, newest first
  (`req.rs:1360-1364`, `db/store/event.rs:34`, `:773-775`), 1024 subscriptions (`req.rs:26`), live author
  index budget 4096 kind-by-author keys (`relay/subscription.rs:833`).

## Kind ranges and registered kinds

**40000-49999:** `ARCHITECTURE.md:177` calls it "Buzz custom kinds" (docs read). No range check in
`kind.rs` or the ingest gate enforces it. Comments in `kind.rs:589-690` name sub-ranges. In code only
20000-29999 (ephemeral), 10000-19999 plus 0, 3 and 41 (replaceable) and 30000-39999 (addressable) have
meaning (`kind.rs:520-527`, `:884-900`). Anything from 40000 up is stored as regular. Kinds are 16-bit.

Registered in `ALL_KINDS` (`kind.rs:725-869`):

- 0, 1, 3, 5, 7, 9, 41, 1059, 1063, 1617-1619, 1621, 1630-1633, 1984
- 8000-8003, 9000-9002, 9005, 9007-9009, 9021, 9022, 9030-9033, 9035, 9036, 9040-9044, 9050
- 10000-10003, 10030, 10100, 13534, 13535
- 20001-20003, 24134, 24200, 24242, 24245, 24810, 27235, 28936
- 30000, 30003, 30023, 30030, 30078, 30174-30180, 30300, 30315, 30617, 30618, 30620-30623
- 39000-39003, 39005-39009
- 40002-40008, 40010, 40011, 40099, 40100, 40901, 40902, 41001, 41010-41012, 42000, 43001-43006
- 44100, 44101, 44200, 45001-45003, 45010, 45011, 46001-46007, 46010-46012, 46020, 46030, 46031
- 48001, 48100-48104, 48106, 49001

Constants outside `ALL_KINDS`: 22242, 24243, 30350, 30382 (`kind.rs:77`, `:81`, `:112`, `:944`).

### Candidate numbers (proposals only; freeze at the first synced note, milestone 3)

| Event | Kind | Why |
|-------|------|-----|
| NOTE_OP | 8460 | NIP-01 "regular" is 1000-9999 (`nips/01.md:96`, docs read); it gives 40000 and up no class |
| NOTE_INTENT | 8461 | next to it |
| NOTE_SNAPSHOT | 38460 | addressable must be 30000-39999, on Buzz too |
| NOTE_MEMBERSHIP | 38461 | next to it |
| USER_INDEX | 38462 | next to it |

Fallback if Buzz wants its own range: 42010 and 42011 for the two regular kinds (only 42000 is used in
the 42000s). The three addressable kinds cannot move there. `CONTRIBUTING.md:514-521` (docs read) tells
contributors to use a `4XXXX` number from "the appropriate sub-range defined in `kind.rs`", so upstream
may ask for this fallback (inference).

**Collision check, code run, 2026-11-23.** All text files in the three clones, at the commits above:
`grep -rnIiE '<pattern>' --exclude-dir=.jj --exclude-dir=.git`. Decimal, proposals and fallback:
`(^|[^0-9a-f])(3?8_?46[0-9]|42_?01[01])([^0-9a-f]|$)`. Hexadecimal:
`0x0*(21_?0[cd]|96_?3[cde]|a4_?1[ab])([^0-9a-f]|$)`. Every hit was a Buzz
changelog pull-request number or a secp256k1 test constant, none a kind. The decimal pattern can't see
`KIND8460`; the same grep for `kinds?[_ ]?(3?8_?46[0-9]|42_?01[01])` had no hits. No range row in the NIPs
list covers them. Not checked: computed values, open pull requests, deployed apps that list themselves
nowhere, other relays' private kinds.

USER_INDEX alternative: kind 30078 (NIP-78 app data) is already accepted by Buzz today
(`ingest.rs:602`), needs no registration, and is signed by the user's own key, so the pubkey rule is not
a problem. Cost: anyone admitted can see it exists. Buzz has an author-only list (`kind.rs:141`) that we
could ask to join with our own kind (the list is by kind; see decision 4).

## What Buzz learns (for O-3)

**In the code:**

| What | Where it goes | Evidence |
|------|---------------|----------|
| Login: key, peer address, connection id, time (client IP too if a header is configured) | access log line | `relay/access.rs:853-950` |
| Each subscription: authenticated key, connection id, **subscription id text**, kinds, the **first 10 authors** and the total count, `p` values, times | access log line | `relay/access.rs:61-139`, `:431-485` |
| Each EVENT over WebSocket: authenticated key, kind, event id, outcome | access log line | `relay/access.rs:519-605` |
| Each HTTP request: route, key and whether it was proven, peer address, status; for `POST /events` also kind, and event id if ingest ran | access log line | `relay/access.rs:1328-1450`, `relay/api/bridge.rs:1347-1386` |
| Each newly stored event: **authenticated key**, event id, kind | `audit_log` table, hash-chained, on by default | `event.rs:647-685`, `relay/config.rs:1319`, `schema/tables/public/audit_log.sql` |
| Event: outer pubkey, tags, ciphertext, size, arrival time | `events` table | `schema/tables/public/events.sql:8-39` |
| Each `p` tag (so: the recipient of a gift wrap that has one) | `event_mentions` table | `db/runtime/mod.rs:61-110` |
| Live subscriptions: connection to author keys | memory | `relay/subscription.rs:840-885` |

Key point: audit records the **authenticated** key, not the event signer. For gift wraps,
`ingest.rs:3925` passes the connection's key to the audit row. (The metrics path does the same on
purpose, `ingest.rs:2483-2485`.) So Buzz stores the key each gift wrap was sent under, and the event id
joins that to the recipient's `p` tag. Nothing ties that key to the sender inside the seal (kind 1059 is
exempt from the pubkey rule, `ingest.rs:2711-2712`):

- We assume it is normally the sender's real key. NIP-59 says clients SHOULD support authenticating with
  it (`nips/59.md:104`, docs read), which shows clients can, not that they do; we have no evidence of what
  clients send. Where it is the real key, sender hiding does not hold against this relay. Every "normally"
  in this report rests on this assumption.
- It can be any other key the relay admits: on an open relay a fresh one, or the channel key under A, which
  instead ties the recipient to the note. A closed relay must have admitted that key. The peer address may
  still link it to the sender (inference).

Our outer events work the same way under D and E. Under G their own pubkey is the real key; under B the
owner is linked; under F the operator sees the real key on the same connection.

Access lines go to stdout, at INFO when served. The default filter is `buzz_relay=info`
(`relay/main.rs:7`) and the access target is `buzz_relay::access` (`relay/access.rs:36`). That the
filter covers the target is library behaviour we infer, not something we ran. The compose file sets
`buzz_relay=info` too (`deploy/compose/.env.example:25`). Against that, a code comment says "Production
runs RUST_LOG=error" (`ingest.rs:3897-3898`). If that is still true of the block-hosted service, its
access lines (INFO and WARN) are not written, and only the audit row and tables remain. The OpenTelemetry export turns these lines off by default (`relay/telemetry.rs:193`). Event content, tags and search text are
documented as never logged (`relay/access.rs:1-24`).

**Inference, not in the code:**

- Where readers subscribe on their own keys, a Buzz operator can rebuild the sharing graph of Buzz-hosted
  notes: every key that subscribes to the same channel key is a likely participant. The log's 10-author cap
  limits one log line, not the relay, which sees the whole filter.
- Put nothing meaningful in subscription ids. They are logged as sent.
- Retention of logs and audit rows is the operator's. We found no setting for it in the files read.
- Other members cannot see subscriptions. They can see outer events and their timing. Under D they
  can't find them by recipient, but can fetch any whose event id they know ("Gift wraps").

## What a sync design must handle

Facts, then the hazards they cause (inference): requirements and open problems, not a design. One rule is
complete on its own: **always put `kinds` in filters.** Buzz refuses the brief's filter without them;
elsewhere it is harmless. It hides nothing (decision 4).

**Timestamps.** Buzz refuses a `created_at` more than 900 s from its clock, gift wraps included (step 12);
NIP-59 and NIP-17 want wraps tweaked up to two days back. Edits are published later than made (brief §4),
and we expect shares to be too (brief §1, "fully offline-capable").

- What was stamped when queued is refused by Buzz after 15 minutes. Other relays may store it behind a
  reader's cursor, and for a gift wrap that is a missed key.
- An edit queued across a rotation must not be lost: clients ignore new ops under the old epoch (brief §6.4).

**Duplicates.** Wrapping an inner event again gives a new outer id, so brief §4's "deduped by event ID" is
not enough. An event id does not commit to its signature (`nips/01.md:19-42`, docs read), and a NIP-59 rumor
has none (`nips/59.md:20`, docs read). A KEY_ENVELOPE needs NIP-17's check that seal and rumor have the same
pubkey (`nips/17.md:57`, docs read).

- Recording an id before the event is verified and accepted can hide the genuine one: any reader can wrap a
  copy with a broken signature (or into another note they are in), and an honest op can arrive before the
  roster that allows it.
- Events held for a roster need a bound, which any member can fill.
- *Open:* which roster version judges an op, and whether an inner event names its note (else an op can be
  replayed into another note its author belongs to).

**Addressable events.** A relay compares only `created_at`, then id (`nips/01.md:99-101`, docs read; Buzz
too, and its `OK true` is not proof of storage, "More storage facts").

- A stale version stamped now replaces a newer one. For USER_INDEX that can drop the only relay copy of a
  note's key (brief §5.5, §6.1). Stamping at edit time instead loses the older write.
- Fetch-then-publish is not atomic, so two devices can overwrite each other. Dropping a queued version is
  safe only if the current one already contains its change.
- Each relay has its own current version. An unreachable relay is not one without a version, and must not
  block the others. A version stamped by a fast clock can demand a later stamp than another relay accepts.
- *Open:* the brief defines no merge for USER_INDEX (milestone 3) or the roster (milestone 4; only the
  owner can rebuild it).

**Catching up with a `since` cursor (brief §4).** `since` filters on `created_at` (`nips/01.md:145`, docs
read). A relay returns the newest matches and may return fewer than asked (`nips/01.md:151`, docs read);
Buzz, at most 1000 per filter (limits above). A cursor at the newest event seen loses events that were:

- stamped earlier but stored later. On Buzz, two events stored together, gift wraps included, can be
  stamped 30 minutes apart (900 s each way). Elsewhere NIP-17's tweak alone can give gift wraps two days;
- past the page limit, which also cuts a backfill that doesn't page. If more matches than the limit share
  one `created_at`, as any member can arrange, paging by `since`/`until` (both inclusive) can't reach the
  rest. Buzz's nonstandard `before_id` can (`relay/protocol.rs:147-179`, `db/store/event.rs:874-890`);
- under a channel key learned later, by a share or a rotation;
- held, then dropped or lost in a restart.

A future-stamped event must not move the cursor past the present.

**Rate limits** (step 4): brief §4 debounces 1-2 s, so one note under steady editing can reach them. Under
A to C the budget is the channel key's: all members share it, and any reader who can authenticate as that key
can use it up, stopping every writer of the note while it keeps sending. Under D, E and G it is normally the
writer's own.

**What any member can do to the relay's copies.** Not Buzz-specific; D and G differ (options notes). Every
member, readers included, holds the channel key (brief §5.1). Where a relay honours NIP-09 (optional, and a
SHOULD), a kind 5 signed by that key deletes, with no login unless the relay asks for one (`nips/09.md`, docs
read). Buzz does, when the authenticated key is the author or the author's recorded owner (step 7,
`side_effects.rs:451-457`). Brief §6.3's checks are for ops; the brief doesn't say who may write a
NOTE_SNAPSHOT or how its content is checked.

- Any member whose publishing the relay accepts can replace the snapshot or roster of an epoch they hold,
  with junk or an older genuine version (a future stamp beats later honest ones), and ask for deletion of
  that epoch's outer events. A relay that ignores kind 5 keeps regular events, which can't be replaced.
- Inner signatures stop forged ops and the owner's signature forged rosters, not loss or rollback. They also
  don't stop a **snapshot with false content** signed by any member with their own key: a new device takes
  it as the note, and a synced device that merges snapshots takes it too.
- *Limits:* a device that kept the signed inner events (through a kind 5 too) can publish them in new outer
  events. Merged state alone can't re-create other authors' ops, and sending the deleted outer event again
  doesn't restore it on Buzz ("More storage facts"). Exposed to loss: whoever has only the relay. Before
  sharing (milestone 4) all holders are one user's devices. USER_INDEX and KEY_ENVELOPE are outside this.

## Contradictions with the brief and roadmap

- Brief §8: kinds "must be declared in `ALL_KINDS`". Not the gate; see above.
- Brief §5.2: outer events signed by the channel key; on Buzz only with one of options A to F (G gives
  that up).
- Brief §5.2: subscribe with `authors` only. Refused; needs `kinds`.
- Brief §4: dedupe by event id, and a `since` cursor per relay. Neither is enough (sync section).
- Brief §5.3, §5.5: one USER_INDEX per user, written by every device. No merge is defined.
- Brief §5.3: 40000-49999 reserved. Documented by Buzz, not enforced.
- Brief §6.1: "relay sees only that someone sent the recipient a gift wrap". Buzz also records the key
  it was sent under, normally the sender's.
- Brief §6.3: checks inbound ops. Nothing says who may write a NOTE_SNAPSHOT or how it is checked.
- Brief §8: clients need NIP-29 membership. Only for channel events; ours need relay admission instead, on
  closed relays.
- Brief §8 and O-3: subscription metadata only. Gift wraps (above) and, depending on O-4, note writes leak
  too.

## Not verified

- The block-hosted Buzz service: we don't know its settings, version, log retention or policies.
- Whether upstream would accept option E or F.
- Push: a lease may match gift wraps sent to its own author only (`relay/push_runtime.rs:464-471`). So
  when push is on (off in the compose example), a push gateway sees wake timing. Not studied further.
- NIP-FI (enterprise identity) was seen in the path but not studied. With it on, admission is stricter.
- HTTP read paths (`/query`, `/count`): only the authentication, membership and `p`-gate steps were read.
- Whether generic relays (strfry, nostr-rs-relay) accept and store our kinds, or kinds of 40000 and up.
- Whether a purge job removes soft-deleted rows.

## Cost to reverse

- **Kind numbers:** free until notes sync (milestone 3). After that, a migration on every relay and
  client, which upstream may still ask for at milestone 6.
- **Publishing option:** A, E and F change only the relay client and can be swapped. B leaves a permanent
  owner link in each Buzz database; C, a member row per epoch. D and G change the event shape and are
  costly to leave.
- **Sync design:** what clients store for dedupe, and USER_INDEX and roster content that can merge, are
  cheap to choose now and expensive to change after clients ship.

## Needs a decision

1. **O-3, restated.** Is it acceptable that a Buzz operator can normally learn who reads, who sends key
   envelopes (a client could use another admitted key for either) and, depending on decision 2, who writes,
   for Buzz-hosted notes? If not, Buzz should be off by default for shared notes, with a warning when a user
   adds it.
2. **How to publish to Buzz** (options table): A, which on closed relays needs C (an admin's part) or B
   (exposes the owner, and a reader who is a relay member can block it); D (no registration) or E
   (upstream), which normally expose the writer to the operator, D the readers too; F (upstream, same
   admission as A); G (exposes the writer to every admitted reader, needs an indexed tag); or no Buzz-hosted
   notes, with Buzz agents using another relay. Under A to C any reader who can authenticate as the channel
   key can stop a note's writers by using up their shared rate budget. Under D and G the relay no longer
   keeps one current snapshot per note, nor under D one current roster. Our suggestion: plan for A, and ask
   upstream about E before milestone 6. Choosing D or G after milestone 3 is a migration.
3. **Regular kinds in 1000-9999 (NIP-01's range) or 40000-49999 (Buzz's range, which its contributor guide asks
   for)?** We suggest the first.
4. **Five kinds or fewer?** Separate kinds tell the relay which event is an op, a suggestion, a snapshot
   or a roster change. One regular and one addressable kind, with the type inside the ciphertext, would
   need fewer registrations, and the kind would no longer tell an op from a suggestion, or a snapshot from a
   roster change. It would still tell the first two from the last two. The addressable kind would need a `d`
   per type, or a snapshot and a roster under one channel key replace each other; brief §5.2 says only "an
   opaque value derived from the note key". USER_INDEX, the fifth kind, couldn't join Buzz's author-only list
   if merged in, because the list is by kind.
5. **What any member can do to the relay's copies** (end of "What a sync design must handle"): what protects
   the members, and who may write a snapshot? Needed before milestone 4. An answer that changes key
   derivation or event shape is needed before milestone 3, or it is a migration.
