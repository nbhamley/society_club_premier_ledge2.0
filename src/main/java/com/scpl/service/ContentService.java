package com.scpl.service;

import com.scpl.dto.AwardRequest;
import com.scpl.dto.GalleryRequest;
import com.scpl.dto.PlatformEventRequest;
import com.scpl.dto.SponsorRequest;
import com.scpl.exception.BusinessRuleException;
import com.scpl.exception.NotFoundException;
import com.scpl.model.Award;
import com.scpl.model.EventScope;
import com.scpl.model.EventType;
import com.scpl.model.GalleryImage;
import com.scpl.model.PlatformEvent;
import com.scpl.model.Season;
import com.scpl.model.Society;
import com.scpl.model.Sponsor;
import com.scpl.model.User;
import com.scpl.repo.AwardRepository;
import com.scpl.repo.GalleryImageRepository;
import com.scpl.repo.PlatformEventRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.SponsorRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.util.Text;
import com.scpl.util.Urls;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Sponsors, awards and gallery (society admins) and platform events (platform admin).
 * Audit actions are composed as ENTITY_CREATED / _UPDATED / _DELETED.
 */
@Service
@RequiredArgsConstructor
public class ContentService {

    private final SponsorRepository sponsors;
    private final AwardRepository awards;
    private final GalleryImageRepository gallery;
    private final PlatformEventRepository events;
    private final SeasonRepository seasons;
    private final PlayerRepository players;
    private final TeamRepository teams;
    private final SocietyRepository societies;
    private final AuditService audit;

    // ------------------------------------------------------------------ sponsors

    @Transactional(readOnly = true)
    public List<Sponsor> sponsors(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        return sponsors.findBySociety_IdOrderByNameAsc(societyId);
    }

    @Transactional
    public Sponsor saveSponsor(User actor, Long societyId, Long idOrNull, SponsorRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        Sponsor s = idOrNull == null ? new Sponsor() : sponsors.findByIdAndSociety_Id(idOrNull, societyId)
                .orElseThrow(() -> new NotFoundException("Sponsor not found"));
        if (idOrNull == null) {
            s.setSociety(societies.getReferenceById(societyId));
        }
        s.setName(r.name().trim());
        s.setTier(r.tier());
        s.setLogoUrl(Urls.requireWebUrl(r.logoUrl(), "Logo"));
        s.setWebsite(Urls.requireWebUrl(r.website(), "Website"));
        s.setAmount(r.amount() == null ? 0.0 : Math.max(0.0, r.amount()));
        s.setActive(r.active() == null || r.active());
        s.setSeason(season(societyId, r.seasonId()));
        s = sponsors.save(s);
        log(actor, societyId, "SPONSOR", idOrNull, s.getId(), s.getName());
        return s;
    }

    @Transactional
    public void deleteSponsor(User actor, Long societyId, Long id) {
        Tenancy.requireAdminOf(actor, societyId);
        Sponsor s = sponsors.findByIdAndSociety_Id(id, societyId).orElseThrow(() -> new NotFoundException("Sponsor not found"));
        sponsors.delete(s);
        log(actor, societyId, "SPONSOR", "DELETED", id, s.getName());
    }

    // ------------------------------------------------------------------ awards

    @Transactional(readOnly = true)
    public List<Award> awards(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        return awards.findBySociety_IdOrderByCreatedAtDesc(societyId);
    }

    @Transactional
    public Award saveAward(User actor, Long societyId, Long idOrNull, AwardRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        if (r.playerId() == null && r.teamId() == null) {
            throw new BusinessRuleException("Choose the player or the team that won this award");
        }
        Award a = idOrNull == null ? new Award() : awards.findByIdAndSociety_Id(idOrNull, societyId)
                .orElseThrow(() -> new NotFoundException("Award not found"));
        if (idOrNull == null) {
            a.setSociety(societies.getReferenceById(societyId));
        }
        a.setTitle(r.title().trim());
        a.setCategory(r.category());
        a.setDescription(Text.clean(r.description()));
        a.setImageUrl(Urls.requireWebUrl(r.imageUrl(), "Image"));
        a.setSeason(season(societyId, r.seasonId()));
        a.setPlayer(r.playerId() == null ? null : players.findByIdAndSociety_Id(r.playerId(), societyId)
                .orElseThrow(() -> new NotFoundException("Player not found")));
        a.setTeam(r.teamId() == null ? null : teams.findByIdAndSociety_Id(r.teamId(), societyId)
                .orElseThrow(() -> new NotFoundException("Team not found")));
        a = awards.save(a);
        log(actor, societyId, "AWARD", idOrNull, a.getId(), a.getTitle());
        return a;
    }

