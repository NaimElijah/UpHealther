package com.healthupgrades.healtharea.application;

import com.healthupgrades.common.exception.ResourceNotFoundException;
import com.healthupgrades.healtharea.api.HealthAreaDto;
import com.healthupgrades.healtharea.api.HealthAreaRequest;
import com.healthupgrades.healtharea.domain.HealthArea;
import com.healthupgrades.healtharea.domain.port.out.HealthAreaRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HealthAreaService {

    private final HealthAreaRepositoryPort repository;

    @Transactional
    public HealthAreaDto create(UUID userId, HealthAreaRequest req) {
        HealthArea area = HealthArea.builder()
                .userId(userId)
                .name(req.name())
                .description(req.description())
                .priority(req.priority())
                .icon(req.icon())
                .color(req.color())
                .build();
        return toDto(repository.save(area));
    }

    public List<HealthAreaDto> findAll(UUID userId) {
        return repository.findByUserId(userId).stream().map(this::toDto).toList();
    }

    public HealthAreaDto findById(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("HealthArea not found: " + id));
    }

    @Transactional
    public HealthAreaDto update(UUID userId, UUID id, HealthAreaRequest req) {
        HealthArea area = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("HealthArea not found: " + id));
        area.setName(req.name());
        area.setDescription(req.description());
        area.setPriority(req.priority());
        area.setIcon(req.icon());
        area.setColor(req.color());
        return toDto(repository.save(area));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        HealthArea area = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("HealthArea not found: " + id));
        repository.delete(area);
    }

    private HealthAreaDto toDto(HealthArea a) {
        return new HealthAreaDto(a.getId(), a.getUserId(), a.getName(), a.getDescription(),
                a.getPriority(), a.getIcon(), a.getColor(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
