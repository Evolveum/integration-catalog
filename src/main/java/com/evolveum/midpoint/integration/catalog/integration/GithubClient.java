/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
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
        
        createTag(repo, latestCommit.getSHA1(), newVersion);
        
        GHTree tree = treeBuilder.create();
        GHCommit commit = repo.createCommit()
                .message(newVersion.getDescription())
                .tree(tree.getSha())
                .parent(latestCommit.getSHA1())
                .create();

        branchRef.updateTo(commit.getSHA1());

        return repo;
    }

    private void createTag(GHRepository repo, String sha, ImplementationVersion newVersion) {

        String connectorVersion = newVersion.getBundleVersion() != null ?
                newVersion.getBundleVersion().getConnectorVersion() : "unknown";
        String tagVersion = "v" + connectorVersion;

        try {
            GHTagObject tagObject = repo.createTag(tagVersion,
                    newVersion.getDescription(), sha, "commit");
            repo.createRef("refs/tags/" + tagVersion, tagObject.getSha());

        } catch (IOException e) {
            throw new RuntimeException("Exception occurred during tag creation: "+ e);
        }
    }
}
