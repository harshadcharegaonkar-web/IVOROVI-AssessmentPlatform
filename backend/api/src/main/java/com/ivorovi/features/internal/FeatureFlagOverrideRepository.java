package com.ivorovi.features.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureFlagOverrideRepository extends JpaRepository<FeatureFlagOverride, UUID> {

    @Query("""
            select o from FeatureFlagOverride o
            where o.flagKey = :key and ((:unit is null and o.orgUnitId is null) or o.orgUnitId = :unit)""")
    Optional<FeatureFlagOverride> findOverride(@Param("key") String key, @Param("unit") UUID orgUnitId);
}
