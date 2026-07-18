# mellishy custom tag 
a simple, token-based custom tag plugin for paper 1.21.1.

### what is this
a custom tag request system where players spend tokens to get their unique tags. admins approve or deny them through a clean gui, keeping everything organized and easy to manage.

### v3.0.0 features
- **smart menus:** gui colors now track status (gray/none, orange/pending, green/approved, red/rejected).
- **live preview:** see exactly how your tag looks in chat before submitting it.
- **stealth moderation:** drop (q) to delete, or shift-right-click to reject and refund. no spammy messages.
- **pluggable storage:** yaml, mysql, or mongodb. drivers are built-in, no extra downloads needed.
- **randomizer:** option to cycle through your approved tags on every chat message.

### found a bug? 
if you run into any issues or bugs, just let me know (open an issue) and i'll drop a fix :)

### commands & perms
| command | perm | description |
| :--- | :--- | :--- |
| `/ct` or `/customtag` | `mellishy.use` | opens the main menu |
| `/ct admin` | `mellishy.admin` | opens admin review gui |
| `/ct give <player> <amt>` | `mellishy.admin` | gives tokens |
| `/ct take <player> <amt>` | `mellishy.admin` | takes tokens |
| `/ct resetcooldown <player>`| `mellishy.admin` | resets cancel cooldown |
| `/ct reload` | `mellishy.admin` | reloads config |

*papi placeholders included: `%customtag_tag%`, `%customtag_tag_raw%`, `%customtag_tokens%`, `%customtag_tagcount%`*

### setup 
grab the latest `CustomTag-3.0.0.jar` from the **releases** tab and drop it into your `plugins` folder.

if you want to build it yourself, run:
```bash
mvn clean package
clean package
*(requires jdk 21 and maven 3.9+)*

### license
mit licensed. feel free to explore and modify. <3
