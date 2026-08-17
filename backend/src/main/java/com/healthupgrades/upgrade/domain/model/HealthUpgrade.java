package com.healthupgrades.upgrade.domain.model;

import com.healthupgrades.common.domain.exception.BusinessRuleException;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "health_upgrades")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthUpgrade {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UpgradeStatus status;

    @Enumerated(EnumType.STRING)
    private Difficulty difficulty;

    private LocalDate plannedStartDate;

    private LocalDate actualStartDate;

    private LocalDate targetEndDate;

    private String motivation;

    private String successCriteria;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (status == null) status = UpgradeStatus.IDEA;
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void plan(LocalDate plannedStart) {
        if (status != UpgradeStatus.IDEA) {
            throw new BusinessRuleException("Only IDEA upgrades can be planned");
        }
        this.plannedStartDate = plannedStart;
        this.status = UpgradeStatus.PLANNED;
    }

    public void activate(LocalDate startDate) {
        if (status != UpgradeStatus.PLANNED && status != UpgradeStatus.PAUSED) {
            throw new BusinessRuleException("Only PLANNED or PAUSED upgrades can be activated");
        }
        this.actualStartDate = startDate;
        this.status = UpgradeStatus.ACTIVE;
    }

    public void pause() {
        if (status != UpgradeStatus.ACTIVE) {
            throw new BusinessRuleException("Only ACTIVE upgrades can be paused");
        }
        this.status = UpgradeStatus.PAUSED;
    }

    public void complete() {
        if (status != UpgradeStatus.ACTIVE) {
            throw new BusinessRuleException("Only ACTIVE upgrades can be completed");
        }
        this.status = UpgradeStatus.COMPLETED;
    }

    public void abandon() {
        if (status == UpgradeStatus.COMPLETED || status == UpgradeStatus.ABANDONED) {
            throw new BusinessRuleException("Cannot abandon a COMPLETED or already ABANDONED upgrade");
        }
        this.status = UpgradeStatus.ABANDONED;
    }

    public void reschedule(LocalDate newDate) {
        if (status == UpgradeStatus.COMPLETED) {
            throw new BusinessRuleException("Cannot reschedule a COMPLETED upgrade");
        }
        this.plannedStartDate = newDate;
        if (status == UpgradeStatus.ABANDONED) {
            this.status = UpgradeStatus.PLANNED;
        }
    }

    public void changeDifficulty(Difficulty difficulty) {
        this.difficulty = difficulty;
    }

    /**
     * Whether the upgrade is past its target date as of the given date (ACTIVE upgrades only).
     *
     * <p>The caller supplies the date rather than the aggregate reading the system clock, so callers that
     * inject a {@link java.time.Clock} actually get to decide what "now" means.
     */
    public boolean isOverdue(LocalDate asOf) {
        return targetEndDate != null
                && status == UpgradeStatus.ACTIVE
                && asOf.isAfter(targetEndDate);
    }

    public boolean isActiveOn(LocalDate date) {
        if (status != UpgradeStatus.ACTIVE) return false;
        if (actualStartDate != null && date.isBefore(actualStartDate)) return false;
        if (targetEndDate != null && date.isAfter(targetEndDate)) return false;
        return true;
    }
}
