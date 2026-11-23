<!--COMPRESSED v1; source:2026-11-22-buzz-ingest.md-->
§META
date:2026-11-22 topic:how the Buzz relay treats wax-notes events status:draft ticket:#6

§ABBREV
SR=source read DR=docs read CR=code run INF=inference(our reasoning, not in code)
kind.rs=crates/buzz-core/src/kind.rs lib.rs=crates/buzz-auth/src/lib.rs relay/=crates/buzz-relay/src/ db/=crates/buzz-db/src/
ingest.rs|event.rs|req.rs|auth.rs|side_effects.rs=relay/handlers/…
authkey=key the connection authenticated with chkey=channel key
unmarked path:line=SR in block/buzz@10dd70f1

§SUMMARY
1 brief §5.2 does NOT work on Buzz as written: registering kinds isn't enough; Buzz also requires event pubkey==authkey, only kind 1059 exempt [confidence high: two independent checks in source]
2 keep §5.2 design, don't reshape for Buzz; build on generic relay first(brief §8 already plans); decide how to publish to Buzz before milestone 6(options below; D,G change event shape→choosing either later than milestone 3=a migration)
3 design sync against the hazards found here(§SYNC); most hold on any relay; the design itself is left to milestone 3 and 4 plans
4 widen O-3: writes can leak too, by default durably: DB audit row ties authkey to each stored event; gift wraps: normally sender's key, but whatever admitted key the client connects with; note writes: depends on publishing option(O-4; under A only chkey)
5 kind numbers(PROPOSALS only): 8460,8461,38460,38461,38462; no collision in the 3 clones [confidence medium: unlisted users + open PRs not checked]

§EVIDENCE
nothing in Buzz built|run; CR=clones+greps only
block/buzz(main) 10dd70f15228d9222fdf80f0d6004ddd3797416e 2026-11-22 Apache-2.0(LICENSE)
nostr-protocol/nips bec0fc89e75645e77a06d145598246efca844457 2026-11-16 license not checked
nostr-protocol/registry-of-kinds 204f47cd83d488f3486fae82ef421b13517e0d80 2026-11-02 license not checked
roadmap R-2(Kotlin versions) n/a: Buzz is Rust, we take no artifact from it

§REGISTRY (brief has the wrong gate)
kind.rs exists where brief guessed; ALL_KINDS kind.rs:725
ALL_KINDS does NOT gate ingest; non-test uses=metric labels kind.rs:876(used by db/runtime/observability.rs:575 + relay/metrics.rs:746); relay/metrics.rs:858 also iterates it; rest=duplicate-number test kind.rs:1030
gate=required_scope_for_kind ingest.rs:598: match on kind, last arm `_ => Err("restricted: unknown event kind")` ingest.rs:712
lists differ both ways(→separate): 41,1063,43001-43006 in ALL_KINDS but no arm→clients can't publish; 30350 has arm(ingest.rs:604) but not in ALL_KINDS
CONTRIBUTING.md:510-560(DR) steps for new kind: constant in kind.rs; arm in required_scope_for_kind; side effects if any; search exclusion if private; tests

