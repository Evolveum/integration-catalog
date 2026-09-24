/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.object;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "connector")
@Getter @Setter
@Accessors(chain = true)
public class Connector implements SetOwnership, GetOwnershipOneMaintainer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String revision;

    @OneToOne
    @JoinColumn(name = "author")
    private Author author;

    @OneToOne
    @JoinColumn(name = "maintainer")
    private Maintainer maintainer;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updated;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "fully_qualified_class_name")
    private String fullyQualifiedClassName;

    /**
     * Id of the connector this row was copy-on-write cloned from (null for connectors created
     * directly). Set by the edit flow when a shared connector is cloned so a draft's changes stay
     * invisible until approval; the approve step uses it to fold a same-version metadata edit back
     * into the original connector, so every integration method linking it sees the correction.
     */
    @Column(name = "cloned_from")
    private Integer clonedFrom;

    @ManyToOne
    @JoinColumn(name = "connector_bundle_id", nullable = false)
    private ConnectorBundle connectorBundle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @OneToMany(mappedBy = "connector", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConnectorVersion> connectorVersions = new ArrayList<>();

    @OneToMany(mappedBy = "connector", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ConnectorConnectorTag> connectorConnectorTags;

    /**
     * @param bundle the bundle the clone belongs to, itself a draft of the source's bundle
     */
    public static Connector createConnectorDraft(Connector source, ConnectorBundle bundle) {
        Connector clone = new Connector();
        clone.setRevision(source.getRevision());
        clone.setAuthor(source.getAuthor());
        clone.setMaintainer(source.getMaintainer());
        clone.setDisplayName(source.getDisplayName());
        clone.setFullyQualifiedClassName(source.getFullyQualifiedClassName());
        clone.setDescription(source.getDescription());
        clone.setConnectorBundle(bundle);
        // Always the first original, so a clone of a clone still folds back into it on approve.
        clone.setClonedFrom(source.getClonedFrom() != null ? source.getClonedFrom() : source.getId());
        // A version-bump approval deletes the original and keeps the this, so the tags must travel with it.
        Set<ConnectorConnectorTag> thisTags = new HashSet<>();
        if (source.getConnectorConnectorTags() != null) {
            for (ConnectorConnectorTag srcTag : source.getConnectorConnectorTags()) {
                ConnectorConnectorTag tag = new ConnectorConnectorTag(srcTag);
                tag.setConnector(clone);
                thisTags.add(tag);
            }
        }
        clone.setConnectorConnectorTags(thisTags);
        return clone;
    }
}
