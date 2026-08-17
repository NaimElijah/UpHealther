# HealthUpgrades 🌱

A full-stack health upgrade planning and tracking platform. Plan, activate, and track your health improvements — from drinking more water to meditating daily, from better sleep habits to product replacements.

## What is a Health Upgrade?

A **Health Upgrade** is more than a habit. It can be:
- A new habit (drink 2L water daily)
- A one-time action (book a dentist appointment)
- A product replacement (replace toxic cleaning products)
- A routine (morning meditation + stretch)
- A goal (lose 5kg by summer)
- An experiment (no caffeine after 2pm for two weeks)
- A learning task (read about intermittent fasting)
- A medical/preventive task (get bloodwork done)

## Features

- 🔐 **JWT Authentication** — register, login, secure routes
- 🗂️ **Health Areas** — organize upgrades by area (Fitness, Nutrition, Sleep, etc.)
- 📋 **Upgrade Management** — full lifecycle: Idea → Planned → Active → Completed/Paused/Abandoned
- 📊 **Progress Tracking** — boolean, numeric, text, or rating tracking
- 🔥 **Streak Calculation** — track your current and longest streaks
- 💡 **Reflections** — periodic reviews of what's working and what isn't
- 📈 **Dashboard** — overview of today's tasks, active upgrades, completion rates, streaks
- ✅ **Daily Check-in** — log progress for all active upgrades at once
- 🏗️ **Domain Events** — clean internal event architecture
- 🐳 **Docker Compose** — one-command startup

## Tech Stack

### Backend
| Technology | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.2.x |
| Spring Security + JWT | JJWT 0.12.x |
| Spring Data JPA | - |
| PostgreSQL | 15 |
| Flyway | - |
| Maven | 3.x |
| JUnit 5 + Mockito | - |

### Frontend
| Technology | Version |
|-----------|---------|
| React | 18 |
| TypeScript | 5 |
| Vite | 5 |
| TanStack Query | 5 |
| React Router | 6 |
| Tailwind CSS | 3 |
| Axios | 1.6 |

## Architecture

### Backend — Hexagonal (Ports & Adapters) + DDD

Every bounded context (`auth, user, healtharea, upgrade, tracking, reflection, reminder, dashboard,
notification`) follows the same ports-and-adapters skeleton:

```
com.healthupgrades.<context>/
  domain/
    model/          — JPA entities, enums, value objects (the aggregate + its state machine)
    service/        — pure, framework-free domain services
    port/out/       — outbound ports (repository SPI, push, event publisher)
  application/
    <X>Service      — @Transactional use-case orchestration
    port/in/        — inbound ports (use-case / query interfaces) + command/result records
  adapter/
    in/web/         — REST controllers, request records, response DTOs, web mappers
    in/{event,scheduling}/ — domain-event listener / scheduler (notification)
    out/persistence/ — Spring Data *JpaRepository + the adapter implementing the outbound port
    out/messaging/   — STOMP push adapter (notification)

com.healthupgrades.common/
  domain/{event,exception}/ — framework-free shared kernel (domain events + shared exceptions)
  adapter/{in,out}/...      — cross-cutting adapters (event publisher, event logger, error handler)
  security/                 — JWT filter, SecurityConfig, SecurityUser, UserDetailsService
  websocket/                — STOMP config + JWT channel interceptor
```

The boundaries are **enforced by ArchUnit** (`HexagonalArchitectureTest`, runs in `mvn test`): the domain
stays framework-free (apart from JPA mappings), the application depends only on ports, Spring Data is
confined to the persistence adapters, and bounded contexts interact only through inbound ports or published
DTOs. See [`docs/ADRs/ADR-001-ddd-hexagonal-architecture.md`](docs/ADRs/ADR-001-ddd-hexagonal-architecture.md).

### Domain Model

**HealthUpgrade state machine:**
```
IDEA → PLANNED → ACTIVE ⇄ PAUSED
                ACTIVE → COMPLETED
                ACTIVE → ABANDONED
```

**Business rules:**
- A completed upgrade cannot be reactivated or paused
- An abandoned upgrade cannot be reactivated without rescheduling
- Max 3 HARD difficulty upgrades can be active simultaneously
- Duplicate progress entries for the same upgrade+date are rejected (HTTP 409)
- Optimistic locking prevents concurrent edit conflicts (HTTP 409)

### Domain Services

- **StreakCalculator** — counts consecutive days with successful progress
- **ProgressEvaluationService** — checks if a progress entry meets the tracking target
- **DashboardAggregationService** — builds the dashboard summary
- **UpgradeSchedulingService** — enforces the 3 HARD upgrade limit

### Domain Events (in-process)

Events are published through a `DomainEventPublisher` outbound port (a Spring-backed adapter over
`ApplicationEventPublisher`), so the application layer stays decoupled from the framework's event bus:
- `HealthUpgradeCreated`, `HealthUpgradePlanned`, `HealthUpgradeActivated`
- `HealthUpgradePaused`, `HealthUpgradeCompleted`, `HealthUpgradeAbandoned`
- `ProgressEntryRecorded`, `ReflectionAdded`, `StreakAchieved`, `UpgradeOverdueDetected`

## API Overview

### Auth
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login, receive JWT |
| GET | `/api/auth/me` | Get current user info |

