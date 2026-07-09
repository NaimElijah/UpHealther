package com.healthupgrades.common.adapter.in.event;
import com.healthupgrades.common.domain.event.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EventHandler {

    @EventListener
    public void handle(HealthUpgradeCreated event) {
        log.debug("Event: HealthUpgradeCreated - upgradeId={}, userId={}, title={}",
                event.upgradeId(), event.userId(), event.title());
    }

    @EventListener
    public void handle(HealthUpgradePlanned event) {
        log.debug("Event: HealthUpgradePlanned - upgradeId={}, plannedStart={}",
                event.upgradeId(), event.plannedStartDate());
    }

    @EventListener
    public void handle(HealthUpgradeActivated event) {
        log.debug("Event: HealthUpgradeActivated - upgradeId={}, startDate={}",
                event.upgradeId(), event.startDate());
    }

    @EventListener
    public void handle(HealthUpgradePaused event) {
        log.debug("Event: HealthUpgradePaused - upgradeId={}", event.upgradeId());
    }

    @EventListener
    public void handle(HealthUpgradeCompleted event) {
        log.debug("Event: HealthUpgradeCompleted - upgradeId={}", event.upgradeId());
    }

    @EventListener
    public void handle(HealthUpgradeAbandoned event) {
        log.debug("Event: HealthUpgradeAbandoned - upgradeId={}", event.upgradeId());
    }

    @EventListener
    public void handle(ProgressEntryRecorded event) {
        log.debug("Event: ProgressEntryRecorded - progressId={}, upgradeId={}, date={}",
                event.progressId(), event.upgradeId(), event.date());
    }

    @EventListener
    public void handle(ReflectionAdded event) {
        log.debug("Event: ReflectionAdded - reflectionId={}, upgradeId={}", event.reflectionId(), event.upgradeId());
    }

    @EventListener
    public void handle(StreakAchieved event) {
        log.debug("Event: StreakAchieved - upgradeId={}, streakDays={}", event.upgradeId(), event.streakDays());
    }

    @EventListener
    public void handle(UpgradeOverdueDetected event) {
        log.debug("Event: UpgradeOverdueDetected - upgradeId={}", event.upgradeId());
    }
}
