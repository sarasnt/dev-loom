# A CI failure that isn't yours doesn't need you now

## Problem

`Today` shows every failing build under **Needs you now**. `UrgencyRules:31` is unconditional:

```java
if (ci && "build".equals(type) && "fail".equals(tone)) return "ci-fail";
```

So a red build on a pull request someone else opened interrupts you exactly as loudly as one on
your own. It isn't nothing — it's worth knowing — but it isn't yours to fix, and a band called
"needs you now" that is mostly other people's work stops being read.

The data to tell them apart arrived with V24 (`work_item.author`, `work_item.pr_role`), but build
items never got it: `BitbucketConnector:270` creates them without `.withAuthor(...)`, so every
build row in the database has a null role today.

## Design

### Build items inherit the PR's authorship

The connector already resolves `prAuthor` and `prRole` for the pull request a few lines above
(`BitbucketConnector:159,169`). A build derived from that PR's head commit takes the same two
values. This is the enabling change — without it there is nothing to branch on.

### Urgent means yours

`UrgencyRules` gains one test: `ci-fail` is urgent only when the build's `prRole` is `mine`.

A build you are only *reviewing* is not urgent. A red build there means the author has work to do,
not you.

### Unknown authorship stays urgent

Where `prRole` is null the item keeps its current urgency. Null means "we never resolved it", not
"it isn't yours": GitHub-connector builds set no role at all, and every row written before this
change has a null one.

Demoting on null would silently stop a GitHub CI failure on your own repository from being urgent,
and it would look exactly like the feature working correctly. Demote only on positive knowledge
that a build belongs to someone else.

### A build reaches Today only when it is urgent

Removing it from `needsYou` is not sufficient by itself. `TodayService:124-128` puts anything whose
`prRole` is `mine` **or `review`** into the **assigned** band, so once builds carry a role, a
reviewer's failing build would quietly reappear one band lower — moved, not demoted.

Excluding `type = "build"` from the assigned band keeps the rule whole: your own red build is an
interrupt, and every other red build is not a Today item at all.

### The signal is kept, not deleted

The item still exists. `Work` lists it with its author and the role chips already filter on
exactly this field; `Builds` still analyzes it on request. Nothing is hidden — it stops claiming
the top of your morning.

### Notifications follow

`NotificationService:71` calls the same `urgencyKey`, so this also stops a desktop toast firing for
other people's failures. That is intended.

## Surface

| File | Change |
| --- | --- |
| `BitbucketConnector.java:270` | build item inherits `author` / `prRole` from its PR |
| `UrgencyRules.java:31` | `ci-fail` requires `prRole` of `mine`, or null |
| `TodayService.java:124` | assigned band excludes `type = "build"` |

No migration: V24 already added both columns. No frontend change — `Work` renders the author and
the role chips already exist.

## Out of scope

- GitHub build items carrying authorship. The GitHub connector does not resolve a PR role today,
  and its builds keep the null-means-urgent behaviour above.
- Any change to what `Builds` analyzes or how it reports being unable to.

## Verification

1. A failing build on a PR you opened appears under **Needs you now**.
2. A failing build on someone else's PR does not appear anywhere on Today — not in needsYou, and
   not in assigned.
3. That same build is still listed in `Work`, showing its author, and still opens in `Builds`.
4. A build with a null `prRole` (a GitHub one, or a row written before this change) still appears
   under **Needs you now** — the unknown case must not go quiet.
5. A sync that newly discovers someone else's failing build fires no desktop notification.