§TRACE EVENT socket→store, in order
1 connection permit, process-wide limit relay/connection.rs:389; unauthenticated after 5s→closed(relay/connection.rs:31,:708-722)
2 frame size default 512 KiB (relay/config.rs:14, relay/connection.rs:1277)
3 JSON parse relay/connection.rs:1404
4 rate limits per authenticated pubkey relay/rejection.rs:52-110; defaults(crates/buzz-auth/src/rate_limit.rs:90-139): 10 frames/s + 60 attempts/min to publish a non-ephemeral event(120/min for key with recorded owner, "agent"); attempts refused by later steps count too; HTTP own limit: 300 API calls/min per key incl. queries(relay/api/bridge.rs:243-275)
5 handler permit, else `rate-limited: too many concurrent requests` relay/connection.rs:1432-1446
6 authenticated, else `auth-required: not authenticated` event.rs:719-738
7 event.pubkey==authkey unless kind 1059, else `invalid: event pubkey does not match authenticated identity` event.rs:744-754
8 22242 refused event.rs:756; 24200 + ephemeral 20000-29999 leave here event.rs:771-931; everything else(and 28936)→ingest event.rs:964
9 community active ingest.rs:2604
10 relay-only + membership-notice kinds refused; 1059 refused over HTTP ingest.rs:2610-2635
11 event id + Schnorr sig ingest.rs:2643, crates/buzz-core/src/verification.rs
12 created_at within 900s of server time EITHER WAY, else `invalid: event timestamp too far from server time` ingest.rs:2685-2692; no exemption by kind
13 content ≤256 KiB ingest.rs:2702; NIP-44 payload of 65,535 bytes fits(INF: base64≈88 KB)
14 pubkey==authkey again, same 1059 exemption ingest.rs:2711-2716
15 KIND GATE ingest.rs:2719-2722, then scope ingest.rs:2739; NIP-42 grants every scope lib.rs:188-196
16 ban|timeout check on authkey ingest.rs:2748; media references when that mode on ingest.rs:2750, relay/handlers/media_access.rs:13
17 commands, feedback, reports, moderation handled + return ingest.rs:2754-2806
18 channel from h tag ingest.rs:2839-2889; kind requires one + missing→`invalid: channel-scoped events must include an h tag` ingest.rs:2891
19 if channel: NIP-29 membership, role, archive checks ingest.rs:2945-2997,:3156-3176
20 per-kind validators ingest.rs:3111-3496
21 store: replaceable ingest.rs:3766; addressable :3774(d length capped); else plain insert :3849; p tags indexed into event_mentions db/runtime/mod.rs:61
22 audit row, then fan-out ingest.rs:3959, event.rs:474-518
23 OK

§UNREGISTERED
regular|replaceable|addressable|≥40000: WS `OK false` `restricted: unknown event kind`, not stored; HTTP POST /events same text as an error
ephemeral 20000-29999: WS ACCEPTED + fanned out, not stored, no kind check, no timestamp check(event.rs:843-931,:1014-1134); HTTP rejected at kind gate(relay/api/bridge.rs:1480-1495)
never a silent drop; ephemeral still must be signed by authkey
order matters: pubkey check(7,14) before kind gate(15)→today a chkey-signed NOTE_OP on a member's connection gets the PUBKEY error, not the kind error; a test looking only for "unknown event kind" would miss the second problem
addressable replacement by (community,kind,pubkey,d); highest created_at wins, ties→lowest id (db/store/replaceable.rs:104-230,:736-741)
`OK true "duplicate:"` is NOT proof of storage (ingest.rs:3875-3880, any !was_inserted): losing(older) addressable event gets it; so does a deleted event sent again: soft-deleted row keeps its key, insert is ON CONFLICT DO NOTHING(schema/tables/public/events.sql:40, db/store/event.rs:2034-2036, db/store/event_deletion.rs:282-288)→stays hidden
superseded version soft-deleted(deleted_at), not erased, for ordinary kinds (db/store/event_deletion.rs:226-240)→old snapshot ciphertext stays in DB after replacement

§GIFTWRAP kind 1059
accepted, WS only (ingest.rs:635,:2624)
from: any authenticated+admitted connection; wrap's own pubkey any key
to: anyone; ingest doesn't require p tag or check recipient is a member; found no kind-1059 validation in ingest.rs beyond general steps
reading: filter that can match 1059 must have #p values ALL==authkey, else `restricted: p-gated events require #p matching your pubkey` (req.rs:378-390,:1940-1975; list kind.rs:211)→finding wraps by recipient means authenticating as the key in their p tag, which closed relay must have admitted; filter for 1059 w/ non-empty ids exempt, no later WS check covers 1059(req.rs:2130-2142, event.rs:220-237, crates/buzz-core/src/filter.rs:40-43)→client that knows the event id can fetch the wrap under any admitted key; decrypting still takes recipient's key; over HTTP the "authenticated" key can be unproven(BUZZ_REQUIRE_AUTH_TOKEN, §AUTH)
never channel-scoped(ingest.rs:2858); not searchable(schema/tables/public/events.sql:28); doesn't start workflows(event.rs:612-616)
BLOCKER for a standard library: NIP-59 wrap timestamps SHOULD be tweaked + in the past(nips/59.md:111-113, DR); NIP-17 up to two days(nips/17.md:59, DR); Buzz 900s rule(step 12) rejects those; Buzz's own e2e test uses a current timestamp(crates/buzz-test-client/tests/e2e_nostr_interop.rs:589-616); wrap that fits the window gives up most of the tweak, and sent to other relays too, shows its sending time there(INF)
21059(ephemeral gift wrap, nips/59.md) gets no pubkey exemption: check is ==1059

