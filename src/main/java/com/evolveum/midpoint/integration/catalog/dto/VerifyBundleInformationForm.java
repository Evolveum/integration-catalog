package com.evolveum.midpoint.integration.catalog.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class VerifyBundleInformationForm {
    private String version;
    private String className;
    private UUID oid;
    private String bundleName;

}
