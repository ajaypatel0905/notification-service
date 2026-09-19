---
name: smoke-run
description: Boot the notification service with the zero-setup local profile and exercise the API with curl to confirm a change works in the running app, not just in tests.
---

# smoke-run

1. `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
2. `./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local > /tmp/app.log 2>&1 &`
3. Poll `curl -sf localhost:8080/actuator/health` until it returns.
4. Run `scripts/demo.sh` (uses the seeded keys `local-admin-key`, `acme-tenant-key-000000`, `globex-tenant-key-0000`).
5. Inspect `GET /api/v1/notifications/{id}` for the attempts and events of anything that misbehaved.
6. Stop with `pkill -f spring-boot:run`.

Pitfall: zsh's `echo` interprets `\n` in JSON bodies; use `printf '%s'` when piping responses into a JSON parser.