§AUTH
over WS: AUTH required to publish AND subscribe (event.rs:719-738, req.rs:109-122); NIP-11 advertises auth_required:true (relay/nip11.rs:167,:184)
over HTTP: proof of key depends on BUZZ_REQUIRE_AUTH_TOKEN, off in code(relay/config.rs:856-858; relay warns :1036-1041); off + NIP-FI not restricting the community→unsigned `X-Pubkey: <hex>` header accepted as identity(relay/api/bridge.rs:439-452; POST /events :1289, /query :1644, /count :2357); on→each request needs NIP-98 signature; shipped compose+chart turn it on(deploy/compose/.env.example:16, deploy/charts/buzz/values.yaml:120), Buzz's own CI off(.github/workflows/_ci-relay.yml:384). With it off:
 - anyone can list a victim's gift wraps(ciphertext, times, sizes) by claiming victim's key: p-gate compares w/ claimed key(bridge.rs:1753-1754); closed relay: claiming a member's key also passes membership check(reads same key)
 - publishing chkey event needs no login as chkey, only the header; admission still applies to the claimed key→closed relay must have admitted chkey; event's own signature still checked(step 11); gift wraps stay WS-only(step 10); NIP-OA admission(B) fails w/o signed request(relay/api/mod.rs:298-301)
 - audit row's "authenticated key" is then only a claimed key
 →(INF) local test relay should set it true, else results won't match a relay deployed from shipped compose|chart
one identity per connection: second AUTH→`auth-required: already authenticated` (auth.rs:528-542); NIP-42 says relays MUST treat every authenticated pubkey as authenticated(nips/42.md:35, DR)→differs from spec
relay admission(who may AUTH): 3 switches, all off in code(relay/config.rs:860-866,:901): BUZZ_REQUIRE_RELAY_MEMBERSHIP, BUZZ_PUBKEY_ALLOWLIST, BUZZ_ALLOW_NIP_OA_AUTH; shipped deployments turn on membership + NIP-OA(deploy/compose/.env.example:17-18, deploy/charts/buzz/values.yaml:121-123)→expect CLOSED in practice; denial `restricted: not a relay member` auth.rs:786
NIP-29 membership applies only to events with a channel(h tag holding a UUID); event with no h, kind outside requires_h_channel_scope(ingest.rs:887)=community-global, needs no group membership; our events should carry no h
pubkey must ==authkey: YES, twice(steps 7,14), only 1059 exempt

