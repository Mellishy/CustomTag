# Changelog

## 3.1.0 — Code Review Bugfix Release

### Fixed

- **No length limit on submitted tag text.** A player could previously submit an arbitrarily long
  tag through either creation method (chat or book), which would then render into *every other
  player's* chat on every message once approved. Both creation paths now enforce
  `tokens.max-tag-length` (default `32`, checked against the plain/stripped length so color codes
  don't eat into the budget) before a preview is even shown, with a matching defense-in-depth check
  in `TagService` itself so no future or third-party call path can bypass it either.
- **Wrong/missing reason shown for a blocked "Create" button.** `MainMenuGUI` and `TagListGUI` were
  determining *why* the Create button was locked using `getTags().size()`, while the actual
  authoritative check (`TagService#canOpenCreateMethod`) uses `activeTagCount()` (which excludes
  rejected history — see `PlayerData#activeTagCount`). A player who had simply accumulated several
  rejected attempts over time could be shown the wrong reason (e.g. "max tags reached" while the
  real blocker was a cooldown), or no reason at all. Both menus now check the same field
  `TagService` does.
- **Unbounded growth of rejected-tag history.** A player who kept submitting and getting rejected
  had every single rejected entry kept forever, with no cap — a slow, permanent per-player storage
  leak on a long-running server. `tokens.max-rejected-history` (default `5`) now prunes the oldest
  rejected entries every time a new rejection comes in; set it to `0` to restore the old unlimited
  behaviour. Pending/approved tags are never touched by this.

### Improved

- `AdminGUI` no longer allocates a new `SimpleDateFormat` on every single menu open — a small but
  needless allocation for a menu that a busy moderator may reopen dozens of times a minute. Cached
  per-instance and automatically invalidated if `messages.admin-date-format` changes via
  `/customtag reload`.
- The admin queue's paginated title (`gui.admin-list.title` + the `[page/total]` suffix) is now
  defensively length-capped, so an unusually long custom title in `config.yml` can never crowd out
  the page indicator.
- Removed several leftover fully-qualified inline class references (e.g.
  `com.mellishy.customtag.util.ColorUtil.parse(...)` used instead of the already-imported
  `ColorUtil`) and one wildcard import (`com.mellishy.customtag.gui.*`), left over from earlier
  find/replace bugfix passes. No behavior change — pure readability/consistency cleanup.

### New config keys (config.yml)

```yaml
tokens:
  max-tag-length: 32          # visible-character cap on a submitted tag, plain text
  max-rejected-history: 5     # oldest rejected entries pruned beyond this count, 0 = unlimited
```

Both fall back to the defaults above automatically if you don't add them to an existing
`config.yml` — nothing needs to be regenerated.

### New message key (config.yml → messages)

```yaml
messages:
  tag-too-long: "&cYour tag is too long ({length}/{max} characters). Please shorten it and try again."
```

### Tests

Added `PlayerDataPruneRejectedHistoryTest` (4 cases) covering the new history cap, following the
project's existing convention of unit-testing pure data/business logic in `PlayerData` directly
rather than mocking the Bukkit API.
