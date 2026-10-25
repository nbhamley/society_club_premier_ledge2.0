package com.scpl.repo;

import com.scpl.model.GalleryImage;

import java.util.List;

public interface GalleryImageRepository extends SocietyScopedRepository<GalleryImage, Long> {

    List<GalleryImage> findBySociety_IdOrderByCreatedAtDesc(Long societyId);

    List<GalleryImage> findTop12BySociety_IdOrderByCreatedAtDesc(Long societyId);

    List<GalleryImage> findBySeason_IdOrderByCreatedAtDesc(Long seasonId);
}