§OPTIONS can a chkey outer event publish?
A authenticate AS chkey, one socket per note key; or HTTP, each request signed by chkey(relay/api/bridge.rs:1404-1476; signing not needed where BUZZ_REQUIRE_AUTH_TOKEN off) | open relay: works once kinds registered | closed: refused unless chkey admitted(B|C) | Buzz learns for writes chkey+IP, no real key(but see INF)
B A + NIP-OA `auth` tag where note owner vouches for chkey | open: not needed | closed: works if NIP-OA on + owner is member(relay/api/mod.rs:294-321) | learns DURABLE chkey→owner link(relay/api/mod.rs:664-715)
C add chkey as relay member | open: not needed | closed: works; admin must add key, or mint an invite it can claim(notes) | every member key listed in relay-signed kind 13534(db/store/relay_members.rs:1106-1140)
D send outer events AS kind 1059; on either kind of relay finding them needs #p==reader's authkey | open: works today, no registration, but all as regular events(notes) | closed: same, for admitted keys | learns connection's key(normally sender's real key) per event + p tag naming each reader(or chkey, if readers authenticate as it)
E upstream: add our kinds to the 1059 exemption(event.rs:745, ingest.rs:2712) | open: works once kinds registered | closed: same, for admitted members | connection's key(normally sender's real key) per event, durably
F upstream: several identities per connection as NIP-42 says | works once kinds registered | same admission problem as A | links real key + chkeys on one connection
G sign outer event w/ MEMBER'S OWN KEY, opaque per-note value in a tag(INF) | open: works once kinds registered, with an indexed tag(notes) | closed: same, for admitted members | sender's real key per event, durably; EVERY admitted reader sees who writes to which note
notes:
G vs E: G needs only the registration milestone 6 already plans, no change to Buzz's pubkey rule; breaks brief §5.1("never by a member's real key") + §1 goal of hiding participants; E normally shows operator the same key→asking for E buys hiding the writer from OTHER relay users, not the operator; in G's favour: one member can no longer replace|delete another's copies(end of §SYNC); risk shrinks but stays: member can still delete its own ops, which others depend on(NOTE_OP carries "deps", brief §5.3), and an agent's recorded Buzz owner, who need not be in the note, can delete the agent's(side_effects.rs:451-457)
G needs an INDEXED TAG: any other tag matched only after the newest 1000 events of the rest of the filter are read→quiet note's ops can be missing w/ no error(§TAGS); #e is indexed + takes 64 hex digits(nips/01.md:143, DR)→opaque value fits, though not what `e` means(INF); value must change with the epoch, or it links a note's epochs(INF; member keys may link them anyway)
D,G LOSE THE RELAY'S SINGLE CURRENT VERSION of a snapshot, D of a roster→readers must choose|merge(INF from the facts cited); D: 1059=regular kind(nips/01.md:96, DR; plain insert, step 21)→nothing replaced, every version stays until deleted; G: replacement by kind,pubkey,d(nips/01.md:99, DR; §UNREGISTERED)→each member who signs one has own slot for the note; roster is owner-signed(brief §5.3)→readers can use the owner's alone; USER_INDEX outside this if the user's own key signs it, which needs neither option
D WITH PER-READER p TAGS STRANDS LATER READERS: tags name readers at send time→member added later can't find earlier events by recipient; id exemption helps only once it knows the ids(INF from the facts cited)→that variant needs a way to hand history to new members; tagging chkey instead doesn't(new member receives that key)
rotation repeats admission: each epoch new chkey(brief §6.4)→B,C admit again at every rotation, leave one owner link|member row per epoch; A needs no admission on open relay
C's admin step can be shared: POST /api/invites/claim exempt from membership gate(relay/api/invites.rs:12-15); one admin-minted invite can admit many keys: unlimited if max_uses omitted, else ≤10,000(:69-73, crates/buzz-core/src/invite.rs:21), ≤30 days(:18); code isn't limited to chkeys→anyone it is shared with can make other keys relay members too; every claimed key still in member list
A's "no real key" covers WRITES ONLY: readers subscribing on own key→Buzz still logs that key next to chkeys; hiding readers too=each reader also authenticates as chkey(one socket per note); finding KEY_ENVELOPEs by recipient shows recipient's key(§GIFTWRAP, w/ the exemption)→A alone does not settle O-3
B breaks in obvious form: owner link first-write-wins; second member vouching w/ DIFFERENT owner→Conflict→refused(auth.rs:864-882, relay/api/mod.rs:610-632); works only if every member presents SAME owner's tag, e.g. carried in KEY_ENVELOPE; NIP-OA calls tag a reusable capability(docs/nips/NIP-OA.md, DR); owner then exposed, can delete the key's events(side_effects.rs:451-456), ban on owner reaches the key(auth.rs:60-109)
B also TRUSTS EVERY READER: link recorded from any valid tag on any admitted login, whatever relay mode(auth.rs:812-821), "set once and never cleared"(relay/api/mod.rs:578-580, db/store/user.rs:418; user-deletion code not read); reader holds chkey→can log in first w/ tag naming itself owner→members presenting real owner's tag get Conflict for that epoch's key; closed relay: reader must itself be a member for that login to be admitted
E small code change but a policy request; changes nothing for kind 5: deletions of our events still need chkey(or its recorded owner)==authkey; PR(milestone 6): constants in kind.rs+ALL_KINDS; arms in required_scope_for_kind; both pubkey exemptions; is_global_only_kind; search exclusion(schema+migration); optionally bounded_kind_label(event.rs:35); tests
INF: A hides real key from relay records, but sockets opened together from one IP are easy to join; access log writes peer address + key of each login(relay/access.rs:932-950)

