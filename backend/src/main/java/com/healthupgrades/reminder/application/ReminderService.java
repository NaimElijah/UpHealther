package com.healthupgrades.reminder.application;

import com.healthupgrades.common.exception.ResourceNotFoundException;
import com.healthupgrades.reminder.api.ReminderDto;
import com.healthupgrades.reminder.api.ReminderRequest;
import com.healthupgrades.reminder.domain.Reminder;
import com.healthupgrades.reminder.domain.port.out.ReminderRepositoryPort;
import com.healthupgrades.upgrade.application.UpgradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepositoryPort repository;
    private final UpgradeService upgradeService;

    @Transactional
    public ReminderDto create(UUID userId, UUID upgradeId, ReminderRequest req) {
        upgradeService.getUpgrade(userId, upgradeId); // ownership check (throws if not owned)
        Reminder reminder = Reminder.builder()
                .upgradeId(upgradeId)
                .reminderTime(req.reminderTime())
                .daysOfWeek(toCsv(req.daysOfWeek()))
                .enabled(req.enabled() == null || req.enabled())
                .build();
        return toDto(repository.save(reminder));
    }

    public List<ReminderDto> getForUpgrade(UUID userId, UUID upgradeId) {
        upgradeService.getUpgrade(userId, upgradeId);
        return repository.findByUpgradeId(upgradeId).stream().map(this::toDto).toList();
    }

    @Transactional
    public ReminderDto update(UUID userId, UUID reminderId, ReminderRequest req) {
        Reminder reminder = getOwnedReminder(userId, reminderId);
        reminder.setReminderTime(req.reminderTime());
        reminder.setDaysOfWeek(toCsv(req.daysOfWeek()));
        if (req.enabled() != null) reminder.setEnabled(req.enabled());
        return toDto(repository.save(reminder));
    }

    @Transactional
    public void delete(UUID userId, UUID reminderId) {
        Reminder reminder = getOwnedReminder(userId, reminderId);
        repository.delete(reminder);
    }

    private Reminder getOwnedReminder(UUID userId, UUID reminderId) {
        Reminder reminder = repository.findById(reminderId)
                .orElseThrow(() -> new ResourceNotFoundException("Reminder not found: " + reminderId));
        upgradeService.getUpgrade(userId, reminder.getUpgradeId()); // ownership via parent upgrade
        return reminder;
    }

    private String toCsv(List<String> days) {
        if (days == null || days.isEmpty()) return null;
        return String.join(",", days.stream().map(d -> d.trim().toUpperCase()).toList());
    }

    private List<String> fromCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private ReminderDto toDto(Reminder r) {
        return new ReminderDto(r.getId(), r.getUpgradeId(), r.getReminderTime(),
                fromCsv(r.getDaysOfWeek()), Boolean.TRUE.equals(r.getEnabled()));
    }
}
