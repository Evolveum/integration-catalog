package com.evolveum.midpoint.integration.catalog.form;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.object.Implementation;

import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter @Setter
public class UploadForm {
    private Application application;
    private Implementation implementation;
    private ImplementationVersion implementationVersion;
    private List<ItemFile> files;
}
