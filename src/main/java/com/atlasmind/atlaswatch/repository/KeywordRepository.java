package com.atlasmind.atlaswatch.repository;

import com.atlasmind.atlaswatch.models.Keyword;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KeywordRepository extends JpaRepository<Keyword, Long> {

    Optional<Keyword> findByTmdbId(Integer tmdbId);

    List<Keyword> findByTmdbIdIn(List<Integer> tmdbIds);
}
