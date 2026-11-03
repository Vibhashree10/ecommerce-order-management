# common-lib

A **plain Java library** (jar, not a Spring Boot app) holding the contracts the services share:

| Class | Purpose |
|---|---|
| `event.OrderEvent` | JSON payload of order events (tolerant reader) |
| `event.Topics` | Kafka topic names + `forOrderEvent(type)` |
| `http.Headers` | `X-Internal-Api-Key`, `X-User-Id`, `X-User-Email`, `X-Request-Id`, `Idempotency-Key` |
| `error.ErrorResponse` | error body shape |
| `page.PageResponse` | paged result shape (no Spring types) |

Build and install into your local Maven repo: `mvnw.cmd clean install` (needs JDK 21).

## Is it used by the deployed services? **Not yet - on purpose.**
Each service's `docker build` only sees its own folder, so a library that lives next to it is invisible inside the image.
To adopt it you need ONE of:
1. **Package repository** (GitHub Packages / CodeArtifact): publish `common-lib`, add it as a dependency in each service. Cleanest.
2. **Repo-root build context**: build Docker images from the repo root and copy `common-lib` into the image build (changes the deploy engine).
3. **Multi-module Maven build** in one parent pom.

Until then each service keeps its own copy of these small classes (`notification-service` already treats the event as a tolerant reader).
Adopting later is mechanical: replace the local record with the import and delete the copy.
