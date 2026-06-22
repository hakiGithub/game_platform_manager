# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Game server unified management platform (游戏服务器统一管理平台) — a lightweight admin panel for personal game server hosting. Manages SSH hosts, deploys game instances (LinuxGSM/Docker/Docker Compose), Web terminal, PF4J plugin system, backup/restore.

## Repository layout

This repo uses **git submodules** for `backend/` and `frontend/`. Always work inside the submodule directories when making changes:
- `backend/` — Java 17, Spring Boot 3.2.5, Maven multi-module
- `frontend/` — Vue 3.4, Vite 5, Element Plus 2.6, Pinia

Detailed developer guides exist in `AGENTS.md` (root), `backend/AGENTS.md`, and `frontend/AGENTS.md`. The API documentation is in `docs/api-doc.md`.

## Common commands

### Backend (from `backend/`)

```bash
mvn clean compile          # compile
mvn spring-boot:run        # run (default profile)
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # run with dev profile
mvn test                   # run all tests
mvn test -pl core -am      # run tests for a specific module (+ dependencies)
mvn test -Dtest=InstanceServiceTest   # run a single test class
mvn clean package -DskipTests         # package, skip tests
mvn jacoco:report          # coverage report
```

### Frontend (from `frontend/`)

```bash
npm install                # install dependencies
npm run dev                # dev server (port 3000, proxies /api → localhost:8080)
npm run build              # production build
npm run preview            # preview production build
npm run lint               # ESLint
npx vitest                 # run all tests
npx vitest run             # run tests once (no watch)
npx vitest src/tests/api/auth.test.js  # run a single test file
```

### Plugin compilation

```bash
cd backend
mvn clean package -pl plugin-l4d2 -am -DskipTests   # build the L4D2 plugin
```

## Architecture

### Backend module layout

The Maven parent POM (`backend/pom.xml`) defines 4 modules:

| Module | Purpose |
|--------|---------|
| `api` | Shared interfaces, DTOs, VOs, common result types (`Result<T>`, `PageResult<T>`), exceptions |
| `core` | Main Spring Boot application — controllers, services, mappers, config, WebSocket handlers, adapters |
| `plugin` | PF4J plugin extension points (`GameEnhancementExtension`), plugin manager, plugin API controllers, Thymeleaf UI serving |
| `plugin-l4d2` | Example game-specific plugin for Left 4 Dead 2 (RCON, maps, monitoring, VPK parsing) |

**Key package**: `com.gameplatform`

### Backend layered flow

```
Controller (@RestController, /api/*)
  → Service interface → ServiceImpl
    → Mapper (MyBatis-Plus BaseMapper)
      → Entity (@TableName, extends BaseEntity)
```

- **DTO** objects for request bodies, **VO** objects for responses
- **Unified response**: `Result<T>` wrapper with `{code, message, data, timestamp}`
- **Pagination**: `PageResult<T>` with `{current, size, total, pages, records}`
- **Authentication**: JWT via Spring Security; `@OperationLog` annotation triggers AOP audit logging
- **Database**: SQLite (embedded), MyBatis-Plus `BaseMapper` with logical delete (`@TableLogic`)
- All entities extend `BaseEntity` (id, createTime, updateTime, createBy, updateBy, deleted, remark)

### Deployment adapter pattern

`adapter/` package uses a strategy pattern for game deployment:

```
DeployAdapter (interface)
  → AbstractDeployAdapter
    → LinuxGsmAdapter, DockerAdapter, DockerComposeAdapter
DeployAdapterFactory — returns the right adapter by DeployType
```

Adapter method lifecycle: `deploy()` → `start()` / `stop()` / `restart()` → `destroy()`; plus `getStatus()` and `healthCheck()`.

### Plugin system (PF4J) — full loading flow

The plugin system has 7 phases from startup to runtime:

**Phase 1 — PF4J Init (app startup)**
```
PluginConfig (@Configuration)
  → GamePlatformPluginManager(pluginsDir)  // extends DefaultPluginManager
    → parentFirst=true ClassLoader (extension point interfaces shared)
PluginAutoLoader (ApplicationRunner.run())
  → pluginManager.loadPlugins()   // scan JARs, parse plugin.properties
  → pluginManager.startPlugins()  // call Plugin.start() on each
  → scan @Extension classes       // register L4D2Extension etc.
```

**Phase 2 — Thymeleaf template resolver**
```
PluginThymeleafConfig creates PluginClassLoaderTemplateResolver (order=1)
  → template "plugin/l4d2/index"
    → parse gameCode=l4d2, path=index
    → PluginFrameworkService.getPluginIdByGameCode("l4d2")
    → load "ui/index.html" from plugin ClassLoader → StringTemplateResource
```

**Phase 3 — Frontend fetches manifest**
```
PluginTab.vue mounted
  → pluginStore.loadManifest(gameCode)
    → GET /api/plugin/{gameCode}/manifest  (PluginFrameworkController)
      → PluginFrameworkService.getManifestByGameCode()
        → read manifest.json from plugin JAR (or build from @Extension metadata)
      → returns PluginManifestVO { pluginId, gameCode, gameName, menus[], entry }
    → render left sidebar menu from manifest.menus
```

