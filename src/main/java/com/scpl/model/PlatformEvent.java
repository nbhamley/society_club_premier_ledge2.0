package com.scpl.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Appears on the platform home (scope PLATFORM) or on one society's page (scope SOCIETY). */
@Entity
@Table(name = "platform_events")
@Getter
@Setter
@NoArgsConstructor
public class PlatformEvent extends TimestampedEntity implements SocietyScoped {

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "event_date")
    private LocalDateTime eventDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventScope scope = EventScope.PLATFORM;

    /** Null for platform-wide events. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "society_id")
    private Society society;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type = EventType.OTHER;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "external_link")
    private String externalLink;

    @Column(nullable = false)
    private Boolean published = Boolean.FALSE;

    @Override
    public Long getSocietyId() {
        return society == null ? null : society.getId();
    }
}