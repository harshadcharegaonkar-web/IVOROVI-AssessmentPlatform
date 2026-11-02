package com.ivorovi.tenancy.internal;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InstitutionRepository extends JpaRepository<Institution, UUID> {

    @Query("""
            select i from Institution i
            where (:q is null or lower(i.name) like lower(concat('%', cast(:q as string), '%'))
                   or lower(i.code) like lower(concat('%', cast(:q as string), '%')))""")
    Page<Institution> search(@Param("q") String q, Pageable pageable);

    @Query("""
            select i from Institution i
            where i.id in :ids and (:q is null or lower(i.name) like lower(concat('%', cast(:q as string), '%'))
                   or lower(i.code) like lower(concat('%', cast(:q as string), '%')))""")
    Page<Institution> searchWithin(@Param("ids") Collection<UUID> ids, @Param("q") String q, Pageable pageable);
}
