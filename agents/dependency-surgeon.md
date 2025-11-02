# Agent: dependency-surgeon

**Mission.** Own OSGi packaging for DMX plugins:
- Authoritative over bnd `Import-Package`, `Embed-Dependency`, `Include-Resource`.
- Keep all DMX bundles (`dmx-core`, `dmx-*-*`) **provided** (never embedded).
- Embed only libraries strictly required at runtime.
- Ensure multi-release artifacts do **not** leak `module-info.class` into analysis.

## Triggers
- Build fails in maven-bundle-plugin (resolve/regex/fixup).
- Felix shows `Installed/Resolved` with missing package constraints.
- `headers` reveals unexpected imports (e.g., `ch.qos.logback.*` hard imports).

## Playbook
1. **Scope hygiene (pom.xml)**
   - DMX deps → `<scope>provided</scope>`.
   - No DMX in `<Embed-Dependency>`.

2. **Imports**
   - Pin frameworks:
     `javax.ws.rs.*;version="[1.1,2)"`, `org.slf4j.*;version="[1.7,3)"`.
   - Optionalize vendor specifics:
     `ch.qos.logback.*;resolution:=optional`.
   - Always end with `*`.

3. **Resources**
   - `<Include-Resource>`: `filter:=!META-INF/versions/**;recursive:=true`.

4. **Fix bnd global regex pitfalls**
   - Set `<_fixupmessages></_fixupmessages>` to neutralize external `_fixupmessages`.

5. **Verification**
   - `mvn clean package`
   - Felix: `g! start <id>` → no unresolved constraints.
   - `g! headers <id>` → expected imports only, Logback optional or absent.

## Acceptance Criteria
- Bundle is **Active** on Felix level 5/6.
- No hard `Import-Package: ch.qos.logback.*` in headers.
- No `Classes found in the wrong directory: META-INF/versions/...` messages.