§TAGS
required: nothing for new channel-less kind; no h, no p; d capped in length for addressable(ingest.rs:3776-3783)
do NOT add h: h with a UUID makes event channel-scoped unless kind in is_global_only_kind(ingest.rs:802)
indexed|pushed to SQL: authors, kinds, ids, time, #e, #a, single #p, #d when every kind in filter is 30000-39999(req.rs:1330-1499); for our kinds other tags matched after the query's LIMIT(req.rs:690, then EOSE :728)→matches can be missing w/ no error
authors on non-member keys ACCEPTED: no check compares filter authors with members(req.rs:87-533,:1368); live fan-out has author index(relay/subscription.rs:840-885)
brief's filter REFUSED: {"authors":[channel_pubkey,…]} can match gated kinds→closed w/ p-gated message(req.rs:378-390,:1943) → ALWAYS send kinds
any admitted key can read our events, except under D(gated, §GIFTWRAP): channel-less ungated kind delivered to every connection that asks(event.rs:239-241); member can list {"kinds":[NOTE_OP]}→sees every chkey(under G, member key), size, time in community; content stays encrypted
search: depends how DB was made: migrated fresh installs index only 0,9,40002,45001,45003(migrations/0008_fresh_install_search_allowlist.sql); older populated DBs(migrations/0033_private_managed_agent_fts.sql) + desired-state schema(schema/tables/public/events.sql:28) use a skip-list→WOULD tokenize our ciphertext unless PR excludes our kinds
limits: 10 filters/REQ(relay/protocol.rs:12); 1000 stored events per filter, newest first(req.rs:1360-1364, db/store/event.rs:34,:773-775); 1024 subscriptions(req.rs:26); live author index budget 4096 kind×author keys(relay/subscription.rs:833)

§KINDS
40000-49999: ARCHITECTURE.md:177 "Buzz custom kinds"(DR); no range check in kind.rs or ingest gate enforces it; comments kind.rs:589-690 name sub-ranges; in code only 20000-29999(ephemeral), 10000-19999+0,3,41(replaceable), 30000-39999(addressable) have meaning(kind.rs:520-527,:884-900); ≥40000 stored as regular; kinds are 16-bit
ALL_KINDS(kind.rs:725-869):
 0,1,3,5,7,9,41,1059,1063,1617-1619,1621,1630-1633,1984
 8000-8003,9000-9002,9005,9007-9009,9021,9022,9030-9033,9035,9036,9040-9044,9050
 10000-10003,10030,10100,13534,13535
 20001-20003,24134,24200,24242,24245,24810,27235,28936
 30000,30003,30023,30030,30078,30174-30180,30300,30315,30617,30618,30620-30623
 39000-39003,39005-39009
 40002-40008,40010,40011,40099,40100,40901,40902,41001,41010-41012,42000,43001-43006
 44100,44101,44200,45001-45003,45010,45011,46001-46007,46010-46012,46020,46030,46031
 48001,48100-48104,48106,49001
constants outside ALL_KINDS: 22242,24243,30350,30382 (kind.rs:77,:81,:112,:944)
CANDIDATES(proposals only; freeze at first synced note, milestone 3):
 NOTE_OP 8460: NIP-01 regular=1000-9999(nips/01.md:96, DR); it gives ≥40000 no class
 NOTE_INTENT 8461 next to it
 NOTE_SNAPSHOT 38460: addressable must be 30000-39999, on Buzz too
 NOTE_MEMBERSHIP 38461, USER_INDEX 38462 next to it
 fallback if Buzz wants its own range: 42010,42011 for the two regular(only 42000 used in 42000s); the three addressable can't move there; CONTRIBUTING.md:514-521(DR) tells contributors to use 4XXXX from "the appropriate sub-range defined in `kind.rs`"→upstream may ask for this fallback(INF)
