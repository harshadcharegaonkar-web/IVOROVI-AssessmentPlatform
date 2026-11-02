package com.ivorovi.features.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureFlagOverrideRepository extends JpaRepository<FeatureFlagOverride, UUID> {

    @Query("""
            select o from FeatureFlagOverride o
            where o.flagKey = :key and ((:inst is null and o.institutionId is null) or o.institutionId = :inst)""")
    Optional<FeatureFlagOverride> findOverride(@Param("key") String key, @Param("inst") UUID institutionId);
}