### Health Areas
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health-areas` | List all user's health areas |
| POST | `/api/health-areas` | Create a health area |
| PUT | `/api/health-areas/{id}` | Update a health area |
| DELETE | `/api/health-areas/{id}` | Delete a health area |

### Health Upgrades
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/upgrades` | List upgrades (filter: status, type, areaId, difficulty) |
| POST | `/api/upgrades` | Create an upgrade |
| GET | `/api/upgrades/{id}` | Get upgrade detail |
| PUT | `/api/upgrades/{id}` | Update upgrade |
| DELETE | `/api/upgrades/{id}` | Delete upgrade |
| POST | `/api/upgrades/{id}/plan` | Plan an upgrade |
| POST | `/api/upgrades/{id}/activate` | Activate an upgrade |
| POST | `/api/upgrades/{id}/pause` | Pause an upgrade |
| POST | `/api/upgrades/{id}/complete` | Complete an upgrade |
| POST | `/api/upgrades/{id}/abandon` | Abandon an upgrade |
| POST | `/api/upgrades/{id}/reschedule` | Reschedule an upgrade |

### Progress & Reflections
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/upgrades/{id}/progress` | Log progress entry |
| GET | `/api/upgrades/{id}/progress` | Get progress history |
| GET | `/api/progress/today` | Today's progress entries |
| GET | `/api/progress/week` | This week's progress |
| POST | `/api/upgrades/{id}/reflections` | Add reflection |
| GET | `/api/upgrades/{id}/reflections` | Get reflections |

### Dashboard
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/dashboard` | Full dashboard summary |

## Setup Instructions

### Prerequisites
- Java 21+
- Maven 3.8+
- Node.js 20+
- PostgreSQL 15+ (or Docker)

### Run Without Docker

1. **Clone the repository**
   ```bash
   git clone <repo-url>
   cd Health_Upgrades_Tracker
   ```

2. **Set up environment**
   ```bash
   cp .env.example .env
   # Edit .env as needed
   ```

3. **Create PostgreSQL database**
   ```sql
   CREATE DATABASE healthupgrades;
   CREATE USER healthupgrades WITH PASSWORD 'healthupgrades';
   GRANT ALL PRIVILEGES ON DATABASE healthupgrades TO healthupgrades;
   ```

4. **Run the backend**
   ```bash
   cd backend
   export DB_URL=jdbc:postgresql://localhost:5432/healthupgrades
   export DB_USERNAME=healthupgrades
   export DB_PASSWORD=healthupgrades
   export JWT_SECRET=your-secret-key-at-least-256-bits
   mvn spring-boot:run
   ```

5. **Run the frontend**
   ```bash
   cd frontend
   npm install
   npm run dev
   ```

6. Open http://localhost:3000

### Run With Docker Compose

```bash
cp .env.example .env
# Edit .env if needed (especially JWT_SECRET for production)
docker-compose up --build
```

- Frontend: http://localhost:3000
- Backend: http://localhost:8080
- API docs: http://localhost:8080/actuator/health

### Demo Account

After startup, a demo account is available:
- **Email:** `demo@healthupgrades.com`
- **Password:** `demo123`

## Running Tests

### Backend Tests
```bash
cd backend
mvn test
```

Tests cover:
- HealthUpgrade state machine business rules
- StreakCalculator (various entry patterns)
- ProgressEvaluationService (boolean/numeric/rating/text)
- Hexagonal architecture rules (ArchUnit) — domain purity, port/adapter boundaries, bounded-context isolation

### Frontend Build (includes type check)
```bash
cd frontend
npm run build
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_DB` | `healthupgrades` | PostgreSQL database name |
| `POSTGRES_USER` | `healthupgrades` | PostgreSQL username |
| `POSTGRES_PASSWORD` | `healthupgrades` | PostgreSQL password |
| `DB_URL` | `jdbc:postgresql://localhost:5432/healthupgrades` | Full JDBC URL |
| `JWT_SECRET` | (default dev key) | JWT signing secret (change in production!) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated origins allowed to call the API cross-origin (only needed when not using the dev/nginx `/api` proxy) |
| `VITE_API_URL` | (empty) | Backend API URL for frontend. Leave empty to use the same-origin `/api` proxy (Vite in dev, nginx in prod) — recommended. Set only if the API is on another origin. |

## Future Improvements

- [ ] Reminders / push notifications
- [ ] AI-powered suggestions (lifestyle only, not medical advice)
- [ ] Social features: share upgrades, accountability partners
- [ ] Mobile app (React Native)
- [ ] Export progress data (CSV/PDF)
- [ ] Gamification: achievements, badges, levels
- [ ] Integration with wearables (steps, sleep data)
- [ ] Weekly/monthly progress email reports
- [ ] Dark mode
- [ ] Internationalization (i18n)

## Screenshots

*(Coming soon — run the app to see it in action)*

## Disclaimer

> HealthUpgrades is a lifestyle planning tool. It is **not** a medical application and does not provide medical advice, diagnosis, or treatment. Always consult a healthcare professional for medical decisions.

## Resume Summary

**HealthUpgrades** is a production-quality full-stack web application built with Java 21 / Spring Boot 3 backend and React / TypeScript frontend. It demonstrates:
- Clean architecture: Domain-Driven Design (DDD) + Hexagonal (Ports & Adapters), enforced with ArchUnit
- JWT authentication with Spring Security
- PostgreSQL with Flyway migrations and optimistic locking
- Domain events for clean separation of concerns
- RESTful API design with proper HTTP semantics
- React with TanStack Query for efficient data fetching
- Tailwind CSS for responsive UI
- Docker Compose for one-command deployment
- GitHub Actions CI pipeline
- Comprehensive unit tests for domain logic