collision check CR 2026-11-23: all text files in the 3 clones, commits above: `grep -rnIiE '<pattern>' --exclude-dir=.jj --exclude-dir=.git`; decimal, proposals+fallback `(^|[^0-9a-f])(3?8_?46[0-9]|42_?01[01])([^0-9a-f]|$)`; hexadecimal `0x0*(21_?0[cd]|96_?3[cde]|a4_?1[ab])([^0-9a-f]|$)`; every hit a Buzz changelog PR number or secp256k1 test constant, none a kind; decimal pattern can't see `KIND8460`: same grep for `kinds?[_ ]?(3?8_?46[0-9]|42_?01[01])` had no hits; no range row in NIPs list covers them; not checked: computed values, open PRs, deployed apps listed nowhere, other relays' private kinds
USER_INDEX alternative: 30078(NIP-78 app data) already accepted by Buzz(ingest.rs:602), no registration, signed by user's own key→pubkey rule no problem; cost: anyone admitted can see it exists; Buzz has author-only list(kind.rs:141) we could ask to join w/ our own kind(list is by kind; decision 4)

§LEARNS (O-3)
IN CODE:
 login: key, peer address, connection id, time(client IP too if header configured)→access log line (relay/access.rs:853-950)
 each subscription: authkey, connection id, SUBSCRIPTION ID TEXT, kinds, FIRST 10 authors + total count, p values, times→access log line (relay/access.rs:61-139,:431-485)
 each EVENT over WS: authkey, kind, event id, outcome→access log line (relay/access.rs:519-605)
 each HTTP request: route, key + whether proven, peer address, status; POST /events also kind, + event id if ingest ran→access log line (relay/access.rs:1328-1450, relay/api/bridge.rs:1347-1386)
 each newly stored event: AUTHKEY, event id, kind→audit_log table, hash-chained, on by default (event.rs:647-685, relay/config.rs:1319, schema/tables/public/audit_log.sql)
 event: outer pubkey, tags, ciphertext, size, arrival time→events table (schema/tables/public/events.sql:8-39)
 each p tag(so the recipient of a gift wrap that has one)→event_mentions table (db/runtime/mod.rs:61-110)
 live subscriptions: connection→author keys→memory (relay/subscription.rs:840-885)
KEY: audit records AUTHKEY not event signer; for gift wraps ingest.rs:3925 passes connection's key to the audit row(metrics path does same on purpose ingest.rs:2483-2485)→Buzz stores the key each gift wrap was sent under, event id joins that to recipient's p tag; nothing ties that key to the sender inside the seal(1059 exempt from pubkey rule, ingest.rs:2711-2712):
 - ASSUMPTION(ours): normally sender's real key; NIP-59: clients SHOULD support authenticating with it(nips/59.md:104, DR)=shows they can, not that they do; no evidence of what clients send; where it is the real key, sender hiding does not hold against this relay; every "normally" in this report rests on this
 - can be any other key the relay admits: open relay a fresh one, or chkey under A, which instead ties recipient to the note; closed relay must have admitted that key; peer address may still link it to sender(INF)
 our outer events work the same way under D|E; under G their own pubkey is the real key; under B owner is linked; under F operator sees real key on same connection
access lines→stdout at INFO when served; default filter buzz_relay=info(relay/main.rs:7), target buzz_relay::access(relay/access.rs:36); that filter covers target=library behaviour INF, not run; compose sets buzz_relay=info too(deploy/compose/.env.example:25); against that, code comment "Production runs RUST_LOG=error"(ingest.rs:3897-3898): if still true of block-hosted service, its access lines(INFO,WARN) not written, only audit row+tables remain; OTel export turns lines off by default(relay/telemetry.rs:193); event content, tags, search text documented as never logged(relay/access.rs:1-24)
INF(not in code):
 where readers subscribe on own keys, operator can rebuild sharing graph of Buzz-hosted notes: every key subscribing to same chkey=likely participant; 10-author cap limits one log line, not the relay(sees whole filter)
 put nothing meaningful in subscription ids(logged as sent)
 retention of logs+audit rows is the operator's; no setting found in files read
 other members can't see subscriptions; can see outer events + timing; under D can't find them by recipient, but can fetch any whose event id they know(§GIFTWRAP)

