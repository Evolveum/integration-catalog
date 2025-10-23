/*
 * Copyright (C) 2010-2025 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import com.evolveum.midpoint.integration.catalog.common.ItemFile;

import com.evolveum.midpoint.integration.catalog.configuration.GithubProperties;
import com.evolveum.midpoint.integration.catalog.object.ImplementationVersion;

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

    public GHRepository createProject(String nameOfProject, ImplementationVersion newVersion, List<ItemFile> files) throws Exception {
        GitHub github = new GitHubBuilder()
                .withOAuthToken(properties.apiToken())
                .build();

        GHRepository repo = github.createRepository(nameOfProject)
                .description(newVersion.getDescription())
                .private_(false)
                .autoInit(true)
                .create();

//        // create a new branch (named connidVersion of an implementation version) if the branch does not exist
//        // if exist the branch new implementation version push to the existing branch
//        try {
//            repo.getBranch(newVersion.getConnidVersion());
//            branchRef = repo.getRef("heads/" + newVersion.getConnidVersion());
//        } catch (IOException e) {
//            GHBranch baseBranch = repo.getBranch("main"); // or "master"
//            branchRef = repo.createRef("refs/heads/" + newVersion.getConnidVersion(), baseBranch.getSHA1());
//        }

        GHRef branchRef = repo.getRef("heads/main");
        GHCommit latestCommit = repo.getCommit(branchRef.getObject().getSha());
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

        branchRef.updateTo(commit.getSHA1());

        return repo;
    }
}
