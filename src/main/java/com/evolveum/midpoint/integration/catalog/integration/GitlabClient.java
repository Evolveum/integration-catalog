/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

package com.evolveum.midpoint.integration.catalog.integration;

import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.CommitAction;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.TreeItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

public class GitlabClient {

    private final String gitlabUrl = System.getProperty("gitlab.url");
    private final String apiToken = System.getProperty("gitlab.apiToken");
    private final String groupPath = System.getProperty("gitlab.groupPath");
    private final String templatePath = System.getProperty("gitlab.templatePath");
    private final String templateTag = System.getProperty("gitlab.templateTag");

    public void createProject(String nameOfProject, String newVersion, MultipartFile script) throws Exception {
        GitLabApi gitLabApi = new GitLabApi(gitlabUrl, apiToken);

        String templateProjectPath = groupPath + "/" + templatePath;

        Project templateProject = gitLabApi.getProjectApi().getProject(templateProjectPath);

        List<TreeItem> files = gitLabApi.getRepositoryApi()
                .getTree(templateProject.getId(), "/", templateTag, true);

        List<CommitAction> actions = new ArrayList<>();

        for (TreeItem file : files) {
            if (!file.getType().equals(TreeItem.Type.BLOB)) {
                continue;
            }
            String content = gitLabApi.getRepositoryFileApi()
                    .getFile(templateProject.getId(), file.getPath(), "main")
                    .getDecodedContentAsString();

            actions.add(new CommitAction()
                    .withAction(CommitAction.Action.CREATE)
                    .withFilePath(file.getPath())
                    .withContent(content));
        }

        //todo use it for new project .... using existing project because of testing
//        Project newProject = gitLabApi.getProjectApi().createProject(
//                new Project()
//                        .withName(nameOfProject)
//                        .withNamespaceId(gitLabApi.getGroupApi().getGroup(groupPath).getId())
//        );

        Project newProject = gitLabApi.getProjectApi().getProject(groupPath + "/" + "sample-scimdev-noclass");

        gitLabApi.getCommitsApi().createCommit(
                newProject.getId(),
                "main",
                "Commit for version " + newVersion,
                null,
                null,
                null,
                actions
        );

        String encoded = Base64.getEncoder().encodeToString(script.getBytes());

        CommitAction action = new CommitAction()
                .withAction(CommitAction.Action.CREATE)
                .withFilePath("src/main/resources/Schema.schema.groovy")
                .withContent(encoded);

        gitLabApi.getCommitsApi().createCommit(
                newProject.getId(),
                "main",
                "Add script",
                null,
                null,
                null,
                Collections.singletonList(action)
        );

        String tmpBranch = "tmp/" + newVersion;

        gitLabApi.getRepositoryApi().createBranch(newProject.getId(), tmpBranch, "main");

        gitLabApi.getTagsApi().createTag(
                newProject.getId(),
                newVersion,
                tmpBranch,
                "Initial tag " + newVersion,
                (String) null
        );

        gitLabApi.getRepositoryApi().deleteBranch(newProject.getId(), tmpBranch);
    }
}
