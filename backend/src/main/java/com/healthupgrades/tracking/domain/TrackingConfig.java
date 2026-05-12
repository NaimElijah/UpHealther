package com.healthupgrades.tracking.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "tracking_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID upgradeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrackingType trackingType;

    @Enumerated(EnumType.STRING)
    private Frequency frequency;

    private Double targetNumericValue;

    private String targetUnit;

    private Boolean requiredDaily;
}
