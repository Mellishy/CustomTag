# mellishy custom tag
a token-based custom tag platform for paper 1.21+. players spend tokens to request their own unique chat tags; staff (or an AI) review them through a clean gui, a global queue, or your discord.

started as a simple tag plugin - v4 turns it into a full platform built for real networks.

### how it works
1. a player spends a token and writes a tag (chat input or the in-game book editor, with live chat preview)
2. the tag passes a validation pipeline (unicode abuse, reserved names, blacklist, regex) *before* it costs anything else
3. it enters the global request queue with a permanent id like `REQ-00000042`
4. optionally an AI moderates it first - approving, rejecting, or escalating to staff when unsure
5. staff review the rest in the gui or with `/ct queue`, with per-request locks so two mods never fight over one request
6. the player gets notified wherever they are - even on another server of your network

---

## v4.0.0 - the platform update
this is a big one. everything below is modular, hot-reloadable and fully async (zero main-thread i/o):

### queue & requests
- every submission becomes a permanent `REQ-00000042` request with a full status lifecycle and transition history - survives restarts
- priority ordering (vip requests can jump the line), a global pending cap, and automatic expiry with refund
- staff review locks with auto-timeout: no two staff can act on the same request, and a mod who logs off mid-review can't jam it
- `/ct undo <request-id>` reopens any decided request - **no decision is irreversible**

### custom player ids
every player gets a permanent, human-friendly id like `<#3VF-2>` - shown in their menu, the queue, every log line and every discord embed. support conversations always line up, even after name changes.

### validation pipeline
- runs before any token/queue/AI cost - a blacklisted tag never wastes an api call
- unicode-abuse detection (zero-width/invisible/bidi characters), character rules, reserved names, word categories and admin regex rules
- each rule carries its own verdict (reject / ai-review / staff-review) and refund policy
- bypass-proof matching: `O_w-n3r`, `0wner`, `o w n e r` and `Оwner` (cyrillic) all normalize to `owner`

### token economy
- every balance change is an immutable `TOKEN-00000001` transaction (consume / refund / admin / purchase / reward) in monthly jsonl ledgers - nothing can touch a balance without leaving a record
- reservation-based charging that's dupe-proof: disconnecting mid-creation refunds automatically, and stale creation books can't be reused
- accounts can be frozen; inspect anything live with `/ct tokens <player>`

### ai moderation (optional)
- works with any openai-compatible endpoint: openai, openrouter, groq, deepseek, ollama, together, gemini and self-hosted gateways - add providers in `ai/providers.yml`, no code changes
- provider fallback chain, FULL / SUGGEST / DISABLED modes, confidence thresholds, an lru cache and rate limiting to keep costs down
- fail-safe by design: every ai failure or low-confidence verdict degrades to staff review - the ai can never lose or wrongly close a request

### cross-server sync (redis)
- set `sync-backend: redis` in `network/settings.yml` and every lobby stays in sync: approve a tag on lobby-1 and lobby-3 re-reads the player's data and delivers the "your tag was approved!" message right there
- auto-reconnects if redis goes down (the plugin keeps working normally in the meantime), and hot-swaps live with `/ct reload network`
- the redis client ships inside the jar, relocated - nothing extra to install

### integrations
discord embeds, telegram messages and generic json webhooks - per-endpoint event subscriptions, admin-editable message templates, per-endpoint rate limits and retry with exponential backoff. every request/token/ai/security event can be broadcast.

### security layer
submission rate limits, duplicate-request swallowing, per-player operation locks (no double-click races), persistent security flags on repeat offenders, and per-subsystem maintenance mode.

### audit trail
every important action - approvals, rejections, refunds, ai decisions, token movements, security blocks - is one json line in daily files under `logs/audit/`. searchable in-game with `/ct audit <filter>`, with configurable retention.

### roles & granular perms
`permissions/roles.yml` defines per-role pending/tag/length limits and queue priority, with inheritance. staff powers can be handed out individually via `customtag.staff.*` nodes - `mellishy.admin` still grants everything, so existing setups keep working unchanged.

### modular configs
each subsystem lives in its own folder (`blacklist/`, `queue/`, `tokens/`, `security/`, `ai/`, `webhooks/`, `permissions/`, `logs/`, `network/`) with versioned files that migrate automatically on update - your edits are never overwritten. reload one module live: `/ct reload blacklist`.