**Phase 4 — Thymeleaf server-side render + iframe**
```
PluginContainer.vue renders <iframe src="/plugin/l4d2/ui?instanceId=1&token=xxx">
  → PluginPageController.pluginPage("l4d2", instanceId, token)
    → inject Thymeleaf model: pluginId, gameCode, gameName, instanceId, token, apiBase
    → return "plugin/l4d2/index"
    → PluginClassLoaderTemplateResolver loads ui/index.html from JAR
    → Thymeleaf renders, injecting: window.pluginData = { instanceId, gameCode, token, apiBase }
```

**Phase 5 — Static resource serving**
```
Browser requests /plugin/{gameCode}/ui/js/app.js
  → PluginFrameworkController.getPluginResource(gameCode, "js/app.js")
    → PluginFrameworkService.getPluginResource(pluginId, resourcePath)
    → pluginClassLoader.getResourceAsStream("ui/js/app.js")
    → return byte[] + Content-Type + 7-day cache
```

**Phase 6 — postMessage communication (main app ↔ iframe)**
```
Plugin iframe sends: postMessage({ type: "READY", source: "PLUGIN" })
  → pluginCommunication.ts handleMessage()
    → sendInit()    → { instanceId, gameCode, hostId, hostIp, ports }
    → sendAuth()    → { token, user }
    → sendThemeChange()
Plugin can request: NAVIGATE, NOTIFY, CONFIRM, API_REQUEST (proxied by main app)
```

**Phase 7 — Plugin API calls**
```
Plugin JS calls its own API via apiBase (e.g. /api/plugin/l4d2/rcon/command)
  → Plugin's Spring @RestController handles request
  → Response returned to plugin iframe
```

Key classes:
| Class | Role |
|-------|------|
| `GamePlatformPluginManager` | Custom PF4J manager with parentFirst ClassLoader |
| `PluginAutoLoader` | `ApplicationRunner` that loads/starts plugins on boot |
| `PluginFrameworkService` | Service layer: manifests, status, resource loading, hot-reload |
| `PluginFrameworkController` | REST API for plugin lifecycle and static resources |
| `PluginPageController` | Thymeleaf page rendering for plugin iframes |
| `PluginClassLoaderTemplateResolver` | Resolves templates from plugin JAR ClassLoaders |
| `GameEnhancementExtension` | PF4J extension point interface (in `api` module) |

Important: In dev mode, `plugin-l4d2` controllers work via classpath scanning (Maven module). For production hot-loaded JARs, Spring Controller registration from PF4J ClassLoaders may need additional wiring.

### Game metadata

Game configurations live in `backend/src/main/resources/games/*.yml` (minecraft, palworld, valheim, rust, l4d2). Each YAML defines:
- Deploy types, default ports, environment dependencies
- Docker image/env/volumes/ports config
- LinuxGSM script and paths
- `configSchema` — JSON Schema-like form definition for instance configuration UI
- `customOperations` — game-specific actions (backup archive, clean logs, etc.)

### WebSocket endpoints

| Endpoint | Purpose |
|----------|---------|
| `/ws/ssh/{hostId}` | Web SSH terminal |
| `/ws/instance/{instanceId}/console` | Game instance console |
| `/ws/instance/{instanceId}/logs` | Instance log streaming |
| `/ws/docker/{hostId}/containers/{containerId}/exec` | Container exec terminal |
| `/ws/docker/{hostId}/containers/{containerId}/attach` | Container attach |
| `/ws/docker/{hostId}/containers/{containerId}/logs` | Container log streaming |

### Frontend structure

```
src/
├── api/           # Axios request functions per module
├── components/    # Reusable Vue components
├── layouts/       # Header, Sidebar, MainLayout
├── router/        # Vue Router config
├── stores/        # Pinia stores (user, host, instance, backup, app)
├── styles/        # SCSS variables + global styles
├── tests/         # Vitest tests (api/ and components/)
├── utils/         # request.js (Axios interceptor), websocket.js
├── App.vue
└── main.js
```

- Vite auto-imports: Vue APIs, Vue Router, Pinia, Element Plus components and resolvers
- Axios base is `/api`, with interceptors for JWT injection, 401 redirect, error toasts
- `@` alias → `src/`
- SCSS variables auto-injected into every component via `additionalData` in vite config

## Key conventions

- **Java class naming**: Controller → `XxxController`, Service → `XxxService` / `XxxServiceImpl`, Mapper → `XxxMapper`, Entity → `Xxx`, DTO → `XxxDTO`, VO → `XxxVO`, Config → `XxxConfig`, Util → `XxxUtil`, Exception → `XxxException`
- **Vue**: Composition API with `<script setup>`, Pinia stores with `defineStore('name', () => { ... })`, Element Plus components (auto-imported, no manual registration needed)
- **Error codes**: HTTP status codes used as-is; business error codes in 1001-1999 range (see `docs/api-doc.md` section 9)
- **SSH credentials**: Passwords stored AES-encrypted; Apache MINA SSHD for connections, SFTP for file transfer
- **Audit**: `@OperationLog` annotation on controller methods triggers `OperationLogAspect` to record operations to `operation_log` table
- **Date format**: ISO 8601 (`yyyy-MM-ddTHH:mm:ss`) in all API responses
