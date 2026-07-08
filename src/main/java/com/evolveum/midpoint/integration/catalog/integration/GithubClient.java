/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.common.ItemFile;
import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.object.ConnectorVersion;

import org.kohsuke.github.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Created by Dominik.
 */
@Service
public class GithubClient {

    private final GithubProperties properties;

    public GithubClient(GithubProperties properties) {
        this.properties = properties;
    }

    public GHRepository createProjectForConnectorVersion(String nameOfProject, ConnectorVersion connectorVersion,
                                                          List<ItemFile> files) throws Exception {
        GitHub github = new GitHubBuilder()
                .withOAuthToken(properties.apiToken())
                .build();

        String description = connectorVersion.getFullyQualifiedClassName() != null
                ? connectorVersion.getFullyQualifiedClassName() : nameOfProject;

        GHRepository repo = github.createRepository(nameOfProject)
                .description(description)
                .private_(false)
                .autoInit(true)
                .create();

        GHRef branchRef = repo.getRef("heads/main");
        GHCommit latestCommit = repo.getCommit(branchRef.getObject().getSha());
        GHTreeBuilder treeBuilder = repo.createTree().baseTree(latestCommit.getSHA1());

        for (ItemFile file : files) {
            treeBuilder.add(file.path(), file.content(), false);
        }

        String bundleVersion = resolveBundleVersion(connectorVersion);
        createTag(repo, latestCommit.getSHA1(), description, bundleVersion);

        GHTree tree = treeBuilder.create();
        GHCommit commit = repo.createCommit()
                .message(description)
                .tree(tree.getSha())
                .parent(latestCommit.getSHA1())
                .create();

        branchRef.updateTo(commit.getSHA1());

        return repo;
    }

    private String resolveBundleVersion(ConnectorVersion connectorVersion) {
        if (connectorVersion.getConnectorBundleVersion() != null
                && connectorVersion.getConnectorBundleVersion().getBundleVersion() != null) {
            return connectorVersion.getConnectorBundleVersion().getBundleVersion();
        }
        return "1.0.0";
    }

    private void createTag(GHRepository repo, String sha, String description, String version) {
        String tagVersion = "v" + version;
        try {
            GHTagObject tagObject = repo.createTag(tagVersion, description, sha, "commit");
            repo.createRef("refs/tags/" + tagVersion, tagObject.getSha());
        } catch (IOException e) {
            throw new RuntimeException("Exception occurred during tag creation: " + e);
        }
    }
}