---

## developer api
depend (or softdepend) on `CustomTag` and you get a full api:

```java
if (CustomTagAPI.isAvailable()) {
    TagOperations ops = CustomTagAPI.operations();

    // reads - safe from any thread
    ops.activeTagLegacy(uuid);        // the tag a player wears, ready for tab/scoreboard
    ops.tokenBalance(uuid);
    ops.customIdDisplay(uuid);        // "<#3VF-2>"
    ops.openRequests();               // the live queue, in review order

    // mutations - main thread, and they run the FULL pipeline
    // (ledger, audit, bukkit event, webhooks, cross-server sync)
    ops.applyTokens(uuid, TokenTransactionType.PURCHASE, 3, "store:webstore", "MyStorePlugin");
    ops.approveRequest("REQ-000123", "MyDiscordBot");
    ops.rejectRequest("REQ-000124", "MyDiscordBot", "inappropriate", true);
}
```

need more? the lower-level services are all exposed too (`CustomTagAPI.requests()`, `.tokens()`, `.playerIds()`, `.validation()`, `.ai()`, `.webhooks()`, `.audit()`, `.security()`, `.permissions()`), plus bukkit events you can listen to: `TagRequestCreatedEvent`, `TagRequestApprovedEvent`, `TagRequestRejectedEvent`, `AIDecisionEvent`, `TokenBalanceChangeEvent`.

there is deliberately **no** way to change a balance or decide a request without going through the validated, logged pipeline - external plugins get the same rules as the gui.

---

## commands & perms
| command | perm | description |
| :--- | :--- | :--- |
| `/ct` or `/customtag` | `mellishy.use` | opens the main menu |
| `/ct id` | `mellishy.use` | shows your permanent custom id |
| `/ct admin` | `customtag.staff.queue` | opens the admin review gui |
| `/ct queue` | `customtag.staff.queue` | review queue in chat (ids, locks, ai notes) |
| `/ct history <player> [n]` | `customtag.staff.queue` | a player's full request history |
| `/ct undo <request-id>` | `customtag.staff.undo` | reopens a decided request |
| `/ct give/take <player> <amt>` | `customtag.staff.tokens` | ledger-logged token changes |
| `/ct tokens <player> [n]` | `customtag.staff.tokens` | balance + recent transactions |
| `/ct freeze/unfreeze <player>` | `customtag.staff.freeze` | freezes a token account |
| `/ct audit [filter]` | `customtag.staff.audit` | searches the audit trail |
| `/ct stats` | `customtag.staff.stats` | queue / token / ai / webhook statistics |
| `/ct maintenance <sub> <on\|off>` | `customtag.staff.maintenance` | freezes a subsystem |
| `/ct pending <limit>` | `customtag.staff.queue` | live global queue cap (persisted) |
| `/ct resetcooldown <player>` | `customtag.staff.cooldown` | resets the cancel cooldown |
| `/ct reload [module]` | `customtag.staff.reload` | reloads everything or one module |

papi placeholders: `%customtag_tag%`, `%customtag_tag_raw%`, `%customtag_tokens%`, `%customtag_tagcount%`, `%customtag_id%`, `%customtag_id_display%`

---

## setup
grab the latest `CustomTag-4.0.0.jar` from the **releases** tab and drop it into your `plugins` folder. storage (yaml / mysql / mongodb) is picked in `config.yml` - all drivers ship inside the jar.

running a network? point every server at the same mysql/mongodb database, give each one a unique `server-name` in `network/settings.yml`, set `sync-backend: redis`, done.

build it yourself:

```bash
mvn clean package
```

*(requires jdk 21 and maven 3.9+)*

## older releases
<details>
<summary>v3.0.0</summary>

- **smart menus:** gui colors track status (gray/none, orange/pending, green/approved, red/rejected)
- **live preview:** see exactly how your tag looks in chat before submitting it
- **stealth moderation:** drop (q) to delete, or shift-right-click to reject and refund - no spammy messages
- **pluggable storage:** yaml, mysql, or mongodb - drivers built in, no extra downloads
- **randomizer:** cycle through your approved tags on every chat message
</details>

### found a bug?
if you run into any issues, open an issue and i'll drop a fix :)

### license
feel free to explore and modify. <3
