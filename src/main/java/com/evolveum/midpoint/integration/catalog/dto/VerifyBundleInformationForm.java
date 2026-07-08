package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class VerifyBundleInformationForm {
    private String version;    // connector_bundle_version.bundle_version
    private String className;  // connector.fully_qualified_class_name
    private UUID oid;          // application.id
    private String bundleName; // connector_bundle.bundle_name

}
