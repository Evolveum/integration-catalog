# GUI tests

Browser tests of the rendered catalog, written with Playwright for Java and run by Maven. The browser
is not visible. This is a standalone Maven project: building the backend does not touch it.

The tests follow the base flow guides in `docs/`:

| Test | Covers |
|---|---|
| `AdminFlowTest` | admin guide, sections 1 and 3 to 8, as a superuser: publish an integration method, approve it, check that an anonymous visitor sees it, change it with *Save*, approve it, upgrade it with *Save as new version* and approve it once more |
| `RequestFlowTest` | section 2 of both guides: a contributor requests an application, a read-only user is refused a request but votes for it, the requester cancels it |
| `ContributorFlowTest` | contributor guide, sections 3 and 4: publish, find the method in *My integration methods*, change it with *Save* while it awaits approval (1.0 becomes 1.1 in place), cancel the request |

## Before a run

These must be running:

- the frontend on `http://localhost:4200`
- Keycloak with the `integration-catalog` realm
- the database
- a backend started with the support portal and the retry job switched off:

  ```
  -Dopenproject.enabled=false -Dpending-operations.cron=-
  ```

  With the portal off, submitting opens no ticket and the approval dialog shows *No support ticket*.

## Running

From the repository root:

```
mvn -f gui-tests/pom.xml test
```

The first run downloads Playwright's browsers into the user's cache, not only the Chromium the tests
use, and Surefire may warn about a `Corrupted channel` because of the download progress output. Later
runs do neither.

| Option | Default | |
|---|---|---|
| `-Dgui.baseUrl` | `http://localhost:4200` | where the frontend runs |
| `-Dgui.superuser.username` | `u5` | a superuser |
| `-Dgui.contributor.username` | `u3` | an individual contributor |
| `-Dgui.readOnly.username` | `u2` | a read-only user |
| `-Dgui.<user>.password` | the username | e.g. `-Dgui.contributor.password=secret` |
| `-Dgui.headless=false` | `true` | show the browser when debugging a failure |
| `-Dgui.timeoutMs` | `20000` | how long an action or a check waits; raise it for a slow environment |
| `-Dtest=AdminFlowTest` | all | run one class |

A failed test leaves a screenshot and a trace in `target/gui-tests`. Open the trace at
<https://trace.playwright.dev>; the file is processed in the browser and not uploaded.

## Writing tests

- **Find elements by `data-testid`**, never by placeholder, label, text or CSS class, which change
  for reasons unrelated to the test. When a test needs an element, add the attribute to its template.
  Keycloak's login page is the one exception, as it is not our template.
- **Name ids `<area>-<element>`** in kebab-case, e.g. `publish-connector-name`. Put the attribute right
  after the tag name. Repeated elements share one id, and a test narrows them down by their content;
  an id built at runtime uses `[attr.data-testid]`.
- **Don't remove or rename a `data-testid`** without updating the tests. To see which ones are in use,
  search this directory for the id.
- **Tests call page classes, and page classes call `framework/Ui`.** A new way of working with a
  component belongs in `Ui`, so it is written once.

| Package | Contains |
|---|---|
| `framework` | `Ui` (working with components), `AbstractGuiTest` (browser lifecycle, failure evidence) |
| `pages` | one class per page or wizard, with the steps a test can take there |
| the root package | the tests |

## Test data

Every run creates real data named `e2e_<timestamp>_*` (application, integration method, connector).
`RequestFlowTest` and `ContributorFlowTest` cancel what they created, so only a failed run leaves
data behind; `AdminFlowTest` publishes its method, which cannot be cancelled. `cleanup.sql` removes
the data with psql, and only previews unless told to commit:

```
psql -h <host> -U <user> -d <database> -f gui-tests/cleanup.sql               # preview, rolls back
psql -h <host> -U <user> -d <database> -v commit=1 -f gui-tests/cleanup.sql   # deletes
```

It stops without deleting anything if real data depends on the test data. Support portal tickets are
not touched; the preview lists their numbers so they can be closed by hand.
