/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.form.ItemFile;

import com.evolveum.midpoint.integration.catalog.configure.GithubProperties;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;

import org.kohsuke.github.*;
import org.springframework.stereotype.Service;

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

    public void createProject(String nameOfProject, ImplementationVersion newVersion, List<ItemFile> files) throws Exception {
        GitHub github = new GitHubBuilder()
                .withOAuthToken(properties.apiToken())
                .build();

        GHRepository repo = github.createRepository(nameOfProject)
                .description("Repository created via API Integration catalog")
                .private_(false)
                .autoInit(true)
                .create();

        GHRef masterRef = repo.getRef("heads/main");
        GHCommit latestCommit = repo.getCommit(masterRef.getObject().getSha());
        GHTreeBuilder treeBuilder = repo.createTree().baseTree(latestCommit.getSHA1());

        for (ItemFile file : files) {
//            GHBlob blob = repo.createBlob().textContent(file.content()).create();
            treeBuilder.add(file.path(), file.content(), false);
        }

        GHTree tree = treeBuilder.create();
        GHCommit commit = repo.createCommit()
                .message(newVersion.getDescription())
                .tree(tree.getSha())
                .parent(latestCommit.getSHA1())
                .create();

        masterRef.updateTo(commit.getSHA1());
    }
}
