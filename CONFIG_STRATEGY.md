# Dev vs Prod Config Strategy

## Android (gallery-android)

Use Gradle **product flavors**, not hardcoded strings, so the same codebase
builds against local and production backends without editing source.

```kotlin
// app/build.gradle.kts
android {
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080\"")
            buildConfigField("String", "WS_URL", "\"ws://10.0.2.2:8080/ws/device\"")
            applicationIdSuffix = ".dev"
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "BASE_URL", "\"https://your-domain.com\"")
            buildConfigField("String", "WS_URL", "\"wss://your-domain.com/ws/device\"")
        }
    }
    buildFeatures {
        buildConfig = true
    }
}
```

- `10.0.2.2` is the special alias the Android **emulator** uses to reach your
  host machine's `localhost` — use your machine's real LAN IP instead if
  testing on a physical phone over Wi-Fi.
- `devDebug` / `devRelease` build variants point at local Postgres + local
  Ktor; `prodRelease` is what you actually sign and distribute from your site.
- Never put secrets in `buildConfigField` — it's baked into the APK and
  trivially extracted. Base URLs are fine; API keys/tokens are not (there
  shouldn't be any static ones in this app's design anyway — auth is all
  per-device JWTs obtained at runtime via pairing).

## Backend (gallery-backend)

Already using `.env` (see `.env.example`) read via `System.getenv(...)` in
`DatabaseFactory` and `JwtConfig`. Keep that pattern:

- **Local dev:** real `.env` file, gitignored, loaded by your shell/IDE run
  config (IntelliJ: Run Configuration → Environment Variables, or use a
  plugin like EnvFile).
- **Deployment:** whatever host you pick (VPS, Docker, etc.) injects real
  environment variables directly — never ship a `.env` file to a server via
  git or a file copy if you can avoid it; set them in the host's own config
  (systemd unit `Environment=`, Docker `-e` flags, or your platform's secrets
  manager).

## Dashboard (gallery-dashboard)

When you scaffold it with Vite, follow the same shape:

```
# .env.development
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_BASE_URL=ws://localhost:8080

# .env.production
VITE_API_BASE_URL=https://your-domain.com
VITE_WS_BASE_URL=wss://your-domain.com
```

Vite picks the right file automatically based on `vite build` vs `vite dev`.
`VITE_`-prefixed vars are the only ones exposed to client code by design —
don't rename them off that prefix or they silently won't be available.

## Rule of thumb across all three

| What | Where it lives |
|---|---|
| Base URLs, feature flags | BuildConfig fields / `.env` (dev) / host env vars (prod) |
| Secrets (DB password, JWT secret) | Backend `.env` / host secrets manager only — never in Android or dashboard code |
| Per-device credentials (device JWT) | Obtained at runtime via pairing, stored in EncryptedSharedPreferences — never baked into a build |
