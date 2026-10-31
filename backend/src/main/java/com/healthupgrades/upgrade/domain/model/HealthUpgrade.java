package com.healthupgrades.upgrade.domain.model;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.*;

/**
 * The core aggregate: a planned health improvement moving through its lifecycle.
 *
 * <p>The aggregate owns its state machine, so {@code status} has no setter and can only change through
 * {@link #plan}, {@link #activate}, {@link #pause}, {@link #complete}, {@link #abandon} and
 * {@link #reschedule}, each of which guards the transition. Editable descriptive fields change through
 * {@link #updateDetails}. Production code creates instances via {@link #create}, which enforces the
 * invariants a new upgrade must satisfy; the builder backs {@code create} and the tests that need an
 * aggregate already in a particular state. Hibernate builds an entity through the protected no-argument
 * constructor.
 */
@Entity
@Table(name = "health_upgrades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for general use
@AllArgsConstructor(access = AccessLevel.PRIVATE) // backs the builder only
@Builder
public class HealthUpgrade {

    /** Why a retired type is refused, on creation and on an edit that would move to one. */
    static final String RETIRED_TYPE = "That kind of upgrade is retired; choose another";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    private UUID areaId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UpgradeType type;

    /**
     * Defaulted on the builder rather than in {@code @PrePersist}: the column is {@code nullable = false},
     * and a callback-based default only repairs the object on the way to the database, so an aggregate
     * built without a status was valid in a unit test and a flush-time failure in production.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UpgradeStatus status = UpgradeStatus.IDEA;

    @Enumerated(EnumType.STRING)
    private Difficulty difficulty;

    private LocalDate plannedStartDate;

    private LocalDate actualStartDate;

    private LocalDate targetEndDate;

    private String motivation;

    private String successCriteria;

    /**
     * When the upgrade was completed; null until it is. Set by {@link #complete} alone, and COMPLETED is
     * terminal, so an edit afterwards never moves it the way it moves {@code updatedAt} (FR-27, ADR-080).
     */
    private LocalDateTime completedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Optimistic lock (BR-14): two saves that overlap inside the server are refused with a 409. No request
     * carries the version back yet, so an edit from a stale page is not refused (#194).
     */
    @Version
    private Long version;

    /**
     * Creates a new upgrade in the IDEA state.
     *
     * <p>The entry point for creating an upgrade, so the invariants live here rather than being deferred
     * to a database constraint at flush time: an upgrade always belongs to a user, always has a title,
     * always has a type, and always starts as an IDEA.
     *
     * @param userId           the owner; required
     * @param areaId           the health area it is filed under; may be null
     * @param title            short name; required and non-blank
     * @param description      longer explanation; may be null
     * @param type             what kind of change this is; required, and not a retired type
     * @param difficulty       how demanding it is; may be null
     * @param plannedStartDate the intended start date; may be null
     * @param targetEndDate    the date it should be done by; may be null
     * @param motivation       why the user wants it; may be null
     * @param successCriteria  how the user will know it worked; may be null
     * @return the new upgrade, in the IDEA state
     * @throws BusinessRuleException if the owner, title or type is missing, or the type is retired
     */
    public static HealthUpgrade create(
            UUID userId,
            UUID areaId,
            String title,
            String description,
            UpgradeType type,
            Difficulty difficulty,
            LocalDate plannedStartDate,
            LocalDate targetEndDate,
            String motivation,
            String successCriteria) {
        if (userId == null) throw new BusinessRuleException("An upgrade must belong to a user");
        if (title == null || title.isBlank()) throw new BusinessRuleException("An upgrade must have a title");
        if (type == null) throw new BusinessRuleException("An upgrade must have a type");
        if (type.isRetired()) throw new BusinessRuleException(RETIRED_TYPE);

        return HealthUpgrade.builder()
                .userId(userId)
                .areaId(areaId)
                .title(title)
                .description(description)
                .type(type)
                .status(UpgradeStatus.IDEA) // every upgrade starts as an idea
                .difficulty(difficulty)
                .plannedStartDate(plannedStartDate)
                .targetEndDate(targetEndDate)
                .motivation(motivation)
                .successCriteria(successCriteria)
                .build();
    }

    /**
     * Replaces the descriptive fields a user may edit at any point in the lifecycle.
     *
     * <p>Deliberately cannot touch status, the start dates or difficulty: those move only through the
     * transition methods, {@link #reschedule} and {@link #changeDifficulty} respectively.
     *
     * <p>A retired type is accepted only when the upgrade already holds it: the SPA sends the type back
     * unchanged on every edit, and a row that carries one must stay editable, but none may move to it.
     *
     * @throws BusinessRuleException if the title is blanked out, the type is missing, or a retired type
     *         is chosen for an upgrade that does not already hold it
     */
    public void updateDetails(
            UUID areaId,
            String title,
            String description,
            UpgradeType type,
            LocalDate targetEndDate,
            String motivation,
            String successCriteria) {
        if (title == null || title.isBlank()) throw new BusinessRuleException("An upgrade must have a title");
        if (type == null) throw new BusinessRuleException("An upgrade must have a type");
        if (type.isRetired() && type != this.type) throw new BusinessRuleException(RETIRED_TYPE);

        this.areaId = areaId;
        this.title = title;
        this.description = description;
        this.type = type;
        this.targetEndDate = targetEndDate;
        this.motivation = motivation;
        this.successCriteria = successCriteria;
    }

    /**
     * Stamps the creation and update timestamps before the row is first inserted.
     *
     * <p>Reads the JVM's default zone rather than the injected clock: the one place NFR-15 allows it (ADR-020,
     * #51).
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Refreshes the update timestamp before each update, from the JVM's default zone as
     * {@link #onCreate} does.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Commits an idea to a start date: IDEA → PLANNED.
     *
     * @param plannedStart the date the user intends to start; not required to be in the future, since
     *                     users plan retroactively
     * @throws BusinessRuleException if the upgrade is not an IDEA, or no date is given
     */
    public void plan(LocalDate plannedStart) {
        if (status != UpgradeStatus.IDEA) {
            throw new BusinessRuleException("Only IDEA upgrades can be planned");
        }
        if (plannedStart == null) throw new BusinessRuleException("A planned upgrade must have a start date");
        this.plannedStartDate = plannedStart;
        this.status = UpgradeStatus.PLANNED;
    }

    /**
     * Starts or resumes an upgrade: PLANNED → ACTIVE, or PAUSED → ACTIVE.
     *
     * <p>Records the date it started running, which opens the window {@link #isActiveOn} puts it on a
     * day's check-in list for. A resume replaces it, so after a pause it is the latest start, not the
     * first (#263); progress and streaks do not read it. The max-concurrent-HARD rule is <em>not</em>
     * checked here — it needs a count across the user's other upgrades, which the aggregate cannot see;
     * {@code UpgradeService} applies it first.
     *
     * @param startDate the date the upgrade starts running
     * @throws BusinessRuleException if the upgrade is not PLANNED or PAUSED, or no date is given
     */
    public void activate(LocalDate startDate) {
        if (!canActivate()) {
            throw new BusinessRuleException("Only PLANNED or PAUSED upgrades can be activated");
        }
        if (startDate == null) throw new BusinessRuleException("An active upgrade must have a start date");
        this.actualStartDate = startDate;
        this.status = UpgradeStatus.ACTIVE;
    }

    /**
     * Whether {@link #activate} would accept this upgrade in its current state.
     *
     * <p>For the caller that must decide something before the transition, as the service decides whether
     * an activation would claim a HARD slot: one the transition refuses claims none.
     *
     * @return true when the upgrade is PLANNED or PAUSED
     */
    public boolean canActivate() {
        return status == UpgradeStatus.PLANNED || status == UpgradeStatus.PAUSED;
    }

    /**
     * Suspends a running upgrade: ACTIVE → PAUSED, releasing any HARD slot it held.
     *
     * @throws BusinessRuleException if the upgrade is not ACTIVE
     */
    public void pause() {
        if (status != UpgradeStatus.ACTIVE) {
            throw new BusinessRuleException("Only ACTIVE upgrades can be paused");
        }
        this.status = UpgradeStatus.PAUSED;
    }

    /**
     * Finishes a running upgrade successfully: ACTIVE → COMPLETED, which is terminal, recording when.
     *
     * @param at the moment it was completed, from the caller's clock
     * @throws BusinessRuleException if the upgrade is not ACTIVE, or no moment is given
     */
    public void complete(LocalDateTime at) {
        if (status != UpgradeStatus.ACTIVE) {
            throw new BusinessRuleException("Only ACTIVE upgrades can be completed");
        }
        if (at == null) throw new BusinessRuleException("A completed upgrade must record when it was completed");
        this.completedAt = at;
        this.status = UpgradeStatus.COMPLETED;
    }

    /**
     * Gives an upgrade up, from any state except COMPLETED and ABANDONED: → ABANDONED.
     *
     * <p>Reversible through {@link #reschedule}, unlike completion.
     *
     * @throws BusinessRuleException if the upgrade is already COMPLETED or ABANDONED
     */
    public void abandon() {
        if (status == UpgradeStatus.COMPLETED || status == UpgradeStatus.ABANDONED) {
            throw new BusinessRuleException("Cannot abandon a COMPLETED or already ABANDONED upgrade");
        }
        this.status = UpgradeStatus.ABANDONED;
    }

    /**
     * Moves the planned start date, and revives an abandoned upgrade back to PLANNED.
     *
     * <p>The revival is the one transition that is not named after its target state: rescheduling
     * something you had given up on is how you take it back on.
     *
     * @param newDate the new planned start date
     * @throws BusinessRuleException if the upgrade is COMPLETED, or no date is given: a reschedule
     *         cannot clear the date, and a revival without one would be PLANNED for no day at all
     */
    public void reschedule(LocalDate newDate) {
        if (status == UpgradeStatus.COMPLETED) {
            throw new BusinessRuleException("Cannot reschedule a COMPLETED upgrade");
        }
        if (newDate == null) throw new BusinessRuleException("A rescheduled upgrade must have a start date");
        this.plannedStartDate = newDate;
        if (status == UpgradeStatus.ABANDONED) {
            this.status = UpgradeStatus.PLANNED;
        }
    }

    /**
     * Sets the difficulty.
     *
     * <p>Unguarded here on purpose: promoting an upgrade to HARD may breach the concurrent-HARD limit,
     * and that check needs the user's other upgrades. {@code UpgradeService} validates before calling.
     *
     * @param difficulty the new difficulty
     */
    public void changeDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    /**
     * Whether the upgrade is past its target date as of the given date (ACTIVE upgrades only).
     *
     * <p>The caller supplies the date rather than the aggregate reading the system clock, so callers that
     * inject a {@link java.time.Clock} actually get to decide what "now" means.
     *
     * @param asOf the day to judge against; must not be null
     * @return true when the upgrade is ACTIVE, has a target end date, and {@code asOf} is after it
     */
    public boolean isOverdue(LocalDate asOf) {
        return targetEndDate != null && status == UpgradeStatus.ACTIVE && asOf.isAfter(targetEndDate);
    }

    /**
     * Whether the upgrade is running on the given date, i.e. ACTIVE and within its date window.
     *
     * <p>Used to decide what belongs on a day's check-in list. An absent start or end date is treated as
     * open-ended rather than as a failed match.
     *
     * @param date the day being asked about
     * @return true if progress is expected on that date
     */
    public boolean isActiveOn(LocalDate date) {
        if (status != UpgradeStatus.ACTIVE) return false;
        if (actualStartDate != null && date.isBefore(actualStartDate)) return false;
        if (targetEndDate != null && date.isAfter(targetEndDate)) return false;
        return true;
    }
}