    @Transactional
    public void deleteAward(User actor, Long societyId, Long id) {
        Tenancy.requireAdminOf(actor, societyId);
        Award a = awards.findByIdAndSociety_Id(id, societyId).orElseThrow(() -> new NotFoundException("Award not found"));
        awards.delete(a);
        log(actor, societyId, "AWARD", "DELETED", id, a.getTitle());
    }

    // ------------------------------------------------------------------ gallery

    @Transactional(readOnly = true)
    public List<GalleryImage> gallery(User actor, Long societyId) {
        Tenancy.requireAdminOf(actor, societyId);
        return gallery.findBySociety_IdOrderByCreatedAtDesc(societyId);
    }

    @Transactional
    public GalleryImage saveImage(User actor, Long societyId, Long idOrNull, GalleryRequest r) {
        Tenancy.requireAdminOf(actor, societyId);
        GalleryImage g = idOrNull == null ? new GalleryImage() : gallery.findByIdAndSociety_Id(idOrNull, societyId)
                .orElseThrow(() -> new NotFoundException("Image not found"));
        if (idOrNull == null) {
            g.setSociety(societies.getReferenceById(societyId));
        }
        g.setImageUrl(Urls.requireWebUrl(r.imageUrl(), "Image"));
        g.setTitle(Text.clean(r.title()));
        g.setCaption(Text.clean(r.caption()));
        g.setSeason(season(societyId, r.seasonId()));
        g = gallery.save(g);
        log(actor, societyId, "GALLERY", idOrNull, g.getId(), g.getTitle());
        return g;
    }

    @Transactional
    public void deleteImage(User actor, Long societyId, Long id) {
        Tenancy.requireAdminOf(actor, societyId);
        GalleryImage g = gallery.findByIdAndSociety_Id(id, societyId).orElseThrow(() -> new NotFoundException("Image not found"));
        gallery.delete(g);
        log(actor, societyId, "GALLERY", "DELETED", id, g.getTitle());
    }

    // ------------------------------------------------------------------ platform events (platform admin)

    @Transactional(readOnly = true)
    public List<PlatformEvent> events(User actor) {
        Tenancy.requirePlatformAdmin(actor);
        return events.findAllByOrderByEventDateDesc();
    }

    @Transactional
    public PlatformEvent saveEvent(User actor, Long idOrNull, PlatformEventRequest r) {
        Tenancy.requirePlatformAdmin(actor);
        PlatformEvent e = idOrNull == null ? new PlatformEvent() : events.findById(idOrNull)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        EventScope scope = r.scope() == null ? EventScope.PLATFORM : r.scope();
        Society society = null;
        if (scope == EventScope.SOCIETY) {
            if (r.societyId() == null) {
                throw new BusinessRuleException("Choose the society this event belongs to");
            }
            society = societies.findById(r.societyId()).orElseThrow(() -> new NotFoundException("Society not found"));
        }
        e.setTitle(r.title().trim());
        e.setDescription(Text.clean(r.description()));
        e.setEventDate(r.eventDate());
        e.setScope(scope);
        e.setSociety(society);
        e.setType(r.type() == null ? EventType.OTHER : r.type());
        e.setThumbnailUrl(Urls.requireWebUrl(r.thumbnailUrl(), "Thumbnail"));
        e.setExternalLink(Urls.requireWebUrl(r.externalLink(), "Link"));
        e.setPublished(Boolean.TRUE.equals(r.published()));
        e = events.save(e);
        log(actor, society == null ? null : society.getId(), "EVENT", idOrNull, e.getId(), e.getTitle());
        return e;
    }

    @Transactional
    public void deleteEvent(User actor, Long id) {
        Tenancy.requirePlatformAdmin(actor);
        PlatformEvent e = events.findById(id).orElseThrow(() -> new NotFoundException("Event not found"));
        events.delete(e);
        log(actor, e.getSocietyId(), "EVENT", "DELETED", id, e.getTitle());
    }

    // ------------------------------------------------------------------ internals

    private Season season(Long societyId, Long seasonIdOrNull) {
        return seasonIdOrNull == null ? null : seasons.findByIdAndSociety_Id(seasonIdOrNull, societyId)
                .orElseThrow(() -> new NotFoundException("Season not found"));
    }

    private void log(User actor, Long societyId, String entity, Long requestedId, Long id, String name) {
        log(actor, societyId, entity, requestedId == null ? "CREATED" : "UPDATED", id, name);
    }

    private void log(User actor, Long societyId, String entity, String op, Long id, String name) {
        audit.record(actor, societyId, entity + "_" + op, entity, id, null, name == null ? null : Map.of("name", name));
    }
}