§SYNC what a sync design must handle
facts, then hazards(INF): requirements + open problems, NOT a design; one rule complete on its own: ALWAYS put kinds in filters(Buzz refuses the brief's filter without; elsewhere harmless; hides nothing, decision 4)
TIMESTAMPS: Buzz refuses created_at >900s from its clock, gift wraps included(step 12); NIP-59/NIP-17 want wraps tweaked up to 2 days back; edits published later than made(brief §4), we expect shares too(brief §1 "fully offline-capable")
 - stamped when queued→refused by Buzz after 15 min; other relays may store it behind a reader's cursor, for a gift wrap=a missed key
 - edit queued across a rotation must not be lost: clients ignore new ops under old epoch(brief §6.4)
DUPLICATES: wrapping an inner event again gives new outer id→brief §4 "deduped by event ID" not enough; event id doesn't commit to its signature(nips/01.md:19-42, DR); NIP-59 rumor has none(nips/59.md:20, DR); KEY_ENVELOPE needs NIP-17's check that seal+rumor have same pubkey(nips/17.md:57, DR)
 - recording an id before event is verified+accepted can hide the genuine one: any reader can wrap a copy w/ broken sig(or into another note they're in); honest op can arrive before the roster that allows it
 - events held for a roster need a bound, which any member can fill
 - OPEN: which roster version judges an op; whether inner event names its note(else op can be replayed into another note its author belongs to)
ADDRESSABLE: relay compares only created_at then id(nips/01.md:99-101, DR; Buzz too, its OK true is not proof of storage, §UNREGISTERED)
 - stale version stamped now replaces newer one; USER_INDEX: can drop only relay copy of a note's key(brief §5.5,§6.1); stamping at edit time instead loses the older write
 - fetch-then-publish not atomic→two devices can overwrite each other; dropping a queued version safe only if the current one already contains its change
 - each relay has own current version; unreachable relay≠one without a version, must not block the others; version stamped by a fast clock can demand a later stamp than another relay accepts
 - OPEN: brief defines no merge for USER_INDEX(milestone 3) or roster(milestone 4; only owner can rebuild it)
CATCHING UP w/ since cursor(brief §4): since filters on created_at(nips/01.md:145, DR); relay returns newest matches, may return fewer than asked(nips/01.md:151, DR); Buzz ≤1000 per filter(§TAGS limits); cursor at newest event seen loses events that were:
 - stamped earlier but stored later: on Buzz two events stored together, gift wraps included, can be stamped 30 min apart(900s each way); elsewhere NIP-17's tweak alone can give gift wraps 2 days
 - past the page limit, which also cuts a backfill that doesn't page; if more matches than the limit share one created_at, as any member can arrange, paging by since/until(both inclusive) can't reach the rest; Buzz's nonstandard before_id can(relay/protocol.rs:147-179, db/store/event.rs:874-890)
 - under a chkey learned later, by share|rotation
 - held, then dropped or lost in a restart
 future-stamped event must not move cursor past the present
RATE LIMITS(step 4): brief §4 debounces 1-2s→one note under steady editing can reach them; under A-C the budget is the chkey's: all members share it, any reader who can authenticate as that key can use it up, stopping every writer of the note while it keeps sending; under D|E|G normally the writer's own
WHAT ANY MEMBER CAN DO TO RELAY'S COPIES (not Buzz-specific; D,G differ, §OPTIONS notes): every member incl. readers holds chkey(brief §5.1); where relay honours NIP-09(optional, a SHOULD) a kind 5 signed by that key deletes, no login unless relay asks(nips/09.md, DR); Buzz does, when authkey is the author or author's recorded owner(step 7, side_effects.rs:451-457); brief §6.3 checks are for ops; brief doesn't say who may write NOTE_SNAPSHOT or how its content is checked
 - any member whose publishing the relay accepts can replace snapshot|roster of an epoch they hold, w/ junk or older genuine version(future stamp beats later honest ones), and ask deletion of that epoch's outer events; relay ignoring kind 5 keeps regular events, which can't be replaced
 - inner sigs stop forged ops, owner's sig forged rosters, not loss|rollback; they also don't stop a SNAPSHOT W/ FALSE CONTENT signed by any member w/ own key: new device takes it as the note; synced device that merges snapshots takes it too
 - limits: device that kept the signed inner events(through a kind 5 too) can publish them in new outer events; merged state alone can't re-create other authors' ops, sending the deleted outer event again doesn't restore it on Buzz(§UNREGISTERED); exposed to loss: whoever has only the relay; before sharing(milestone 4) all holders=one user's devices; USER_INDEX+KEY_ENVELOPE outside this

§CONTRADICTIONS
brief §8: kinds "must be declared in ALL_KINDS"→not the gate
brief §5.2: outer events signed by chkey→on Buzz only w/ one of A-F(G gives that up)
brief §5.2: subscribe with authors only→refused; needs kinds
brief §4: dedupe by event id, since cursor per relay→neither is enough(§SYNC)
brief §5.3,§5.5: one USER_INDEX per user, written by every device→no merge defined
brief §5.3: 40000-49999 reserved→documented by Buzz, not enforced
brief §6.1: "relay sees only that someone sent the recipient a gift wrap"→Buzz also records the key it was sent under, normally the sender's
brief §6.3: checks inbound ops→nothing says who may write NOTE_SNAPSHOT or how it is checked
brief §8: clients need NIP-29 membership→only for channel events; ours need relay admission instead, on closed relays
brief §8 + O-3: subscription metadata only→gift wraps(above) and, depending on O-4, note writes leak too

§UNVERIFIED
block-hosted Buzz service: settings, version, log retention, policies unknown
whether upstream would accept E|F
push: a lease may match gift wraps sent to its own author only(relay/push_runtime.rs:464-471)→when push on(off in compose example) a push gateway sees wake timing; not studied further
NIP-FI(enterprise identity) seen in path, not studied; with it on, admission stricter
HTTP read paths(/query,/count): only authentication, membership, p-gate steps read
whether generic relays(strfry, nostr-rs-relay) accept+store our kinds, or kinds ≥40000
whether a purge job removes soft-deleted rows

§REVERSE cost
kind numbers: free until notes sync(milestone 3); after that a migration on every relay+client, which upstream may still ask for at milestone 6
publishing option: A,E,F change only relay client, swappable; B leaves permanent owner link in each Buzz DB; C, a member row per epoch; D,G change event shape, costly to leave
sync design: what clients store for dedupe, and USER_INDEX + roster content that can merge: cheap to choose now, expensive to change after clients ship

§DECISIONS needed
1 O-3 restated: acceptable that a Buzz operator can normally learn who reads, who sends key envelopes(client could use another admitted key for either) and, depending on decision 2, who writes, for Buzz-hosted notes? If not, Buzz off by default for shared notes, warning when user adds it
2 how to publish to Buzz(§OPTIONS): A, which on closed relays needs C(admin's part) or B(exposes owner, a reader who is a relay member can block it) | D(no registration) or E(upstream): normally expose writer to operator, D the readers too | F(upstream, same admission as A) | G(exposes writer to every admitted reader, needs indexed tag) | no Buzz-hosted notes, Buzz agents use another relay. Under A-C any reader who can authenticate as chkey can stop a note's writers by using up their shared rate budget. Under D,G relay no longer keeps one current snapshot per note, nor under D one current roster. Suggestion: plan for A, ask upstream about E before milestone 6. Choosing D|G after milestone 3=a migration
3 regular kinds in 1000-9999(NIP-01's range) | 40000-49999(Buzz's range, which its contributor guide asks for)? suggest the first
4 five kinds or fewer? separate kinds tell relay op|suggestion|snapshot|roster change; one regular + one addressable w/ type inside ciphertext needs fewer registrations, and kind no longer tells op from suggestion, or snapshot from roster change; still tells first two from last two; the addressable kind needs a `d` per type, else snapshot+roster under one chkey replace each other(brief §5.2 says only "an opaque value derived from the note key"); USER_INDEX, the fifth kind, couldn't join Buzz's author-only list if merged in(list is by kind)
5 what any member can do to relay's copies(end of §SYNC): what protects the members, who may write a snapshot? needed before milestone 4; answer that changes key derivation|event shape needed before milestone 3, or it is a migration
